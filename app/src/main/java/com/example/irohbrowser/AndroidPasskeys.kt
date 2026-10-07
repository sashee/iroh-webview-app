package com.example.irohbrowser

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** The logcat tag the Rust half logs under too, so one filter shows both. */
private const val LOG_TAG = "irohbrowser"

/*
 * The platform half of passkeys: Keystore, BiometricPrompt, the WebView
 * bridge. Thin on purpose, like NativeProxy -- every decision is in
 * PasskeyAuthenticator and PasskeyRequests, where the tests can reach it.
 * Nothing here runs under Robolectric, so it is exercised on the device.
 */

/**
 * Keys in the Android Keystore, in the StrongBox chip where there is one (the
 * Titan M2 on a Pixel 6a), in the TEE otherwise.
 *
 * Every key needs the user: a strong biometric, or the screen lock as a
 * fallback for when the sensor will not read. Signing keys and PRF keys need a
 * touch per operation. The one exception is the signing key of a passkey with
 * PRF, which accepts the touch its PRF key just took, for a few seconds --
 * because the phone ties each touch to a single operation, and one touch per
 * sign-in was the requirement.
 */
object AndroidKeyVault : KeyVault {

    private const val KEYSTORE = "AndroidKeyStore"

    /** A strong biometric, or the screen lock for when the sensor will not read. */
    private const val USER = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

    /**
     * How long a window key accepts a touch. The signature follows the touch
     * within a fraction of a second; the rest is room for StrongBox, which is
     * slow, to finish the PRF key's operation first.
     */
    private const val TOUCH_WINDOW_SECONDS = 5

    override fun create(alias: String, touchWindow: Boolean): ECPublicKey {
        val publicKey = inStrongBoxIfPossible { strongBox ->
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(if (touchWindow) TOUCH_WINDOW_SECONDS else 0, USER)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
            }.generateKeyPair().public as ECPublicKey
        }
        // The one place that says whether the key really is in the chip.
        Log.i(LOG_TAG, "passkey key created in ${storage(alias)}")
        return publicKey
    }

    override fun createPrf(alias: String) {
        inStrongBoxIfPossible { strongBox ->
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, USER)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
            }.generateKey()
        }
        Log.i(LOG_TAG, "passkey PRF key created in ${storage(alias)}")
    }

    override fun storage(alias: String): KeyStorage? {
        val key = keyStore().getKey(alias, null) ?: return null
        val info = runCatching {
            when (key) {
                is PrivateKey -> KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
                is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
                else -> null
            }
        }.getOrNull() ?: return KeyStorage.Unknown
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyStorage.StrongBox
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyStorage.TrustedEnvironment
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyStorage.Software
            else -> KeyStorage.Unknown
        }
    }

    override fun signer(alias: String): Signature? {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return null
        return try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    override fun prf(alias: String): Mac? {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
        return try {
            Mac.getInstance("HmacSHA256").apply { init(key) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            null
        }
    }

    override fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun <T> inStrongBoxIfPossible(make: (strongBox: Boolean) -> T): T =
        try {
            make(true)
        } catch (_: StrongBoxUnavailableException) {
            make(false)
        }
}

/** The system's fingerprint prompt, and a plain dialog for choosing an account. */
class BiometricPasskeyUi(private val activity: Activity) : PasskeyUi {

    override fun choose(endpointName: String, accounts: List<String>, chosen: (Int?) -> Unit): () -> Unit {
        var answered = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.passkey_choose_account, endpointName))
            .setItems(accounts.toTypedArray()) { _, index ->
                answered = true
                chosen(index)
            }
            .setOnDismissListener { if (!answered) chosen(null) }
            .show()
        return dialog::dismiss
    }

    override fun verify(
        purpose: PasskeyPurpose,
        account: String,
        endpointName: String,
        operation: KeyOperation,
        done: (Outcome<KeyOperation>) -> Unit,
    ): () -> Unit {
        val cancellation = CancellationSignal()
        val title = when (purpose) {
            PasskeyPurpose.Register -> R.string.passkey_register_title
            PasskeyPurpose.SignIn -> R.string.passkey_sign_in_title
        }
        val subtitle = if (account.isBlank()) {
            endpointName
        } else {
            activity.getString(R.string.passkey_account_on_endpoint, account, endpointName)
        }
        BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(title))
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(
                when (operation) {
                    is KeyOperation.Signing -> BiometricPrompt.CryptoObject(operation.signature)
                    is KeyOperation.Hmac -> BiometricPrompt.CryptoObject(operation.mac)
                },
                cancellation,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val crypto = result.cryptoObject
                        val unlocked = when (operation) {
                            is KeyOperation.Signing -> crypto?.signature?.let(KeyOperation::Signing)
                            is KeyOperation.Hmac -> crypto?.mac?.let(KeyOperation::Hmac)
                        }
                        done(
                            if (unlocked != null) {
                                Outcome.Ok(unlocked)
                            } else {
                                Outcome.Failed(PasskeyError.unknown("The prompt returned no key."))
                            },
                        )
                    }

                    // Cancelled, timed out, locked out, no fingerprint set up:
                    // to the page these are all "not allowed", and the system's
                    // message says which.
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        done(Outcome.Failed(PasskeyError.notAllowed(message.toString())))
                    }
                },
            )
        return cancellation::cancel
    }
}

/**
 * The bridge and the script, through androidx.webkit.
 *
 * Both are restricted to exactly one origin -- scheme, host and port -- by the
 * WebView itself: pages of any other origin get neither the script nor the
 * injected object.
 */
object WebViewPasskeyInstaller : PasskeyInstaller {

    override fun install(webView: WebView, origin: String, receive: PasskeyReceiver): (() -> Unit)? {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!supported) return null

        val rules = setOf(origin)
        val script = webView.context.assets.open(PasskeyBridge.SCRIPT_ASSET).bufferedReader().use { it.readText() }
        return try {
            WebViewCompat.addWebMessageListener(webView, PasskeyBridge.NAME, rules) { _, message, sourceOrigin, _, replyProxy ->
                val data = message.data ?: return@addWebMessageListener
                receive(data, sourceOrigin.toString()) { replyProxy.postMessage(it) }
            }
            val handler = WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
            val uninstall: () -> Unit = {
                handler.remove()
                WebViewCompat.removeWebMessageListener(webView, PasskeyBridge.NAME)
            }
            uninstall
        } catch (cause: IllegalArgumentException) {
            // A rule the WebView will not accept. Browsing must not depend on
            // passkeys, so the endpoint opens without them -- and logcat, the
            // only place this would ever show, says why.
            runCatching { WebViewCompat.removeWebMessageListener(webView, PasskeyBridge.NAME) }
            Log.w(LOG_TAG, "passkeys unavailable for $origin: ${cause.message}")
            null
        }
    }

}

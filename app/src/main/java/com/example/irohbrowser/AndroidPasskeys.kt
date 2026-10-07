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
 * Each key needs the user for every single signature: a strong biometric, or
 * the screen lock as a fallback for when the sensor will not read. There is no
 * "unlocked for the next 30 seconds" window for a page to use.
 */
object AndroidKeyVault : KeyVault {

    private const val KEYSTORE = "AndroidKeyStore"

    override fun create(alias: String): ECPublicKey {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        fun generate(strongBox: Boolean): ECPublicKey {
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            return generator.generateKeyPair().public as ECPublicKey
        }
        val publicKey = try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
        // The one place that says whether the key really is in the chip.
        Log.i(LOG_TAG, "passkey key created in ${storage(alias)}")
        return publicKey
    }

    override fun storage(alias: String): KeyStorage? {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return null
        val info = runCatching {
            KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
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

    override fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
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
        signature: Signature,
        done: (Outcome<Signature>) -> Unit,
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
                BiometricPrompt.CryptoObject(signature),
                cancellation,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val unlocked = result.cryptoObject?.signature
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

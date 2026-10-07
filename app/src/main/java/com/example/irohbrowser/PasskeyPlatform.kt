package com.example.irohbrowser

import android.app.Activity
import android.webkit.WebView
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Mac

/**
 * Where passkeys' keys live. The real one is the Android Keystore
 * ([AndroidKeyVault]); tests use software keys, because the point of the real
 * one is that nothing -- a test included -- can get a signature out of it
 * without a finger on the sensor.
 */
interface KeyVault {
    /**
     * Create a P-256 key under [alias] that signs only after the user is
     * verified, and return its public half. Throws when no such key can be
     * made, which on a phone means no fingerprint and no screen lock is set up.
     *
     * Normally each signature needs its own touch. With [touchWindow] the key
     * instead accepts a touch from the last few seconds: for passkeys with a
     * PRF key, where the touch itself goes to the PRF key and the signature
     * follows straight after.
     */
    fun create(alias: String, touchWindow: Boolean = false): ECPublicKey

    /**
     * A signature primed with [alias]'s key. Null when the key is gone -- or
     * invalidated, which Android does to keys like these when a fingerprint
     * is added.
     *
     * For an ordinary key, call it before the touch: the prompt unlocks it.
     * For a [create]d-with-touchWindow key, call it after: priming it is what
     * needs the recent touch, and it throws without one.
     */
    fun signer(alias: String): Signature?

    /**
     * Create an HMAC-SHA-256 key under [alias] -- a passkey's PRF key -- that
     * needs a touch for every single operation. Throws when none can be made.
     */
    fun createPrf(alias: String)

    /** An HMAC primed with [alias]'s PRF key, for the prompt to unlock; null when gone or invalidated. */
    fun prf(alias: String): Mac?

    fun delete(alias: String)

    /** Where [alias]'s key lives, or null when there is no such key. */
    fun storage(alias: String): KeyStorage?
}

/**
 * The one hardware operation a touch unlocks. The phone ties each touch to
 * exactly one, which is why a passkey with PRF needs its signing key to take
 * the touch on trust for a moment (see [KeyVault.create]).
 */
sealed interface KeyOperation {
    class Signing(val signature: Signature) : KeyOperation
    class Hmac(val mac: Mac) : KeyOperation
}

/** Where a key lives, from strongest to weakest. */
enum class KeyStorage {
    /** A separate secure chip -- the Titan M2 on a Pixel 6a. */
    StrongBox,

    /** The main processor's trusted execution environment. */
    TrustedEnvironment,

    /** Ordinary memory: an emulator's Keystore, or the tests' software keys. */
    Software,

    /** Secure hardware the platform would not name. */
    Unknown,
}

enum class PasskeyPurpose { Register, SignIn }

/** What the user sees during a ceremony. */
interface PasskeyUi {
    /**
     * Ask which of several accounts to sign in to [endpointName] as. [chosen]
     * gets the index, or null when the user declined. Returns a way to
     * withdraw the question.
     */
    fun choose(endpointName: String, accounts: List<String>, chosen: (Int?) -> Unit): () -> Unit

    /**
     * Ask for the fingerprint, or screen lock, that unlocks [operation]. [done]
     * gets it unlocked, or why it is not. Returns a way to withdraw the
     * prompt, which also answers [done].
     */
    fun verify(
        purpose: PasskeyPurpose,
        account: String,
        endpointName: String,
        operation: KeyOperation,
        done: (Outcome<KeyOperation>) -> Unit,
    ): () -> Unit
}

/** A message from a page: its text, the origin the WebView reports for it, and a way to answer. */
typealias PasskeyReceiver = (message: String, sourceOrigin: String, reply: (String) -> Unit) -> Unit

/** Making the bridge, and the script that uses it, available to one origin's pages. */
fun interface PasskeyInstaller {
    /**
     * Install for pages of exactly [origin]. Returns how to uninstall, or null
     * when this WebView lacks what the bridge needs -- in which case pages see
     * no passkey support at all, rather than a broken one.
     */
    fun install(webView: WebView, origin: String, receive: PasskeyReceiver): (() -> Unit)?
}

/** Everything passkeys need from the platform, replaced as a unit by tests. */
class PasskeyPlatform(
    val installer: PasskeyInstaller,
    val store: PasskeyStore,
    val vault: KeyVault,
    /** A factory: the real UI needs the activity, which does not exist when the container is built. */
    val ui: (Activity) -> PasskeyUi,
    val random: (Int) -> ByteArray = ::secureRandomBytes,
    val clock: () -> Long = System::currentTimeMillis,
)

private val secureRandom = SecureRandom()

private fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

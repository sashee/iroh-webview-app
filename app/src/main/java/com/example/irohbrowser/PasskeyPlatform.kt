package com.example.irohbrowser

import android.app.Activity
import android.webkit.WebView
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey

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
     */
    fun create(alias: String): ECPublicKey

    /**
     * A signature primed with [alias]'s key, for the prompt to unlock. Null
     * when the key is gone -- or invalidated, which Android does to every key
     * like this one when a fingerprint is added.
     */
    fun signer(alias: String): Signature?

    fun delete(alias: String)

    /** Where [alias]'s key lives, or null when there is no such key. */
    fun storage(alias: String): KeyStorage?
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
     * Ask for the fingerprint, or screen lock, that unlocks [signature]. [done]
     * gets the unlocked signature, or why there is none. Returns a way to
     * withdraw the prompt, which also answers [done].
     */
    fun verify(
        purpose: PasskeyPurpose,
        account: String,
        endpointName: String,
        signature: Signature,
        done: (Outcome<Signature>) -> Unit,
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

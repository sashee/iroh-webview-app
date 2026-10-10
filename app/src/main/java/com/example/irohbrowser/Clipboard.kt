package com.example.irohbrowser

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import org.json.JSONObject

/**
 * Text a page copies, put on the clipboard marked sensitive.
 *
 * The WebView writes the clipboard without Android's sensitivity flag, so a
 * password copied from a page shows in the system's copy preview and stays in
 * the keyboard's clipboard history. `assets/clipboard.js` hands
 * `navigator.clipboard`'s writes to the app instead, and [copySensitive] writes
 * them with the flag set. That is the first and only write, so nothing sees
 * the text unflagged.
 *
 * As narrow as the passkey bridge: text goes one way, from the endpoint's own
 * origin, while its page is on screen. The page could already replace the
 * clipboard through the browser, so the bridge gives it nothing new.
 */
object ClipboardBridge {

    /** The name of the object the WebView injects into pages; the script looks for it. */
    const val NAME = "__irohClipboard"

    /** The script, in the APK's assets. */
    const val SCRIPT_ASSET = "clipboard.js"

    /**
     * The reply to a page's [message], once its text has been copied, or why
     * it was not. Null for a message with no id to answer to.
     *
     * [onScreen] is the browser's own rule: only a focused page may write the
     * clipboard. Android lets an app in the background write it, and a page
     * keeps running while the app is in the background.
     */
    fun answer(
        message: String,
        sourceOrigin: String,
        site: ProxyBinding,
        onScreen: Boolean,
        copy: (String) -> Boolean,
    ): String? {
        val envelope = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val id = envelope.optInt("id", -1).takeIf { it >= 0 } ?: return null
        val text = envelope.opt("text") as? String
        // `when` stops at the first refusal, so the copy happens only once
        // every check has passed.
        val refusal = when {
            !Origins.isOwnOrigin(sourceOrigin, site.label, site.port) -> "SecurityError" to "Not this endpoint's page."
            text == null -> "TypeError" to "No text to copy."
            !onScreen -> "NotAllowedError" to "Document is not focused."
            !copy(text) -> "NotAllowedError" to "The clipboard refused the text."
            else -> null
        }
        val reply = JSONObject().put("id", id)
        refusal?.let { (name, why) -> reply.put("error", JSONObject().put("name", name).put("message", why)) }
        return reply.toString()
    }
}

/**
 * Put [text] on the clipboard with [ClipDescription.EXTRA_IS_SENSITIVE] set:
 * the copy preview shows dots, and keyboards leave it out of their history.
 * False when the system refused it, which a text too large for a binder
 * transaction does.
 */
fun copySensitive(context: Context, text: String): Boolean {
    val clip = ClipData.newPlainText("text", text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    return runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
        // The text itself is never logged: it is somebody's password.
        .onFailure { Log.w(LOG_TAG, "a page's copy was refused: ${it.javaClass.simpleName}") }
        .isSuccess
}

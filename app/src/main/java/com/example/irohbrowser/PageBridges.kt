package com.example.irohbrowser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/*
 * The ways page JavaScript reaches into the app: an injected script and the
 * WebMessageListener it posts to, offered to one origin at a time. There are
 * two, passkeys (PasskeyBridge) and the clipboard (ClipboardBridge). Both carry
 * data, not callable methods.
 */

/** A message from a page: its text, the origin the WebView reports for it, and a way to answer. */
typealias BridgeReceiver = (message: String, sourceOrigin: String, reply: (String) -> Unit) -> Unit

/** Making a bridge, and the script that uses it, available to one origin's pages. */
fun interface BridgeInstaller {
    /**
     * Install for pages of exactly [origin]. Returns how to uninstall, or null
     * when this WebView lacks what the bridge needs -- in which case the
     * script is not installed either, and pages are left as they were.
     */
    fun install(webView: WebView, origin: String, receive: BridgeReceiver): (() -> Unit)?
}

/**
 * The bridge named [name] and the script in [scriptAsset], through
 * androidx.webkit.
 *
 * Both are restricted to exactly one origin -- scheme, host and port -- by the
 * WebView itself: pages of any other origin get neither the script nor the
 * injected object.
 */
class WebViewBridgeInstaller(private val name: String, private val scriptAsset: String) : BridgeInstaller {

    override fun install(webView: WebView, origin: String, receive: BridgeReceiver): (() -> Unit)? {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!supported) return null

        val rules = setOf(origin)
        val script = webView.context.assets.open(scriptAsset).bufferedReader().use { it.readText() }
        return try {
            WebViewCompat.addWebMessageListener(webView, name, rules) { _, message, sourceOrigin, _, replyProxy ->
                val data = message.data ?: return@addWebMessageListener
                receive(data, sourceOrigin.toString()) { replyProxy.postMessage(it) }
            }
            val handler = WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
            val uninstall: () -> Unit = {
                handler.remove()
                WebViewCompat.removeWebMessageListener(webView, name)
            }
            uninstall
        } catch (cause: IllegalArgumentException) {
            // A rule the WebView will not accept. Browsing must not depend on
            // a bridge, so the endpoint opens without it -- and logcat, the
            // only place this would ever show, says why.
            runCatching { WebViewCompat.removeWebMessageListener(webView, name) }
            Log.w(LOG_TAG, "$name unavailable for $origin: ${cause.message}")
            null
        }
    }
}

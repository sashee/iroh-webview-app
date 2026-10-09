package com.example.irohbrowser

import android.util.Log
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Keeps the WebView off the network, except through the tunnel.
 *
 * A page comes from an arbitrary peer, and whatever it names on another host
 * -- an image, a font, a beacon -- would otherwise be fetched straight from
 * the internet. Three layers stop that, each covering what the one before it
 * cannot see:
 *
 * - [Origins.mayRequest] refuses every request that is not the running
 *   proxy's origin. It sees subresources, fetches, forms and service workers,
 *   but never WebSockets or the connections the browser opens speculatively.
 * - [proxyConfig] sends every connection but those to `*.localhost` to a
 *   proxy that is not there. That catches the rest of what goes over TCP.
 * - [SCRIPT_ASSET] takes WebRTC away from pages, because its UDP passes
 *   beneath both.
 *
 * iroh is not affected: it runs in the Rust half, outside the WebView's
 * network stack.
 */
object Confinement {

    /**
     * Where every connection off the device is sent. Nothing can listen on a
     * port below 1024 without root, so each connection is refused at once.
     */
    const val NOWHERE = "127.0.0.1:1"

    /**
     * Every host but `*.localhost` through [NOWHERE].
     *
     * Chromium bypasses loopback and link-local hosts by default, and
     * `removeImplicitRules` takes those away, so that only the endpoints'
     * hosts go direct. The order matters: later bypass rules override earlier
     * ones, and the other way round `<-loopback>` would send `*.localhost` --
     * the tunnel itself -- to [NOWHERE] too.
     */
    fun proxyConfig(): ProxyConfig =
        ProxyConfig.Builder()
            .addProxyRule(NOWHERE)
            .removeImplicitRules()
            .addBypassRule("*.localhost")
            .build()

    /**
     * Apply [proxyConfig] to every WebView in the process. Once, before the
     * first page: the override is the process's, and outlives any activity.
     */
    fun confineNetwork() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(LOG_TAG, "this WebView cannot override its proxy; only the request check confines it")
            return
        }
        androidx.webkit.ProxyController.getInstance().setProxyOverride(proxyConfig(), Runnable::run) {
            Log.i(LOG_TAG, "WebView network confined to *.localhost")
        }
    }

    /** The script that removes WebRTC, run in every page before the page's own. */
    const val SCRIPT_ASSET = "no-webrtc.js"
}

/**
 * Scripts run at the start of every document, whatever its origin.
 *
 * An interface because `WebViewCompat` needs the WebView provider, which a
 * Robolectric test does not have.
 */
fun interface PageScripts {
    /** Run [script] in every document [webView] starts from now on. */
    fun install(webView: WebView, script: String)
}

/** The real one. */
object WebViewPageScripts : PageScripts {
    override fun install(webView: WebView, script: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(LOG_TAG, "this WebView cannot run scripts at document start; pages keep WebRTC")
            return
        }
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
    }
}

package com.example.irohbrowser

import android.net.Uri

/**
 * The loopback origin an endpoint is browsed at.
 *
 * Every endpoint gets its own `<label>.localhost` hostname. Chromium resolves
 * `*.localhost` to loopback without DNS and treats each label as a distinct
 * origin, so this is what gives each endpoint a separate cookie jar and a
 * separate autofill identity — a port cannot do it, because ports are not part
 * of a cookie's key.
 *
 * The label comes from the Rust side, which derives it from the endpoint id, so
 * returning to an endpoint returns to the same origin.
 */
object Origins {

    /** The page the WebView is pointed at for a freshly started proxy. */
    fun url(label: String, port: Int): String = "http://$label.localhost:$port/"

    /** The origin, without a path, that [url] belongs to. */
    fun origin(label: String, port: Int): String = "http://$label.localhost:$port"

    /**
     * Whether [url] is a page of the running proxy, and so belongs inside the
     * WebView rather than in the real browser.
     *
     * Deliberately strict about scheme, host and port together: a link to
     * `http://evil.localhost:PORT/` is not ours even though the port matches,
     * and neither is `https://` on our own host.
     */
    fun isOwnOrigin(url: String?, label: String, port: Int): Boolean {
        val uri = runCatching { Uri.parse(url ?: return false) }.getOrNull() ?: return false
        return uri.scheme == "http" &&
            uri.host.equals("$label.localhost", ignoreCase = true) &&
            uri.port == port
    }

    /**
     * Whether a navigation should be handed to the real browser.
     *
     * Anything that is not an ordinary web page is refused outright rather than
     * forwarded: `intent:` can start arbitrary components, `file:` and
     * `content:` reach the device's storage, and `javascript:` would run in
     * whatever page is loaded. None of them should be reachable from a page
     * served by an arbitrary peer.
     */
    fun externalDestination(url: String?, label: String, port: Int): Destination {
        if (url.isNullOrBlank()) return Destination.Refuse
        if (isOwnOrigin(url, label, port)) return Destination.Keep
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase()
        return when (scheme) {
            "http", "https" -> Destination.OpenExternally
            else -> Destination.Refuse
        }
    }

    /** What to do with a navigation the WebView is asking about. */
    enum class Destination {
        /** Load it in the WebView. */
        Keep,

        /** Hand it to the real browser. */
        OpenExternally,

        /** Do nothing at all. */
        Refuse,
    }
}

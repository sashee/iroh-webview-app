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

    /**
     * The hostname an endpoint is browsed at -- and so also the RP ID its
     * passkeys belong to, which is why it must stay a pure function of the
     * label.
     */
    fun host(label: String): String = "$label.localhost"

    /** The page the WebView is pointed at for a freshly started proxy. */
    fun url(label: String, port: Int): String = "http://${host(label)}:$port/"

    /** The origin, without a path, that [url] belongs to. */
    fun origin(label: String, port: Int): String = "http://${host(label)}:$port"

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
     * Whether the WebView may send a request to [url] while [running] is the
     * proxy behind the page.
     *
     * Only to the running proxy's exact origin, port included. Nothing else
     * goes through the tunnel, so anything else would leave the device: an
     * image or a beacon on another host tells it the phone's address and what
     * the page is, and the page comes from an arbitrary peer. On loopback the
     * port matters as much, because cookies are keyed by host and not by
     * port: a request to `<label>.localhost` on any other port carries that
     * endpoint's session to whatever is listening there, and any app on the
     * device can listen on a loopback port the proxy is not holding.
     *
     * `data:`, `blob:` and `about:` pass: the page or the browser made them,
     * and fetching one sends nothing anywhere. History navigation and restored
     * pages never pass through [externalDestination], so this is checked on
     * every request instead.
     */
    fun mayRequest(url: String?, running: ProxyBinding?): Boolean =
        (running != null && isOwnOrigin(url, running.label, running.port)) || scheme(url) in LOCAL_SCHEMES

    private val LOCAL_SCHEMES = setOf("data", "blob", "about")

    private fun scheme(url: String?): String? =
        runCatching { Uri.parse(url ?: return null).scheme }.getOrNull()?.lowercase()

    /**
     * Whether a navigation should be handed to the real browser.
     *
     * Only when [byUser]: a tap, not the page sending itself somewhere. A page
     * could otherwise open the real browser on a timer, carrying whatever it
     * likes in the URL to wherever it likes.
     *
     * Anything that is not an ordinary web page is refused outright rather than
     * forwarded: `intent:` can start arbitrary components, `file:` and
     * `content:` reach the device's storage, and `javascript:` would run in
     * whatever page is loaded. None of them should be reachable from a page
     * served by an arbitrary peer.
     */
    fun externalDestination(url: String?, label: String, port: Int, byUser: Boolean): Destination {
        if (url.isNullOrBlank()) return Destination.Refuse
        if (isOwnOrigin(url, label, port)) return Destination.Keep
        return when (scheme(url)) {
            "http", "https" -> if (byUser) Destination.OpenExternally else Destination.Refuse
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

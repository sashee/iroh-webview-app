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
     * Whether [url] points at this device: `localhost` and every name under it,
     * the whole 127/8 block, `0.0.0.0`, and `[::1]` with its IPv4-mapped forms.
     *
     * Exact rather than generous with hostnames: the WebView canonicalises a
     * URL before the app sees it, so a loopback address written as a decimal
     * number or with a trailing dot arrives in one of these forms.
     */
    fun isLoopback(url: String?): Boolean {
        val host = runCatching { Uri.parse(url ?: return false).host }.getOrNull()
            ?.lowercase()?.trimEnd('.') ?: return false
        val ipv6 = host.removePrefix("[").removeSuffix("]")
        // ::ffff:127.0.0.1, which the WebView writes as ::ffff:7f00:1.
        val mapped = ipv6.takeIf { it.startsWith("::ffff:") }?.removePrefix("::ffff:")
        return host == "localhost" ||
            host.endsWith(".localhost") ||
            IPV4_LOOPBACK.matches(host) ||
            host == "0.0.0.0" ||
            ipv6 == "::1" ||
            (mapped != null && (IPV4_LOOPBACK.matches(mapped) || mapped.startsWith("7f")))
    }

    /**
     * Whether the WebView may send a request to [url] while [running] is the
     * proxy behind the page.
     *
     * Anything that is not loopback may go. Loopback may go only to the running
     * proxy's exact origin, port included, because cookies are keyed by host
     * and not by port: a request to `<label>.localhost` on any other port
     * carries that endpoint's session to whatever is listening there, and any
     * app on the device can listen on a loopback port the proxy is not holding.
     * History navigation and restored pages never pass through
     * [externalDestination], so this is checked on every request instead.
     */
    fun mayRequest(url: String?, running: ProxyBinding?): Boolean =
        !isLoopback(url) || (running != null && isOwnOrigin(url, running.label, running.port))

    private val IPV4_LOOPBACK = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

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

package com.example.irohbrowser

/** A running proxy: where to point an endpoint's WebView. */
data class ProxyBinding(val label: String, val port: Int)

/**
 * An endpoint's origin, worked out from its ticket without starting anything:
 * the label its host is named by, and the port its proxy prefers. A running
 * proxy normally binds exactly that port, but may have fallen back to another,
 * so for the running endpoint [ProxyBinding] is the authority.
 */
data class EndpointIdentity(val label: String, val preferredPort: Int)

/** Why a proxy would not start, in terms the UI can show. */
enum class ProxyError {
    /** What was pasted is not a ticket or an endpoint id. */
    BadTicket,

    /** The endpoint or the loopback socket would not bind. */
    StartFailed,
}

sealed interface ProxyResult {
    data class Started(val binding: ProxyBinding) : ProxyResult
    data class Failed(val error: ProxyError) : ProxyResult
}

/**
 * The boundary between the app and the Rust library.
 *
 * An interface so that everything above it is testable without loading a native
 * library: Robolectric runs on the host JVM, where `libiroh_webview_proxy.so`
 * is for the wrong architecture and could not be loaded even if it were built.
 */
interface ProxyController {
    /**
     * Start the proxy for [ticket]'s endpoint. One already running for the same
     * endpoint is replaced; those of other endpoints, open in other cards, go on.
     */
    fun start(ticket: String): ProxyResult

    /** Stop the proxy of the endpoint labelled [label], if one runs. The others go on. */
    fun stop(label: String)

    /**
     * Every proxy running, one per card with a page: what the checks that are
     * the whole process's, the service workers', let requests reach.
     * Thread-safe, as those run on the WebView's threads.
     */
    fun running(): List<ProxyBinding>

    /** The identity of the endpoint [ticket] names, or null when it is not a ticket. Pure. */
    fun identify(ticket: String): EndpointIdentity?

    /**
     * Android blocked this app's network ([blocked]), or gave it back. Every
     * running proxy closes every connection it has open, and when the network
     * is back asks iroh to look at it again. See [NetworkBlockWatch].
     */
    fun networkBlocked(blocked: Boolean)
}

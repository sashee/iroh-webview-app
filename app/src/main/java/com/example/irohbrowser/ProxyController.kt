package com.example.irohbrowser

/** A running proxy: where to point the WebView. */
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
    fun start(ticket: String): ProxyResult
    fun stop()

    /** The identity of the endpoint [ticket] names, or null when it is not a ticket. Pure. */
    fun identify(ticket: String): EndpointIdentity?
}

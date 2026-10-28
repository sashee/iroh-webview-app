package com.example.irohbrowser

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * The real [ProxyController]: a thin wrapper over the JNI entry points in
 * `rust/src/android.rs`.
 *
 * Nothing here decides anything. The error codes and their meanings are the
 * contract with the Rust side and are mirrored there.
 */
object NativeProxy : ProxyController {

    private const val ERROR_BAD_TICKET = -1
    private const val ERROR_START_FAILED = -2
    private const val ERROR_BAD_ARGUMENT = -3

    /**
     * What runs on the Rust side, by label, kept here as well: the service
     * workers' check reads it for every request, and a call across JNI and a
     * lock each time is more than that needs.
     */
    private val bindings = ConcurrentHashMap<String, ProxyBinding>()

    fun load() {
        System.loadLibrary("iroh_webview_proxy")
    }

    /**
     * Give iroh's dependencies a way to call into the platform.
     *
     * Not for DNS: the proxy resolves over DoH and never reads the device's
     * nameservers. netdev, which reads the device's interfaces, needs it.
     * Called once with the application context, whose lifetime is the process's.
     */
    fun installContext(context: Context) {
        nativeInstallContext(context.applicationContext)
    }

    override fun start(ticket: String): ProxyResult {
        val port = nativeStart(ticket)
        return when {
            port > 0 -> {
                // Read from the ticket the way the proxy read it: the same label.
                val label = identify(ticket)?.label
                if (label.isNullOrBlank()) {
                    ProxyResult.Failed(ProxyError.StartFailed)
                } else {
                    val binding = ProxyBinding(label, port)
                    bindings[label] = binding
                    ProxyResult.Started(binding)
                }
            }
            port == ERROR_BAD_TICKET || port == ERROR_BAD_ARGUMENT ->
                ProxyResult.Failed(ProxyError.BadTicket)
            else -> ProxyResult.Failed(ProxyError.StartFailed)
        }
    }

    override fun stop(label: String) {
        nativeStop(label)
        bindings.remove(label)
    }

    override fun running(): List<ProxyBinding> = bindings.values.toList()

    override fun networkBlocked(blocked: Boolean) {
        nativeNetworkBlocked(blocked)
    }

    override fun identify(ticket: String): EndpointIdentity? {
        val identity = nativeIdentity(ticket) ?: return null
        val label = identity.substringBeforeLast(':')
        val port = identity.substringAfterLast(':').toIntOrNull() ?: return null
        return EndpointIdentity(label, port)
    }

    private external fun nativeInstallContext(context: Context)
    private external fun nativeStart(ticket: String): Int
    private external fun nativeStop(label: String)
    private external fun nativeIdentity(ticket: String): String?
    private external fun nativeNetworkBlocked(blocked: Boolean)
}

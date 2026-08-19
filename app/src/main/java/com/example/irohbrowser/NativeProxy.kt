package com.example.irohbrowser

import android.content.Context

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

    fun load() {
        System.loadLibrary("iroh_webview_proxy")
    }

    /**
     * Give iroh a way to read the device's DNS configuration.
     *
     * Android has no `/etc/resolv.conf`; without this, resolving a bare endpoint
     * id falls back to public nameservers. Called once with the application
     * context, whose lifetime is the process's.
     */
    fun installContext(context: Context) {
        nativeInstallContext(context.applicationContext)
    }

    override fun start(ticket: String): ProxyResult {
        val port = nativeStart(ticket)
        return when {
            port > 0 -> {
                val label = nativeLabel()
                if (label.isNullOrBlank()) {
                    ProxyResult.Failed(ProxyError.StartFailed)
                } else {
                    ProxyResult.Started(ProxyBinding(label, port))
                }
            }
            port == ERROR_BAD_TICKET || port == ERROR_BAD_ARGUMENT ->
                ProxyResult.Failed(ProxyError.BadTicket)
            else -> ProxyResult.Failed(ProxyError.StartFailed)
        }
    }

    override fun stop() {
        nativeStop()
    }

    private external fun nativeInstallContext(context: Context)
    private external fun nativeStart(ticket: String): Int
    private external fun nativeLabel(): String?
    private external fun nativeStop()
}

package com.example.irohbrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Android blocking this app's network, and giving it back, passed on to the
 * proxy.
 *
 * From Android 15, an app that leaves the foreground without a foreground
 * service loses its network about five seconds later; picking a file in the
 * system's picker is enough. The iroh connections open across that may carry a
 * small request afterwards, but never bulk data again: an upload on one hangs
 * for many minutes. So on each change the proxy closes every connection it has
 * open, and the page's next requests open fresh ones (DESIGN.md, Android
 * specifics).
 *
 * The first report, which comes when the callback is registered, is the state
 * then rather than a change, and is only remembered.
 */
class NetworkBlockWatch(private val proxy: ProxyController) {

    /** The last state reported: null until the first report. */
    private var blocked: Boolean? = null

    /** A report of whether the app's network is blocked now. */
    @Synchronized
    fun report(now: Boolean) {
        val before = blocked
        blocked = now
        if (before != null && before != now) proxy.networkBlocked(now)
    }

    /** Listen for reports for the app's default network, for the rest of the process. */
    fun register(context: Context) {
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = report(blocked)
            },
        )
    }
}

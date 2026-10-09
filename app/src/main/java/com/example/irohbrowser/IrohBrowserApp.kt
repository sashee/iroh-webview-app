package com.example.irohbrowser

import android.app.Application

/** The logcat tag the Rust half logs under too, so one filter shows both. */
internal const val LOG_TAG = "irohbrowser"

/**
 * Loads the native library, hands iroh an Android context, and keeps the
 * WebView off the network.
 *
 * All three happen here rather than in the activity because all three are
 * process-wide and must happen before the first proxy starts: the context has
 * to be installed before iroh builds its first DNS resolver, and the WebView
 * confined before it loads its first page.
 */
class IrohBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NativeProxy.load()
        NativeProxy.installContext(this)
        Confinement.confineNetwork()
    }
}

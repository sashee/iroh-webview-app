package com.example.irohbrowser

import android.app.Application

/** The logcat tag the Rust half logs under too, so one filter shows both. */
internal const val LOG_TAG = "irohbrowser"

/**
 * Loads the native library, hands iroh an Android context, keeps the WebView
 * off the network, and tells the proxy when Android blocks the app's network.
 *
 * All four happen here rather than in the activity because all four are
 * process-wide and must happen before the first proxy starts: the context has
 * to be installed before iroh first reads the device's interfaces, the WebView
 * confined before it loads its first page, and a block can come whenever the
 * app is in the background, with or without an activity.
 */
class IrohBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NativeProxy.load()
        NativeProxy.installContext(this)
        Confinement.confineNetwork()
        NetworkBlockWatch(NativeProxy).register(this)
    }
}

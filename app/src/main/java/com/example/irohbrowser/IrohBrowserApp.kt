package com.example.irohbrowser

import android.app.Application

/**
 * Loads the native library and hands iroh an Android context.
 *
 * Both happen here rather than in the activity because both are process-wide and
 * must happen before the first proxy starts: the context has to be installed
 * before iroh builds its first DNS resolver.
 */
class IrohBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NativeProxy.load()
        NativeProxy.installContext(this)
    }
}

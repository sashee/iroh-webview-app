package com.example.irohbrowser.testing

import android.app.Application

/**
 * The Application Robolectric uses instead of [com.example.irohbrowser.IrohBrowserApp].
 *
 * It exists to *not* do the one thing the real one does: load the native
 * library. That library is an ELF object for a phone, and `System.loadLibrary`
 * on the build host fails with `UnsatisfiedLinkError` before any test runs.
 */
class TestApp : Application()

package com.example.irohbrowser

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.testing.FakeProxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/**
 * Android blocking the app's network, passed on to the proxy: only changes,
 * and never the first report, which is the state at registration.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkBlockWatchTest {

    private val proxy = FakeProxy()
    private val watch = NetworkBlockWatch(proxy)

    @Test
    fun `the first report is only remembered`() {
        watch.report(false)
        assertEquals(emptyList<String>(), proxy.calls)
    }

    @Test
    fun `a block and the network coming back are each passed on once`() {
        watch.report(false)
        watch.report(true)
        watch.report(true)
        watch.report(false)
        watch.report(false)
        assertEquals(listOf("blocked:true", "blocked:false"), proxy.calls)
    }

    @Test
    fun `starting blocked, the network coming back is a change`() {
        watch.report(true)
        watch.report(false)
        assertEquals(listOf("blocked:false"), proxy.calls)
    }

    @Test
    fun `registering listens to the default network's blocked status`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        watch.register(context)
        val callback = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.single()
        val network = ShadowNetwork.newInstance(1)
        callback.onBlockedStatusChanged(network, false)
        callback.onBlockedStatusChanged(network, true)
        assertEquals(listOf("blocked:true"), proxy.calls)
    }
}

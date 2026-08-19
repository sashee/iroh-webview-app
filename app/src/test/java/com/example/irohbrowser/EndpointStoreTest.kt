package com.example.irohbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Persistence. The model is tested in [EndpointsTest]; what matters here is that
 * the whole state survives a round trip through preferences, because a saved
 * list with a stale selection would open the wrong server.
 */
@RunWith(RobolectricTestRunner::class)
class EndpointStoreTest {

    private fun store(name: String = "endpoints-test"): EndpointStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return EndpointStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }

    @Test
    fun `nothing saved reads as empty`() {
        assertTrue(store("fresh").load().isEmpty)
    }

    @Test
    fun `saved state survives a reload`() {
        val store = store()
        val saved = Endpoints()
            .add(Endpoint("t1", "one"))
            .add(Endpoint("t2", "two"))
            .select(0)
        store.save(saved)

        assertEquals(saved, store.load())
    }

    @Test
    fun `a second store sees what the first wrote`() {
        // Two instances over the same preferences file, as the app has across a
        // restart.
        store("shared").save(Endpoints().add(Endpoint("t", "n")))
        assertEquals("t", store("shared").load().selected?.ticket)
    }

    @Test
    fun `update applies a change and persists it`() {
        val store = store("updating")
        store.save(Endpoints().add(Endpoint("t1", "one")))

        val result = store.update { it.add(Endpoint("t2", "two")) }

        assertEquals(2, result.all.size)
        assertEquals(result, store.load())
    }

    @Test
    fun `update can empty the list`() {
        val store = store("emptying")
        store.save(Endpoints().add(Endpoint("t1", "one")))

        store.update { it.remove(0) }

        assertTrue(store.load().isEmpty)
        assertNull(store.load().selected)
    }
}

package com.example.irohbrowser.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.irohbrowser.AppContainer
import com.example.irohbrowser.EndpointStore
import com.example.irohbrowser.Endpoints
import com.example.irohbrowser.ProxyBinding
import com.example.irohbrowser.ProxyController
import com.example.irohbrowser.ProxyError
import com.example.irohbrowser.ProxyResult
import com.example.irohbrowser.SiteData

/**
 * A [ProxyController] that records what it was asked to do and hands back a
 * port without binding anything.
 *
 * The real one loads a native library cross-compiled for a phone, which a
 * Robolectric test on the host cannot open. What the activity has to get right
 * is the *order* — stop, clear, start, load — and that is visible here.
 */
class FakeProxy : ProxyController {

    /** Every call, in order, as `"start:<ticket>"` or `"stop"`. */
    val calls = mutableListOf<String>()

    /** Tickets that should fail, and how. */
    val failures = mutableMapOf<String, ProxyError>()

    /** Ports handed out, so each start is distinguishable from the last. */
    private var nextPort = 40000

    var running: ProxyBinding? = null
        private set

    override fun start(ticket: String): ProxyResult {
        calls += "start:$ticket"
        failures[ticket]?.let {
            running = null
            return ProxyResult.Failed(it)
        }
        val binding = ProxyBinding(label = labelFor(ticket), port = nextPort++)
        running = binding
        return ProxyResult.Started(binding)
    }

    override fun stop() {
        calls += "stop"
        running = null
    }

    /**
     * Stands in for the Rust side's endpoint-id derivation: stable per ticket,
     * distinct between tickets, and a valid DNS label.
     */
    fun labelFor(ticket: String): String =
        ticket.filter { it.isLetterOrDigit() }.lowercase().takeLast(16).ifEmpty { "endpoint" }

    val startedTickets: List<String>
        get() = calls.filter { it.startsWith("start:") }.map { it.removePrefix("start:") }
}

/** A [SiteData] that records rather than touching the framework's stores. */
class FakeSiteData : SiteData {
    var flushes = 0
        private set

    /** Origins cleared, in order. */
    val cleared = mutableListOf<String>()

    val clears: Int
        get() = cleared.size

    override fun flush() {
        flushes++
    }

    override fun clear(origin: String) {
        cleared += origin
    }
}

/** Everything a test needs to drive the activity. */
class TestHarness(
    val proxy: FakeProxy = FakeProxy(),
    val siteData: FakeSiteData = FakeSiteData(),
) {
    private val context: Context = ApplicationProvider.getApplicationContext()

    val store: EndpointStore =
        EndpointStore(context.getSharedPreferences("endpoints-test", Context.MODE_PRIVATE))

    fun install() {
        store.save(Endpoints())
        AppContainer.install(AppContainer(store, proxy) { siteData })
    }

    /** Seed the saved endpoints before the activity starts. */
    fun seed(vararg tickets: String, selected: Int = tickets.size - 1) {
        var endpoints = Endpoints()
        tickets.forEach { endpoints = endpoints.add(com.example.irohbrowser.Endpoint(it, it)) }
        store.save(endpoints.select(selected))
    }
}

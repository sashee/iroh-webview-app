package com.example.irohbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the settings screen lists, from the saved state. Pure, so plain JUnit. */
class SettingsTest {

    private val alpha = EndpointIdentity("aaaa000000000000", 21000)
    private val beta = EndpointIdentity("bbbb000000000000", 22000)

    private fun passkey(id: String, identity: EndpointIdentity, user: String = "alice", display: String = "") =
        StoredPasskey(id, Origins.host(identity.label), "h-$id", user, display, 1_700_000_000_000)

    private fun model(
        endpoints: Endpoints,
        identities: List<EndpointIdentity?> = listOf(alpha, beta),
        running: ProxyBinding? = null,
        passkeys: List<StoredPasskey> = emptyList(),
        storage: (StoredPasskey) -> KeyStorage? = { KeyStorage.StrongBox },
    ) = Settings.model(endpoints, identities, running, passkeys, storage)

    private val both = Endpoints().add(Endpoint("ticket-a")).add(Endpoint("ticket-b", "rpi5")).select(1)

    @Test
    fun `every endpoint is listed in order, by name or else by label`() {
        val rows = model(both).endpoints
        assertEquals(listOf(0, 1), rows.map { it.index })
        assertEquals(listOf("aaaa000000000000", "rpi5"), rows.map { it.name })
    }

    @Test
    fun `the open endpoint's origin comes from the running proxy`() {
        // The proxy may have fallen back from the preferred port; the page is
        // on the port it actually bound.
        val rows = model(both, running = ProxyBinding(beta.label, 39999)).endpoints
        assertEquals("http://bbbb000000000000.localhost:39999", rows[1].origin)
        assertTrue(rows[1].open)
    }

    @Test
    fun `the others' origins come from their preferred ports`() {
        val rows = model(both, running = ProxyBinding(beta.label, 39999)).endpoints
        assertEquals("http://aaaa000000000000.localhost:21000", rows[0].origin)
        assertFalse(rows[0].open)
    }

    @Test
    fun `a selected endpoint that is not running is not open`() {
        val rows = model(both, running = null).endpoints
        assertFalse(rows[1].open)
        assertEquals("http://bbbb000000000000.localhost:22000", rows[1].origin)
    }

    @Test
    fun `passkeys are listed under the endpoint whose host they belong to`() {
        val rows = model(both, passkeys = listOf(passkey("p1", alpha), passkey("p2", beta), passkey("p3", alpha))).endpoints
        assertEquals(listOf("p1", "p3"), rows[0].passkeys.map { it.credentialId })
        assertEquals(listOf("p2"), rows[1].passkeys.map { it.credentialId })
    }

    @Test
    fun `a passkey whose endpoint is gone is listed on its own`() {
        val gone = EndpointIdentity("cccc000000000000", 23000)
        val model = model(both, passkeys = listOf(passkey("p1", alpha), passkey("p9", gone)))
        assertEquals(listOf("p9"), model.orphans.map { it.credentialId })
        assertEquals("cccc000000000000.localhost", model.orphans.single().rpId)
    }

    @Test
    fun `a ticket the app cannot read has no origin and claims no passkeys`() {
        val model = model(both, identities = listOf(null, beta), passkeys = listOf(passkey("p1", alpha)))
        assertNull(model.endpoints[0].origin)
        assertEquals("ticket-a", model.endpoints[0].name)
        assertEquals(listOf("p1"), model.orphans.map { it.credentialId })
    }

    @Test
    fun `one endpoint saved twice lists its passkeys under both, and they are not orphans`() {
        // A bare id and a ticket with relay urls name the same endpoint.
        val twice = Endpoints().add(Endpoint("ticket-a")).add(Endpoint("ticket-a-with-relays"))
        val model = model(twice, identities = listOf(alpha, alpha), passkeys = listOf(passkey("p1", alpha)))
        assertEquals(listOf(listOf("p1"), listOf("p1")), model.endpoints.map { row -> row.passkeys.map { it.credentialId } })
        assertTrue(model.orphans.isEmpty())
    }

    @Test
    fun `a passkey shows the display name, else the user name, and where its key lives`() {
        val storage = mapOf("p1" to KeyStorage.StrongBox, "p2" to null)
        val rows = model(
            both,
            passkeys = listOf(passkey("p1", alpha, user = "alice", display = "Alice A."), passkey("p2", alpha, user = "bob")),
            storage = { storage[it.credentialId] },
        ).endpoints[0].passkeys
        assertEquals(listOf("Alice A.", "bob"), rows.map { it.account })
        assertEquals(listOf(KeyStorage.StrongBox, null), rows.map { it.storage })
    }

    @Test
    fun `a passkey with PRF is shown as such`() {
        val rows = model(both, passkeys = listOf(passkey("p1", alpha).copy(prf = true), passkey("p2", alpha))).endpoints[0].passkeys
        assertEquals(listOf(true, false), rows.map { it.prf })
    }
}

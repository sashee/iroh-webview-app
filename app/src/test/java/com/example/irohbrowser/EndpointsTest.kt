package com.example.irohbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The endpoint list model. Pure, so these are the cheapest tests in the suite
 * and the ones that pin down the behaviour that matters most: which endpoint is
 * selected after each edit. Get that wrong and the app shows the wrong server.
 *
 * Robolectric only for `org.json`, which is a stub in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class EndpointsTest {

    private fun endpoint(n: Int) = Endpoint("ticket$n", "name$n")

    @Test
    fun `a fresh list is empty and has nothing selected`() {
        val endpoints = Endpoints()
        assertTrue(endpoints.isEmpty)
        assertNull(endpoints.selected)
        assertEquals(-1, endpoints.selectedIndex)
    }

    @Test
    fun `adding selects what was added`() {
        val endpoints = Endpoints().add(endpoint(1)).add(endpoint(2))
        assertEquals(listOf(endpoint(1), endpoint(2)), endpoints.all)
        assertEquals(endpoint(2), endpoints.selected)
    }

    @Test
    fun `adding a ticket that is already saved selects it instead of duplicating`() {
        // Two rows for one peer would share a cookie jar while looking like
        // separate origins.
        val endpoints = Endpoints()
            .add(endpoint(1))
            .add(endpoint(2))
            .add(Endpoint("ticket1", "a different name"))

        assertEquals(2, endpoints.all.size)
        assertEquals(0, endpoints.selectedIndex)
        // The saved name wins: the user named it, and re-pasting a ticket is
        // not a rename.
        assertEquals("name1", endpoints.selected?.name)
    }

    @Test
    fun `selecting moves the selection`() {
        val endpoints = Endpoints().add(endpoint(1)).add(endpoint(2)).select(0)
        assertEquals(endpoint(1), endpoints.selected)
    }

    @Test
    fun `selecting out of range changes nothing`() {
        val endpoints = Endpoints().add(endpoint(1))
        assertEquals(endpoints, endpoints.select(5))
        assertEquals(endpoints, endpoints.select(-1))
    }

    @Test
    fun `removing an earlier endpoint keeps the same one selected`() {
        val endpoints = Endpoints()
            .add(endpoint(1)).add(endpoint(2)).add(endpoint(3))
            .select(2)
            .remove(0)

        assertEquals(endpoint(3), endpoints.selected)
        assertEquals(1, endpoints.selectedIndex)
    }

    @Test
    fun `removing a later endpoint keeps the same one selected`() {
        val endpoints = Endpoints()
            .add(endpoint(1)).add(endpoint(2)).add(endpoint(3))
            .select(0)
            .remove(2)

        assertEquals(endpoint(1), endpoints.selected)
        assertEquals(0, endpoints.selectedIndex)
    }

    @Test
    fun `removing the selected endpoint selects the one that took its place`() {
        val endpoints = Endpoints()
            .add(endpoint(1)).add(endpoint(2)).add(endpoint(3))
            .select(1)
            .remove(1)

        assertEquals(endpoint(3), endpoints.selected)
    }

    @Test
    fun `removing the last endpoint selects the new last one`() {
        val endpoints = Endpoints()
            .add(endpoint(1)).add(endpoint(2))
            .select(1)
            .remove(1)

        assertEquals(endpoint(1), endpoints.selected)
        assertEquals(0, endpoints.selectedIndex)
    }

    @Test
    fun `removing the only endpoint leaves nothing selected`() {
        val endpoints = Endpoints().add(endpoint(1)).remove(0)
        assertTrue(endpoints.isEmpty)
        assertNull(endpoints.selected)
        assertEquals(-1, endpoints.selectedIndex)
    }

    @Test
    fun `removing out of range changes nothing`() {
        val endpoints = Endpoints().add(endpoint(1))
        assertEquals(endpoints, endpoints.remove(9))
        assertEquals(endpoints, endpoints.remove(-1))
    }

    @Test
    fun `renaming changes only the name`() {
        val endpoints = Endpoints().add(endpoint(1)).rename(0, "rpi5")
        assertEquals("rpi5", endpoints.all[0].name)
        assertEquals("ticket1", endpoints.all[0].ticket)
    }

    @Test
    fun `renaming out of range changes nothing`() {
        val endpoints = Endpoints().add(endpoint(1))
        assertEquals(endpoints, endpoints.rename(4, "nope"))
    }

    @Test
    fun `the state round-trips through json`() {
        val endpoints = Endpoints()
            .add(endpoint(1)).add(endpoint(2)).add(endpoint(3))
            .select(1)

        assertEquals(endpoints, Endpoints.fromJson(endpoints.toJson()))
    }

    @Test
    fun `an empty state round-trips`() {
        assertEquals(Endpoints(), Endpoints.fromJson(Endpoints().toJson()))
    }

    @Test
    fun `nothing saved reads as empty`() {
        assertEquals(Endpoints(), Endpoints.fromJson(null))
        assertEquals(Endpoints(), Endpoints.fromJson(""))
        assertEquals(Endpoints(), Endpoints.fromJson("   "))
    }

    @Test
    fun `unreadable json reads as empty rather than throwing`() {
        // A corrupt preference should cost the endpoint list, not every launch.
        assertEquals(Endpoints(), Endpoints.fromJson("{not json"))
        assertEquals(Endpoints(), Endpoints.fromJson("[]"))
    }

    @Test
    fun `entries without a ticket are dropped`() {
        val json = """{"endpoints":[{"name":"nameless"},{"ticket":"t","name":"n"}],"selected":0}"""
        val endpoints = Endpoints.fromJson(json)
        assertEquals(listOf(Endpoint("t", "n")), endpoints.all)
    }

    @Test
    fun `a selection pointing past the end reads as no selection`() {
        val json = """{"endpoints":[{"ticket":"t","name":"n"}],"selected":7}"""
        assertNull(Endpoints.fromJson(json).selected)
    }

    @Test
    fun `an entry without a name has none`() {
        val json = """{"endpoints":[{"ticket":"abcdefghijklmnop"}],"selected":0}"""
        assertNull(Endpoints.fromJson(json).all[0].name)
    }

    @Test
    fun `the old default name reads as no name`() {
        // Earlier versions saved the ticket's first twelve characters when the
        // user gave no name, and every ticket starts with "endpoint".
        val json = """{"endpoints":[{"ticket":"endpointaankziuk4","name":"endpointaank"}],"selected":0}"""
        assertNull(Endpoints.fromJson(json).all[0].name)
    }

    @Test
    fun `a name that is the whole of a short ticket is a real name`() {
        // The old default was the ticket cut short; nothing was ever cut from
        // a ticket of twelve characters or fewer.
        val json = """{"endpoints":[{"ticket":"ticket-alpha","name":"ticket-alpha"}],"selected":0}"""
        assertEquals("ticket-alpha", Endpoints.fromJson(json).all[0].name)
    }

    @Test
    fun `an unnamed endpoint is not saved with a name`() {
        val saved = Endpoints().add(Endpoint("t")).toJson()
        assertEquals(Endpoints().add(Endpoint("t")), Endpoints.fromJson(saved))
        assertFalse(saved.contains("\"name\""))
    }

    @Test
    fun `renaming to blank goes back to no name`() {
        val endpoints = Endpoints().add(endpoint(1)).rename(0, "   ")
        assertNull(endpoints.all[0].name)
    }

    @Test
    fun `a name is trimmed`() {
        assertEquals("rpi5", Endpoints().add(endpoint(1)).rename(0, "  rpi5 ").all[0].name)
    }

    @Test
    fun `the display name is the user's, else the label, else the ticket`() {
        assertEquals("rpi5", Endpoint("t", "rpi5").displayName("1aaca28ae50b8d96"))
        assertEquals("1aaca28ae50b8d96", Endpoint("t").displayName("1aaca28ae50b8d96"))
        assertEquals("t", Endpoint("t").displayName(null))
    }
}

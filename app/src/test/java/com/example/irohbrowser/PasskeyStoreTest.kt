package com.example.irohbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PasskeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun store() = PasskeyStore(context.getSharedPreferences("passkey-store-test", Context.MODE_PRIVATE))

    private val alice = StoredPasskey("aWQ", "alpha.localhost", "aGFuZGxl", "alice", "Alice", 1234)

    @Test
    fun `an empty store has no passkeys`() {
        assertEquals(emptyList<StoredPasskey>(), store().load())
    }

    @Test
    fun `a saved passkey reads back the same`() {
        store().update { it + alice }
        assertEquals(listOf(alice), store().load())
    }

    @Test
    fun `the key alias is derived from the credential id`() {
        assertEquals("passkey:aWQ", alice.alias)
    }

    @Test
    fun `a corrupt store reads as empty rather than failing`() {
        assertEquals(emptyList<StoredPasskey>(), StoredPasskey.listFromJson("{not json"))
        assertEquals(emptyList<StoredPasskey>(), StoredPasskey.listFromJson("""{"a": 1}"""))
    }

    @Test
    fun `unreadable entries are skipped and the rest kept`() {
        val text = """[{"rpId": "x"}, ${StoredPasskey.listToJson(listOf(alice)).removeSurrounding("[", "]")}, 5]"""
        assertEquals(listOf(alice), StoredPasskey.listFromJson(text))
    }
}

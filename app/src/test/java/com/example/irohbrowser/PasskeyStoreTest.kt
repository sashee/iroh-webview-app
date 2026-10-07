package com.example.irohbrowser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `whether a passkey has PRF is kept, and old entries have none`() {
        store().update { it + alice.copy(prf = true) }
        assertTrue(store().load().single().prf)
        val old = """[{"credentialId": "aWQ", "rpId": "r", "userHandle": "h", "userName": "u", "userDisplayName": "U", "created": 1}]"""
        assertFalse(StoredPasskey.listFromJson(old).single().prf)
    }

    @Test
    fun `forgetting a passkey deletes both of its keys`() {
        val vault = com.example.irohbrowser.testing.FakeKeyVault()
        vault.create(alice.alias)
        vault.createPrf(alice.prfAlias)
        store().update { it + alice.copy(prf = true) }

        store().forget(alice.credentialId, vault)

        assertTrue(store().load().isEmpty())
        assertTrue(vault.keys.isEmpty())
        assertTrue(vault.prfKeys.isEmpty())
    }
}

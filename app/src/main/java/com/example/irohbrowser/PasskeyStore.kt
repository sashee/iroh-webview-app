package com.example.irohbrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app remembers about a passkey. The key itself is in the Android
 * Keystore under [alias] and never leaves it; this is the part a sign-in needs
 * before the key is touched -- which site it is for, and whose it is.
 */
data class StoredPasskey(
    /** base64url, as servers and the page see it. */
    val credentialId: String,
    val rpId: String,
    /** base64url. Returned on sign-in so a usernameless login knows whose passkey it is. */
    val userHandle: String,
    val userName: String,
    val userDisplayName: String,
    val created: Long,
    /**
     * Whether it has a PRF key beside its signing key. Decided at
     * registration, by whether the site asked for PRF, and fixed after: a
     * Keystore key's rules cannot be changed once it exists.
     */
    val prf: Boolean = false,
) {
    val alias: String get() = aliasFor(credentialId)
    val prfAlias: String get() = prfAliasFor(credentialId)

    companion object {
        fun aliasFor(credentialId: String): String = "passkey:$credentialId"
        fun prfAliasFor(credentialId: String): String = "prf:$credentialId"

        fun listToJson(passkeys: List<StoredPasskey>): String =
            JSONArray(
                passkeys.map {
                    JSONObject()
                        .put("credentialId", it.credentialId)
                        .put("rpId", it.rpId)
                        .put("userHandle", it.userHandle)
                        .put("userName", it.userName)
                        .put("userDisplayName", it.userDisplayName)
                        .put("created", it.created)
                        .put("prf", it.prf)
                },
            ).toString()

        /**
         * Parse the saved form, skipping entries that cannot be read and
         * falling back to empty on anything worse. A corrupt preference costs
         * passkeys, which the server can re-register, rather than the app.
         */
        fun listFromJson(text: String?): List<StoredPasskey> {
            if (text.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                StoredPasskey(
                    credentialId = entry.text("credentialId") ?: return@mapNotNull null,
                    rpId = entry.text("rpId") ?: return@mapNotNull null,
                    userHandle = entry.text("userHandle") ?: return@mapNotNull null,
                    userName = entry.text("userName").orEmpty(),
                    userDisplayName = entry.text("userDisplayName").orEmpty(),
                    created = entry.optLong("created"),
                    prf = entry.optBoolean("prf"),
                )
            }
        }
    }
}

/** Persistence for [StoredPasskey]s. Reads and writes; the rules are in [PasskeyRequests]. */
class PasskeyStore(private val prefs: SharedPreferences) {

    fun load(): List<StoredPasskey> = StoredPasskey.listFromJson(prefs.getString(KEY, null))

    /** Apply [change] to the saved list and return the result. */
    fun update(change: (List<StoredPasskey>) -> List<StoredPasskey>): List<StoredPasskey> =
        change(load()).also { prefs.edit().putString(KEY, StoredPasskey.listToJson(it)).apply() }

    /**
     * Forget a passkey: the record here, and its keys in [vault]. The server's
     * copy of the public key stays, and can never be used again.
     */
    fun forget(credentialId: String, vault: KeyVault) {
        update { stored -> stored.filterNot { it.credentialId == credentialId } }
        vault.delete(StoredPasskey.aliasFor(credentialId))
        vault.delete(StoredPasskey.prfAliasFor(credentialId))
    }

    companion object {
        private const val KEY = "passkeys"
        private const val FILE = "passkeys"

        fun from(context: Context): PasskeyStore =
            PasskeyStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

package com.example.irohbrowser

/** One passkey as the settings screen lists it. */
data class PasskeyRow(
    val credentialId: String,
    val account: String,
    val rpId: String,
    val created: Long,
    /** Where its key lives; null when the key is gone from the Keystore. */
    val storage: KeyStorage?,
)

/** One endpoint as the settings screen lists it. */
data class EndpointRow(
    /** Its position in [Endpoints.all], which is what every action names. */
    val index: Int,
    val name: String,
    /** Its origin, or null for a ticket the Rust side cannot read. */
    val origin: String?,
    /** Whether it is the one on screen. */
    val open: Boolean,
    val passkeys: List<PasskeyRow>,
)

/** Everything the settings screen shows. */
data class SettingsModel(
    val endpoints: List<EndpointRow>,
    /**
     * Passkeys whose endpoint is no longer saved. Removing an endpoint keeps
     * them, since re-adding it makes them usable again; here is where they can
     * be seen, and deleted for good.
     */
    val orphans: List<PasskeyRow>,
)

/**
 * What the settings screen shows, from the state that already exists. Pure: it
 * is handed the identities and the key storage rather than asking for them.
 */
object Settings {

    /**
     * @param identities one per endpoint in [endpoints], as `ProxyController.identify` reads them
     * @param running the proxy currently running, which is the authority on the open endpoint's port
     * @param storage where a passkey's key lives, as the vault reports it
     */
    fun model(
        endpoints: Endpoints,
        identities: List<EndpointIdentity?>,
        running: ProxyBinding?,
        passkeys: List<StoredPasskey>,
        storage: (StoredPasskey) -> KeyStorage?,
    ): SettingsModel {
        val rows = endpoints.all.mapIndexed { index, endpoint ->
            val binding = running?.takeIf { index == endpoints.selectedIndex }
            val identity = identities.getOrNull(index)
            val label = binding?.label ?: identity?.label
            EndpointRow(
                index = index,
                name = endpoint.displayName(label),
                origin = binding?.let { Origins.origin(it.label, it.port) }
                    ?: identity?.let { Origins.origin(it.label, it.preferredPort) },
                open = binding != null,
                passkeys = passkeys
                    .filter { label != null && it.rpId == Origins.host(label) }
                    .map { row(it, storage) },
            )
        }
        val claimed = rows.flatMap { it.passkeys }.map { it.credentialId }.toSet()
        return SettingsModel(
            endpoints = rows,
            orphans = passkeys.filter { it.credentialId !in claimed }.map { row(it, storage) },
        )
    }

    private fun row(passkey: StoredPasskey, storage: (StoredPasskey) -> KeyStorage?) = PasskeyRow(
        credentialId = passkey.credentialId,
        account = passkey.userDisplayName.ifBlank { passkey.userName },
        rpId = passkey.rpId,
        created = passkey.created,
        storage = storage(passkey),
    )
}

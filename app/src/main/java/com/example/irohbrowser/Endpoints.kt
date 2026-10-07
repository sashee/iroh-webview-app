package com.example.irohbrowser

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved endpoint: what to dial, and what the user called it.
 *
 * The ticket is stored verbatim rather than parsed. Only the Rust side knows how
 * to read one, and keeping the original text means a ticket carrying relay urls
 * keeps them — which is what stops a dial from depending on DNS discovery.
 *
 * [name] is null until the user gives one. The fallback is computed rather than
 * stored, so it can be the endpoint's label, which only the Rust side can read
 * out of a ticket.
 */
data class Endpoint(val ticket: String, val name: String? = null) {

    /**
     * What to call it: the user's name, else the endpoint's label -- the same
     * hex the page's origin starts with, so the two can be matched by eye.
     * Every ticket begins with "endpoint", which is why the ticket itself makes
     * a poor name.
     */
    fun displayName(label: String?): String = name ?: label ?: ticket
}

/**
 * The whole saved state: the endpoints and which one is showing.
 *
 * Immutable, and every operation returns a new value. The list is the source of
 * truth; the selection is an index into it rather than a copy of an entry, so
 * the two cannot disagree about which endpoint is which.
 */
data class Endpoints(
    val all: List<Endpoint> = emptyList(),
    val selectedIndex: Int = -1,
) {
    val selected: Endpoint?
        get() = all.getOrNull(selectedIndex)

    val isEmpty: Boolean
        get() = all.isEmpty()

    /**
     * Add an endpoint and select it.
     *
     * Adding one that is already saved selects the existing entry instead of
     * duplicating it: two rows dialing the same peer would share cookies while
     * looking like separate origins, which is exactly the confusion the
     * per-endpoint origins exist to prevent.
     */
    fun add(endpoint: Endpoint): Endpoints {
        val existing = all.indexOfFirst { it.ticket == endpoint.ticket }
        if (existing >= 0) return copy(selectedIndex = existing)
        return Endpoints(all + endpoint, all.size)
    }

    /**
     * Remove the endpoint at [index], keeping the selection pointed at the same
     * endpoint where it can be.
     */
    fun remove(index: Int): Endpoints {
        if (index !in all.indices) return this
        val remaining = all.toMutableList().apply { removeAt(index) }
        val selection = when {
            remaining.isEmpty() -> -1
            // Removing something before the selection shifts it down; removing
            // the selection itself falls back to the entry that took its place.
            selectedIndex > index -> selectedIndex - 1
            selectedIndex == index -> index.coerceAtMost(remaining.size - 1)
            else -> selectedIndex
        }
        return Endpoints(remaining, selection)
    }

    fun select(index: Int): Endpoints =
        if (index in all.indices) copy(selectedIndex = index) else this

    /** Name the endpoint at [index]; a blank name goes back to the default. */
    fun rename(index: Int, name: String): Endpoints =
        if (index in all.indices) {
            val named = name.trim().ifEmpty { null }
            copy(all = all.mapIndexed { at, e -> if (at == index) e.copy(name = named) else e })
        } else {
            this
        }

    companion object {
        /**
         * Parse the saved form, falling back to empty on anything unreadable.
         *
         * A corrupt preference should cost the endpoint list, not every launch
         * of the app.
         */
        fun fromJson(text: String?): Endpoints {
            if (text.isNullOrBlank()) return Endpoints()
            return try {
                val root = JSONObject(text)
                val array = root.optJSONArray("endpoints") ?: JSONArray()
                val all = (0 until array.length()).mapNotNull { at ->
                    val entry = array.optJSONObject(at) ?: return@mapNotNull null
                    val ticket = entry.optString("ticket").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Endpoint(ticket, userName(ticket, entry.optString("name")))
                }
                val selected = root.optInt("selected", -1)
                Endpoints(all, if (selected in all.indices) selected else -1)
            } catch (_: Exception) {
                Endpoints()
            }
        }

        /**
         * The saved name, if the user chose it. Earlier versions saved the
         * ticket cut to twelve characters when the user gave none --
         * "endpointaank", "endpointadbi", impossible to tell apart -- so a
         * name that is exactly that cut is read as no name at all.
         */
        private fun userName(ticket: String, saved: String): String? {
            val name = saved.trim()
            val oldDefault = ticket.length > LEGACY_NAME_LENGTH && name == ticket.take(LEGACY_NAME_LENGTH)
            return name.takeIf { it.isNotEmpty() && !oldDefault }
        }

        private const val LEGACY_NAME_LENGTH = 12
    }

    fun toJson(): String {
        val array = JSONArray()
        all.forEach { endpoint ->
            array.put(
                JSONObject()
                    .put("ticket", endpoint.ticket)
                    .apply { endpoint.name?.let { put("name", it) } },
            )
        }
        return JSONObject()
            .put("endpoints", array)
            .put("selected", selectedIndex)
            .toString()
    }
}

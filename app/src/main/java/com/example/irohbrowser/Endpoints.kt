package com.example.irohbrowser

import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved endpoint: what to dial, and what to call it.
 *
 * The ticket is stored verbatim rather than parsed. Only the Rust side knows how
 * to read one, and keeping the original text means a ticket carrying relay urls
 * keeps them — which is what stops a dial from depending on DNS discovery.
 */
data class Endpoint(val ticket: String, val name: String)

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

    fun rename(index: Int, name: String): Endpoints =
        if (index in all.indices) {
            copy(all = all.mapIndexed { at, e -> if (at == index) e.copy(name = name) else e })
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
                    Endpoint(ticket, entry.optString("name").ifBlank { defaultName(ticket) })
                }
                val selected = root.optInt("selected", -1)
                Endpoints(all, if (selected in all.indices) selected else -1)
            } catch (_: Exception) {
                Endpoints()
            }
        }

        /** A readable stand-in when the user did not name the endpoint. */
        fun defaultName(ticket: String): String = ticket.take(12)
    }

    fun toJson(): String {
        val array = JSONArray()
        all.forEach { endpoint ->
            array.put(
                JSONObject()
                    .put("ticket", endpoint.ticket)
                    .put("name", endpoint.name),
            )
        }
        return JSONObject()
            .put("endpoints", array)
            .put("selected", selectedIndex)
            .toString()
    }
}

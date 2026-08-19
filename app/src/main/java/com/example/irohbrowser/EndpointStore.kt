package com.example.irohbrowser

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for [Endpoints].
 *
 * The whole state is written as one value, so a save can never leave the list
 * and the selection describing different things. All the interesting behaviour
 * lives in [Endpoints]; this only reads and writes.
 */
class EndpointStore(private val prefs: SharedPreferences) {

    fun load(): Endpoints = Endpoints.fromJson(prefs.getString(KEY, null))

    fun save(endpoints: Endpoints) {
        prefs.edit().putString(KEY, endpoints.toJson()).apply()
    }

    /** Apply [change] to the saved state and return the result. */
    fun update(change: (Endpoints) -> Endpoints): Endpoints =
        change(load()).also(::save)

    companion object {
        private const val KEY = "endpoints"
        private const val FILE = "endpoints"

        fun from(context: Context): EndpointStore =
            EndpointStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

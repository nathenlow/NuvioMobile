package com.nuvio.app.features.player.audiosync

import android.content.SharedPreferences

internal class SharedPreferencesMappingStorage(private val preferences: SharedPreferences) : MappingStorage {
    override fun get(key: String): String? = runCatching { preferences.getString(key, null) }.getOrNull()

    override fun put(key: String, value: String) {
        runCatching { preferences.edit().putString(key, value).apply() }
    }
}

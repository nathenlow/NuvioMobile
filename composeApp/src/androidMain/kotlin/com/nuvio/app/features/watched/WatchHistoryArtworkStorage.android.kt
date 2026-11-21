package com.nuvio.app.features.watched

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object WatchHistoryArtworkStorage {
    private const val preferencesName = "nuvio_watch_history_artwork"
    private const val key = "recent_artwork"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(profileId: Int): String? =
        preferences?.getString(ProfileScopedKey.of(key, profileId), null)

    actual fun save(profileId: Int, payload: String) {
        preferences?.edit()?.putString(ProfileScopedKey.of(key, profileId), payload)?.apply()
    }

    actual fun remove(profileId: Int) {
        preferences?.edit()?.remove(ProfileScopedKey.of(key, profileId))?.apply()
    }
}

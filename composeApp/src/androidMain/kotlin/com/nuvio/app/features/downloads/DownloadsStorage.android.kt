package com.nuvio.app.features.downloads

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DownloadsStorage {
    private const val preferencesName = "nuvio_downloads"
    private const val payloadKey = "downloads_payload"
    private const val maxConcurrentKey = "max_concurrent_downloads"
    private const val fullEpisodeListKey = "save_full_episode_list"
    private const val storageVolumeKey = "download_storage_volume"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)

    actual fun savePayload(payload: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(payloadKey), payload)
            ?.apply()
    }

    // A device-wide setting, so it is not scoped to the active profile.
    actual fun loadMaxConcurrentDownloads(): Int? =
        preferences?.takeIf { it.contains(maxConcurrentKey) }?.getInt(maxConcurrentKey, 0)

    actual fun loadSaveFullEpisodeList(): Boolean? =
        preferences?.takeIf { it.contains(fullEpisodeListKey) }?.getBoolean(fullEpisodeListKey, true)

    actual fun saveSaveFullEpisodeList(value: Boolean) {
        preferences?.edit()?.putBoolean(fullEpisodeListKey, value)?.apply()
    }

    // A device-wide setting, so it is not scoped to the active profile.
    actual fun loadStorageVolumeId(): String? = preferences?.getString(storageVolumeKey, null)

    actual fun saveStorageVolumeId(value: String) {
        preferences?.edit()?.putString(storageVolumeKey, value)?.apply()
    }

    actual fun saveMaxConcurrentDownloads(value: Int) {
        preferences?.edit()?.putInt(maxConcurrentKey, value)?.apply()
    }
}

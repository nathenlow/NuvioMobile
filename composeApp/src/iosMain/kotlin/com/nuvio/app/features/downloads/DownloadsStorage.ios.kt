package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val preferredResolutionKey = "preferred_download_resolution"
    private const val maxConcurrentKey = "max_concurrent_downloads"
    private const val fullEpisodeListKey = "save_full_episode_list"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    // A device-wide setting, so it is not scoped to the active profile.
    actual fun loadMaxConcurrentDownloads(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(maxConcurrentKey) != null) {
            defaults.integerForKey(maxConcurrentKey).toInt()
        } else {
            null
        }
    }

    actual fun loadSaveFullEpisodeList(): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(fullEpisodeListKey) != null) defaults.boolForKey(fullEpisodeListKey) else null
    }

    actual fun saveSaveFullEpisodeList(value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = fullEpisodeListKey)
    }

    actual fun loadPreferredResolution(): Int? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(preferredResolutionKey) != null) {
            defaults.integerForKey(preferredResolutionKey).toInt()
        } else {
            null
        }
    }

    actual fun savePreferredResolution(value: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(value.toLong(), forKey = preferredResolutionKey)
    }

    actual fun loadStorageVolumeId(): String? = null

    actual fun saveStorageVolumeId(value: String) = Unit

    actual fun saveMaxConcurrentDownloads(value: Int) {
        NSUserDefaults.standardUserDefaults.setInteger(value.toLong(), forKey = maxConcurrentKey)
    }
}

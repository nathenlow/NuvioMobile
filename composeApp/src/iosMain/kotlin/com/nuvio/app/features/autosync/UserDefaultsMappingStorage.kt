package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.audiosync.MappingStorage
import platform.Foundation.NSUserDefaults

/** Remembered audio sync mappings, in user defaults. The keys already hold hashes, never URLs. */
internal object UserDefaultsMappingStorage : MappingStorage {
    private const val PREFIX = "audio_sync_mapping_"

    override fun get(key: String): String? = NSUserDefaults.standardUserDefaults.stringForKey(PREFIX + key)

    override fun put(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = PREFIX + key)
    }
}

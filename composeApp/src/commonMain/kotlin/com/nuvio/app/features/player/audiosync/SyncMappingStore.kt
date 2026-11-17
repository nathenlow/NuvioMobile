package com.nuvio.app.features.player.audiosync

/** Where remembered mappings live; a seam so the store can be tested without Android. */
internal interface MappingStorage {
    fun get(key: String): String?

    fun put(key: String, value: String)
}

/** Text form of a [SubtitleSyncModel]: "fromMs,scale,shiftMs" per segment, separated by "|". */
internal object SyncMappingCodec {
    fun encode(model: SubtitleSyncModel): String =
        model.segments.joinToString("|") { "${it.fromMediaMs},${it.scale},${it.shiftMs}" }

    /** Null when [stored] is not a mapping this version wrote. */
    fun decode(stored: String): SubtitleSyncModel? {
        val segments = stored.split('|').map { entry ->
            val parts = entry.split(',')
            if (parts.size != 3) return null
            SubtitleSyncSegment(
                fromMediaMs = parts[0].toLongOrNull() ?: return null,
                scale = parts[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null,
                shiftMs = parts[2].toDoubleOrNull() ?: return null,
            )
        }
        return SubtitleSyncModel(segments)
    }
}

/**
 * The mapping found the last time a subtitle played on one stream, so replaying it starts in sync.
 * Keys hold hashes, never the stream or subtitle URL (they can carry account tokens).
 */
internal class SyncMappingStore(
    private val storage: MappingStorage,
    private val sourceKey: String,
) {
    fun load(subtitleKey: String): SubtitleSyncModel? =
        storage.get(keyFor(subtitleKey))?.let(SyncMappingCodec::decode)

    fun save(subtitleKey: String, model: SubtitleSyncModel) {
        storage.put(keyFor(subtitleKey), SyncMappingCodec.encode(model))
    }

    /** Versioned: syncs saved by earlier builds could hold a 0.1% stretch found on too little audio. */
    private fun keyFor(subtitleKey: String): String = "v2:${sourceKey.hashCode()}:${subtitleKey.hashCode()}"
}

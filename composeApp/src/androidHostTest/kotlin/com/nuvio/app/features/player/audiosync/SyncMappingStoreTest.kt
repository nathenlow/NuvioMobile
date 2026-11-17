package com.nuvio.app.features.player.audiosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMappingStoreTest {
    private val storage = MemoryMappingStorage()
    private val model = SubtitleSyncModel(
        listOf(
            SubtitleSyncSegment(0L, 25.0 / 23.976, -1_234.5),
            SubtitleSyncSegment(720_000L, 25.0 / 23.976, 5_678.25),
        ),
    )

    @Test
    fun aSavedMappingComesBackExactly() {
        val store = SyncMappingStore(storage, "stream")
        store.save("subtitle", model)
        val loaded = assertNotNull(store.load("subtitle"))
        assertEquals(model.segments, loaded.segments)
    }

    @Test
    fun nothingIsFoundForAnotherSubtitleOrAnotherStream() {
        SyncMappingStore(storage, "stream").save("subtitle", model)
        assertNull(SyncMappingStore(storage, "stream").load("other subtitle"))
        assertNull(SyncMappingStore(storage, "other stream").load("subtitle"))
    }

    @Test
    fun keysNeverHoldTheUrls() {
        SyncMappingStore(storage, "https://cdn.example/stream?token=secret").save("https://subs.example/a.srt", model)
        assertTrue(storage.values.keys.none { "http" in it || "secret" in it }, "keys ${storage.values.keys}")
    }

    @Test
    fun aMalformedStoredValueIsIgnored() {
        listOf("", "a,b,c", "1,2", "0,1.0,5.0,9", "0,-1.0,5.0", "0,0.0,5.0", "0,1.0,x", "0,1.0,5.0|").forEach { stored ->
            assertNull(SyncMappingCodec.decode(stored), "decoded \"$stored\"")
        }
    }

    @Test
    fun aValueThatCannotBeReadDoesNotBreakLoading() {
        val store = SyncMappingStore(storage, "stream")
        store.save("subtitle", model)
        storage.values.replaceAll { _, _ -> "garbage" }
        assertNull(store.load("subtitle"))
    }
}

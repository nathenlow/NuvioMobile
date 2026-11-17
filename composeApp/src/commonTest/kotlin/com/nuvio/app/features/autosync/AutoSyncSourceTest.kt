package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncSourceTest {
    @Test
    fun streamsAndDownloadsAreSyncable() {
        assertTrue(isSyncableSource("https://host/film.mkv"))
        assertTrue(isSyncableSource("HTTP://host/film.mkv"))
        assertTrue(isSyncableSource("file:///data/user/0/app/files/film.mkv"))
        assertTrue(isLocalFileUrl("FILE:///x/y.mkv"))
        assertTrue(isLocalFileUrl("file:/data/user/0/app/files/film.mkv"))
        assertFalse(isRemoteUrl("file:///x/y.mkv"))
    }

    @Test
    fun otherSchemesAreNotSyncable() {
        assertFalse(isSyncableSource("content://media/external/video/1"))
        assertFalse(isSyncableSource("rtmp://host/live"))
        assertFalse(isSyncableSource(""))
    }

    @Test
    fun aLocalSubtitleFileIsReadWithoutARequest() = kotlinx.coroutines.test.runTest {
        val missing = AutoSyncSubtitleHttp.get("file:///definitely/not/here.srt", emptyMap(), 1024)
        assertEquals(404, missing.status)
    }
}

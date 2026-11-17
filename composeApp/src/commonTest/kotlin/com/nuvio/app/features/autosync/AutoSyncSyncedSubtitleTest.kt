package com.nuvio.app.features.autosync

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoSyncSyncedSubtitleTest {
    @AfterTest
    fun reset() = AutoSyncSyncedSubtitle.clear()

    @Test
    fun aSubtitleBeingLinedUpWithTheAudioIsMarkedSyncingNotSynced() {
        AutoSyncSyncedSubtitle.markSyncing("a")
        assertEquals("a", AutoSyncSyncedSubtitle.syncingUrl.value)
        assertNull(AutoSyncSyncedSubtitle.url.value)
    }

    @Test
    fun confirmingATimingEndsTheSyncingState() {
        AutoSyncSyncedSubtitle.markSyncing("a")
        AutoSyncSyncedSubtitle.mark("a")
        assertEquals("a", AutoSyncSyncedSubtitle.url.value)
        assertNull(AutoSyncSyncedSubtitle.syncingUrl.value)
    }

    @Test
    fun aWithdrawnEstimateGoesBackToSyncing() {
        AutoSyncSyncedSubtitle.mark("a")
        AutoSyncSyncedSubtitle.markSyncing("a")
        assertNull(AutoSyncSyncedSubtitle.url.value)
        assertEquals("a", AutoSyncSyncedSubtitle.syncingUrl.value)
    }

    @Test
    fun clearingOnlyTheSyncingStateKeepsAConfirmedMark() {
        AutoSyncSyncedSubtitle.mark("a")
        AutoSyncSyncedSubtitle.clearSyncing()
        assertEquals("a", AutoSyncSyncedSubtitle.url.value)

        AutoSyncSyncedSubtitle.markSyncing("b")
        AutoSyncSyncedSubtitle.clearSyncing()
        assertNull(AutoSyncSyncedSubtitle.syncingUrl.value)
    }

    @Test
    fun clearingResetsBothStates() {
        AutoSyncSyncedSubtitle.markSyncing("a")
        AutoSyncSyncedSubtitle.clear()
        assertNull(AutoSyncSyncedSubtitle.url.value)
        assertNull(AutoSyncSyncedSubtitle.syncingUrl.value)

        AutoSyncSyncedSubtitle.mark("a")
        AutoSyncSyncedSubtitle.clear()
        assertNull(AutoSyncSyncedSubtitle.url.value)
        assertNull(AutoSyncSyncedSubtitle.syncingUrl.value)
    }
}

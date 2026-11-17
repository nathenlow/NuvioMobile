package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncAudioFallbackTest {
    @Test
    fun theAudioTakesOverWheneverTheSubtitleLoadedAndIsStillShown() {
        val outcomes = listOf(
            null,
            AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
            AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
            AutoSyncAnalysisOutcome.NO_FIT,
            AutoSyncAnalysisOutcome.INCONCLUSIVE,
        )
        outcomes.forEach { outcome ->
            assertTrue(shouldSyncToAudio(outcome, sidecarStillShowsSubtitle = true), "outcome $outcome")
        }
    }

    @Test
    fun theAudioCannotSyncASubtitleThatFailedToLoad() {
        assertFalse(shouldSyncToAudio(AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE, sidecarStillShowsSubtitle = true))
    }

    @Test
    fun theAudioLeavesAloneASubtitleThatIsNoLongerShown() {
        AutoSyncAnalysisOutcome.entries.forEach { outcome ->
            assertFalse(shouldSyncToAudio(outcome, sidecarStillShowsSubtitle = false), "outcome $outcome")
        }
    }

    @Test
    fun theAudioFallbackSettingIsSavedAndLoadedAgain() {
        var stored: Boolean? = null
        val load = { stored }
        val save = { enabled: Boolean -> stored = enabled }

        AutoSyncPreferencesRepository.installAudioSyncFallbackPersistence(load, save)
        AutoSyncPreferencesRepository.ensureLoaded()
        assertTrue(AutoSyncPreferencesRepository.audioSyncFallbackSupported)
        assertTrue(AutoSyncPreferencesRepository.audioSyncFallbackEnabled.value, "on by default")

        AutoSyncPreferencesRepository.setAudioSyncFallbackEnabled(false)
        assertEquals(false, stored)
        assertFalse(AutoSyncPreferencesRepository.audioSyncFallbackEnabled.value)

        // A fresh start reads what was saved.
        AutoSyncPreferencesRepository.installAudioSyncFallbackPersistence(load, save)
        AutoSyncPreferencesRepository.ensureLoaded()
        assertFalse(AutoSyncPreferencesRepository.audioSyncFallbackEnabled.value)

        AutoSyncPreferencesRepository.setAudioSyncFallbackEnabled(true)
        assertEquals(true, stored)
    }

    @Test
    fun settingTheSameValueAgainSavesNothing() {
        var saves = 0
        AutoSyncPreferencesRepository.installAudioSyncFallbackPersistence(load = { null }, save = { saves++ })
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.setAudioSyncFallbackEnabled(true)
        assertEquals(0, saves)
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioSyncCaptureTest {
    private val timeline = SpeechTimeline()
    private val clock = FakeClock()
    private val duty = AnalysisDuty(clock::now)
    private val capture = AudioSyncCapture(timeline, duty) { null }

    private fun audio(id: String, mime: String = MimeTypes.AUDIO_AC3): Format =
        Format.Builder().setId(id).setSampleMimeType(mime).setChannelCount(6).setSampleRate(48_000).build()

    private fun fillTimeline(frames: Int) {
        for (frame in 0 until frames) timeline.record(frame, 0.5f)
    }

    @Test
    fun beforeAnySubtitleTheFirstMinutesOfAudioAreHeard() {
        assertTrue(capture.wantsSamples(audio("1")))
    }

    @Test
    fun afterTheFirstTenMinutesNothingIsHeardUntilASubtitleIsChosen() {
        fillTimeline(19_000)
        assertFalse(capture.wantsSamples(audio("1")))
        capture.sessionActive = true
        assertTrue(capture.wantsSamples(audio("1")))
    }

    @Test
    fun nothingIsHeardWhenListeningBeforeASessionIsOff() {
        capture.listensBeforeSession = false
        assertFalse(capture.wantsSamples(audio("1")))
        capture.sessionActive = true
        assertTrue(capture.wantsSamples(audio("1")))
    }

    @Test
    fun nothingIsHeardWhileDisabledOrAfterRelease() {
        capture.enabled = false
        assertFalse(capture.wantsSamples(audio("1")))
        capture.enabled = true
        assertTrue(capture.wantsSamples(audio("1")))
        capture.release()
        assertFalse(capture.wantsSamples(audio("1")))
    }

    @Test
    fun onlyTheSelectedAudioTrackIsHeard() {
        capture.onAudioTrackSelected(audio("2"))
        assertFalse(capture.wantsSamples(audio("1")))
        assertTrue(capture.wantsSamples(audio("2")))
        assertTrue(capture.wantsSamples(audio("0:2")))
    }

    @Test
    fun untilATrackIsSelectedTheFirstOneSeenIsAssumed() {
        assertFalse(capture.isPlayingTrack(audio("1")))
        assertTrue(capture.wantsSamples(audio("1")))
        assertTrue(capture.isPlayingTrack(audio("1")))
        assertFalse(capture.wantsSamples(audio("2", MimeTypes.AUDIO_AAC)))
    }

    @Test
    fun withoutTheSpeechDetectorNothingIsHeard() {
        capture.prepare()
        assertTrue(capture.decoderUnavailable)
        assertFalse(capture.wantsSamples(audio("1")))
        assertFalse(capture.wantsPlaybackPcm(0L, 1_000_000L))
    }

    @Test
    fun playbackAudioIsOnlyWantedWhereTheLookAheadLeftAGap() {
        val heard = AudioSyncCapture(timeline, duty) { SyntheticSpeech.sileroWeights() }
        heard.prepare()
        assertTrue(heard.wantsPlaybackPcm(mediaTimeUs = 10_000_000L, durationUs = 1_000_000L))
        val from = SpeechTimeline.frameForTimeUs(10_000_000L)
        val to = SpeechTimeline.frameForTimeUs(11_000_000L) + 1
        for (frame in from..to) timeline.record(frame, 0.5f)
        assertFalse(heard.wantsPlaybackPcm(mediaTimeUs = 10_000_000L, durationUs = 1_000_000L))
        assertFalse(heard.wantsPlaybackPcm(mediaTimeUs = -1L, durationUs = 1_000_000L))
        heard.release()
    }

    @Test
    fun anEasedOffDutyCycleSkipsTheQuietMinutes() {
        val heard = AudioSyncCapture(timeline, duty) { SyntheticSpeech.sileroWeights() }
        heard.prepare()
        duty.markLocked()
        clock.advance(11 * 60_000L)
        assertFalse(heard.wantsPlaybackPcm(mediaTimeUs = 61_000_000L, durationUs = 1_000_000L))
        assertTrue(heard.wantsPlaybackPcm(mediaTimeUs = 181_000_000L, durationUs = 1_000_000L))
        heard.release()
    }
}

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSyncEngineTest {
    private val clock = FakeClock()
    private val timeline = SpeechTimeline()
    private val storage = MemoryMappingStorage()
    private val statuses = mutableListOf<AudioSyncStatus>()

    private fun engine(sourceKey: String = "stream", liveTimeline: SpeechTimeline = timeline): AudioSyncEngine {
        val duty = AnalysisDuty(clock::now)
        val capture = AudioSyncCapture(liveTimeline, duty) { null }
        val sampling = SpotSampling(liveTimeline, { null }, capture::isPlayingTrack, { true }, clock::now)
        return AudioSyncEngine(
            timeline = liveTimeline,
            duty = duty,
            capture = capture,
            sampling = sampling,
            store = SyncMappingStore(storage, sourceKey),
            alignment = InlineExecutorService(),
            nowMs = clock::now,
        ).also { it.onStatus = { status -> statuses += status } }
    }

    /** Plays [full] through [engine], 60 s of audio ahead of the playhead, ticking every 10 s. */
    private fun play(engine: AudioSyncEngine, full: SpeechTimeline, live: SpeechTimeline = timeline) {
        val range = full.knownRange()!!
        val all = full.snapshot(0, range.last + 1)
        val endMs = ((range.last + 1) * SpeechTimeline.FRAME_DURATION_MS).toLong()
        var copied = 0
        var positionMs = 0L
        while (positionMs <= endMs) {
            val horizon = minOf(all.size, ((positionMs + SyntheticSpeech.LOOK_AHEAD_MS) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            engine.onPlaybackPosition(positionMs, endMs)
            clock.advance(10_000L)
            positionMs += 10_000L
        }
    }

    private val cues = SyntheticSpeech.cues(Random(21), durationMs = 20 * 60_000L)
    private val truth = SubtitleSyncSegment(0L, 1.0, -4_500.0)
    private val speech = SyntheticSpeech.speechFor(cues, truth, Random(22))

    @Test
    fun syncsASubtitleToTheAudioAndRemembersIt() {
        val engine = engine()
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        assertEquals(AudioSyncStatus.Listening, statuses.first())

        play(engine, speech)

        assertTrue(statuses.any { it is AudioSyncStatus.Synced }, "$statuses")
        val delayMs = engine.currentModel()!!.delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - truth.shiftMs) < 50.0, "delay $delayMs")
        assertNotNull(SyncMappingStore(storage, "stream").load("subtitle"))
    }

    @Test
    fun aReplayStartsSyncedFromTheRememberedMapping() {
        val first = engine()
        first.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(first, speech)
        val learned = first.currentModel()!!
        statuses.clear()

        val second = engine(liveTimeline = SpeechTimeline())
        second.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))

        val synced = assertIs<AudioSyncStatus.Synced>(statuses.single())
        assertTrue(abs(synced.offsetMs - learned.delayUsAt(0L) / 1_000L) < 5L)
        assertEquals(learned.segments.first().shiftMs, second.currentModel()!!.segments.first().shiftMs, 1e-9)
    }

    @Test
    fun aRememberedMappingBelongsToItsStream() {
        val first = engine(sourceKey = "stream")
        first.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(first, speech)
        statuses.clear()

        val other = engine(sourceKey = "another stream", liveTimeline = SpeechTimeline())
        other.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))

        assertEquals(listOf<AudioSyncStatus>(AudioSyncStatus.Listening), statuses)
        assertNull(other.currentModel())
    }

    @Test
    fun aSubtitleWithTooFewDialogueLinesIsLeftAlone() {
        val engine = engine()
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues.take(10)))
        play(engine, speech)
        assertTrue(statuses.isEmpty(), "$statuses")
        assertNull(engine.currentModel())
    }

    @Test
    fun nothingHappensWhileDisabled() {
        val engine = engine()
        engine.enabled = false
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(engine, speech)
        assertTrue(statuses.isEmpty(), "$statuses")
        assertNull(engine.currentModel())
    }

    @Test
    fun turningItOffHidesTheMappingAndBackOnRestoresIt() {
        val engine = engine()
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(engine, speech)
        assertNotNull(engine.currentModel())
        engine.enabled = false
        assertNull(engine.currentModel())
        engine.enabled = true
        assertNotNull(engine.currentModel())
    }

    @Test
    fun stoppingTheSessionDropsTheMapping() {
        val engine = engine()
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(engine, speech)
        engine.stopSession()
        assertNull(engine.currentModel())
    }

    @Test
    fun startingAnotherSubtitleDropsTheFirstOnesMapping() {
        val engine = engine()
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(engine, speech)
        engine.startSession("another subtitle", AndroidSpeechFixtures.cuesWithTiming(cues.take(10)))
        assertNull(engine.currentModel())
    }

    @Test
    fun aCodecWithoutALookAheadDecoderIsReportedOnlyDuringASession() {
        val engine = engine()
        engine.capture.onLiveOnly("audio/ac3")
        assertTrue(statuses.isEmpty())

        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        statuses.clear()
        engine.capture.onLiveOnly("audio/ac3")

        assertEquals(listOf<AudioSyncStatus>(AudioSyncStatus.LiveOnly("audio/ac3")), statuses)
    }

    @Test
    fun aReleasedEngineDoesNothingMore() {
        val engine = engine()
        engine.release()
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        engine.onPlaybackPosition(10_000L, 600_000L)
        assertTrue(statuses.isEmpty())
        assertNull(engine.currentModel())
    }

    @Test
    fun aFailingListenerDoesNotStopTheAnalysis() {
        val engine = engine()
        engine.onStatus = { error("listener failure") }
        engine.startSession("subtitle", AndroidSpeechFixtures.cuesWithTiming(cues))
        play(engine, speech)
        assertNotNull(engine.currentModel())
    }
}

package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LookaheadAudioSyncTest {
    private val cues = SyntheticSpeech.cues(Random(21), 20 * 60_000L)
    private val unrelatedCues = SyntheticSpeech.cues(Random(205), 20 * 60_000L)
    private val truth = SubtitleSyncSegment(0L, 1.0, -4_500.0)
    private val speech = SyntheticSpeech.speechFor(cues, truth, Random(22))

    private class Harness(scope: TestScope, speech: SpeechTimeline, store: SyncMappingStore? = null, pollMs: Long = 2_000L) {
        val source = FakeLookaheadSource()
        val sinks = mutableListOf<SpeechReplaySink>()
        val models = mutableListOf<SubtitleSyncModel>()
        val statuses = mutableListOf<AudioSyncStatus>()
        val engine = LookaheadAudioSync(
            scope = scope,
            source = source,
            directory = "cache",
            frontEnd = { timeline -> SpeechReplaySink(speech, timeline).also(sinks::add) },
            store = store,
            worker = StandardTestDispatcher(scope.testScheduler),
            nowMs = { scope.testScheduler.currentTime },
            pollMs = pollMs,
        )

        fun takeOver(dialogue: List<Triple<Long, Long, String>>) =
            engine.takeOver("subtitle", dialogue, models::add, statuses::add)

        val chunks: List<SpeechReplaySink.Chunk> get() = sinks.flatMap { it.chunks }
    }

    /** Plays [seconds] of virtual time, letting [tick] move the player along before each second. */
    private suspend fun TestScope.play(seconds: Int, tick: (Int) -> Unit) {
        repeat(seconds) { second ->
            tick(second)
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    /** Normal playback from [startMs] with the player always [ahead] seconds buffered. */
    private fun Harness.playing(startMs: Long, ahead: Double = 240.0): (Int) -> Unit = { second ->
        source.positionMs = startMs + second * 1_000L
        source.bufferEnd = source.positionMs / 1_000.0 + ahead
    }

    @Test
    fun locksOntoTheSubtitleFromTheRecordedAudioAndStopsRecording() = runTest {
        val h = Harness(this, speech)
        assertTrue(h.takeOver(cues))
        play(40, h.playing(startMs = 0))
        h.engine.stop()
        runCurrent()

        assertEquals(AudioSyncStatus.Listening, h.statuses.first())
        assertTrue(h.statuses.any { it is AudioSyncStatus.Synced }, "${h.statuses}")
        val delayMs = h.models.last().delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - truth.shiftMs) < 50.0, "delay $delayMs")
        // One recording, stopped and cleaned up once the mapping was confirmed.
        assertEquals(1, h.source.startedPaths.size)
        assertTrue(h.source.stopCount >= 1)
        assertTrue(h.source.deletedPaths.containsAll(listOf("cache/lookahead-0.mkv", "cache/lookahead-0.pcm")))
    }

    @Test
    fun feedsEveryRecordedSampleOnceAndInOrder() = runTest {
        val h = Harness(this, speech)
        h.takeOver(cues)
        play(40, h.playing(startMs = 0))
        h.engine.stop()
        runCurrent()

        val chunks = h.chunks
        assertTrue(chunks.size > 3, "only ${chunks.size} chunks")
        for (index in 1 until chunks.size) {
            val expectedUs = chunks[index - 1].timeUs + chunks[index - 1].count * 1_000_000L / LookaheadAudioSync.SAMPLE_RATE_HZ
            assertEquals(expectedUs, chunks[index].timeUs, "gap before chunk $index")
        }
    }

    @Test
    fun stampsTheAudioWithTheRecordingsPlaceOnTheMediaClock() = runTest {
        // Resumed at 120 s: the recording begins where the buffer began, and that anchor is what puts its
        // audio at the right place on the film. A wrong anchor would shift the whole result.
        val h = Harness(this, speech)
        h.source.anchorForNext = 120.0
        h.takeOver(cues)
        play(40, h.playing(startMs = 120_000))
        h.engine.stop()
        runCurrent()

        assertEquals(120_000_000L, h.chunks.first().timeUs)
        val delayMs = h.models.last().delayUsAt(900_000_000L) / 1_000.0
        assertTrue(abs(delayMs - truth.shiftMs) < 50.0, "delay $delayMs")
    }

    @Test
    fun keepsTryingUntilThePlayerHasBufferedSomething() = runTest {
        val h = Harness(this, speech)
        h.source.failedStartsLeft = 2
        h.takeOver(unrelatedCues)
        play(3, h.playing(startMs = 0))
        assertTrue(h.source.startedPaths.isEmpty(), "started too early: ${h.source.startedPaths}")
        play(6, h.playing(startMs = 3_000))
        assertEquals(1, h.source.startedPaths.size)
        h.engine.stop()
        runCurrent()
    }

    @Test
    fun startsANewRecordingAfterASeekOutOfTheRecordedRange() = runTest {
        val h = Harness(this, speech)
        h.takeOver(unrelatedCues)
        play(8, h.playing(startMs = 0))
        assertEquals(1, h.source.startedPaths.size)

        // The viewer jumps to 15:00; the player buffers there and the new range starts at 900 s.
        h.source.anchorForNext = 900.0
        play(8, h.playing(startMs = 900_000))
        h.engine.stop()
        runCurrent()

        assertEquals(2, h.source.startedPaths.size)
        assertTrue("cache/lookahead-0.mkv" in h.source.deletedPaths, "the first recording was not removed")
        assertTrue(h.chunks.any { it.timeUs >= 900_000_000L }, "nothing was heard at the new place")
    }

    @Test
    fun doesNotRestartWhileTheViewerPausesAndTheBufferIsFull() = runTest {
        val h = Harness(this, speech)
        h.takeOver(unrelatedCues)
        h.source.positionMs = 30_000
        h.source.bufferEnd = 270.0
        play(90) { }
        h.engine.stop()
        runCurrent()

        assertEquals(1, h.source.startedPaths.size)
    }

    @Test
    fun startsANewRecordingWhenOneStopsGrowingWhileThePlayerKeepsBuffering() = runTest {
        val h = Harness(this, speech)
        h.takeOver(unrelatedCues)
        play(10, h.playing(startMs = 0, ahead = 60.0))
        h.source.decodeStalled = true
        play(50, h.playing(startMs = 10_000, ahead = 60.0))
        h.engine.stop()
        runCurrent()

        assertTrue(h.source.startedPaths.size >= 2, "started ${h.source.startedPaths.size}")
    }

    @Test
    fun stopsRecordingOnceItHoldsEnoughAudioAndDoesNotStartAgain() = runTest {
        val h = Harness(this, speech, pollMs = 10_000)
        h.takeOver(unrelatedCues)
        // The player has buffered 8 minutes ahead from the start.
        play(60) { second ->
            h.source.positionMs = second * 1_000L
            h.source.bufferEnd = 480.0
        }
        h.engine.stop()
        runCurrent()

        assertEquals(1, h.source.startedPaths.size)
        assertTrue("cache/lookahead-0.mkv" in h.source.deletedPaths)
    }

    @Test
    fun givesUpAfterTheLongestRunWithoutAMapping() = runTest {
        val h = Harness(this, speech, pollMs = 60_000)
        h.takeOver(unrelatedCues)
        play(11 * 60, h.playing(startMs = 0, ahead = 120.0))
        runCurrent()

        assertTrue("cache/lookahead-0.mkv" in h.source.deletedPaths, "recording not cleaned up")
        assertTrue(h.models.isEmpty() || h.statuses.none { it is AudioSyncStatus.Synced })
    }

    @Test
    fun stoppingEndsTheRecordingAndRemovesItsFiles() = runTest {
        val h = Harness(this, speech)
        h.takeOver(unrelatedCues)
        play(6, h.playing(startMs = 0))
        h.engine.stop()
        runCurrent()

        assertTrue(h.source.stopCount >= 1)
        assertTrue(h.source.deletedPaths.containsAll(listOf("cache/lookahead-0.mkv", "cache/lookahead-0.pcm")))
    }

    @Test
    fun aSubtitleWithTooFewLinesIsNotStarted() = runTest {
        val h = Harness(this, speech)
        assertFalse(h.takeOver(cues.take(10)))
        play(5, h.playing(startMs = 0))
        assertTrue(h.source.startedPaths.isEmpty())
    }

    @Test
    fun aRememberedMappingIsAppliedAtOnceWithoutRecording() = runTest {
        val storage = MemoryMappingStorage()
        val remembered = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 1_234.0)))
        SyncMappingStore(storage, "stream").save("subtitle", remembered)
        val h = Harness(this, speech, store = SyncMappingStore(storage, "stream"))
        h.source.positionMs = 30_000

        assertTrue(h.takeOver(cues))
        runCurrent()

        assertEquals(remembered.segments, h.models.single().segments)
        assertIs<AudioSyncStatus.Synced>(h.statuses.single())
        assertTrue(h.source.startedPaths.isEmpty())
    }

    @Test
    fun aConfirmedMappingIsRememberedForNextTime() = runTest {
        val storage = MemoryMappingStorage()
        val h = Harness(this, speech, store = SyncMappingStore(storage, "stream"))
        h.takeOver(cues)
        play(40, h.playing(startMs = 0))
        h.engine.stop()
        runCurrent()

        val saved = assertNotNull(SyncMappingStore(storage, "stream").load("subtitle"))
        assertTrue(abs(saved.delayUsAt(600_000_000L) / 1_000.0 - truth.shiftMs) < 50.0)
    }

    @Test
    fun withoutASpeechDetectorNothingIsRecorded() = runTest {
        val h = Harness(this, speech)
        val engine = LookaheadAudioSync(
            scope = this, source = h.source, directory = "cache", frontEnd = { null }, store = null,
            worker = StandardTestDispatcher(testScheduler), nowMs = { testScheduler.currentTime },
        )
        assertTrue(engine.takeOver("subtitle", cues, h.models::add, h.statuses::add))
        play(5, h.playing(startMs = 0))
        assertTrue(h.source.startedPaths.isEmpty())
        assertNull(h.models.firstOrNull())
    }

    @Test
    fun decodedAudioIsNeverReadTwice() = runTest {
        val h = Harness(this, speech)
        h.takeOver(unrelatedCues)
        play(20, h.playing(startMs = 0))
        h.engine.stop()
        runCurrent()

        val total = h.chunks.sumOf { it.count }
        assertTrue(total > 0)
        // Every sample came from one recording: the fed length equals one decode's length, not several.
        assertTrue(total <= (480 * LookaheadAudioSync.SAMPLE_RATE_HZ), "fed $total samples")
        assertContentEquals(h.chunks.map { it.count }.map { it > 0 }, h.chunks.map { true })
    }
}

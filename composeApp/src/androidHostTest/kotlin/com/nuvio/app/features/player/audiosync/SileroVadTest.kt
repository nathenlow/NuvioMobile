package com.nuvio.app.features.player.audiosync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SileroVadTest {
    @Test
    fun portMatchesTheReferenceModel() {
        val vad = SileroVad(SyntheticSpeech.sileroWeights())
        val signal = referenceSignal(REFERENCE_PROBABILITIES.size * SileroVad.CHUNK_SAMPLES)
        REFERENCE_PROBABILITIES.forEachIndexed { index, expected ->
            val actual = vad.process(signal, index * SileroVad.CHUNK_SAMPLES)
            assertTrue(abs(actual - expected) < 0.01f, "chunk $index: expected $expected, got $actual")
        }
    }

    @Test
    fun analyzerAlignsFramesToMediaTime() {
        val timeline = SpeechTimeline()
        val analyzer = SpeechAnalyzer(SileroVad(SyntheticSpeech.sileroWeights()), timeline)
        val rate = 48_000
        val block = FloatArray(1_024) { (0.1 * sin(2 * PI * 220 * it / rate)).toFloat() }
        var timeUs = 10_000_000L
        repeat(200) {
            analyzer.accept(block, block.size, rate, timeUs)
            timeUs += block.size * 1_000_000L / rate
        }
        val range = assertNotNull(timeline.knownRange())
        // First chunk starts on the next 32 ms boundary; four warm-up chunks are not recorded.
        assertEquals(313 + 4, range.first)
        val expectedLast = ((timeUs - 1) / SpeechTimeline.FRAME_DURATION_US).toInt() - 1
        assertTrue(abs(range.last - expectedLast) <= 1, "last frame ${range.last} vs $expectedLast")
    }

    @Test
    fun aTimestampJumpRestartsTheAnalysisWithoutSmearingTheGap() {
        val timeline = SpeechTimeline()
        val analyzer = SpeechAnalyzer(SileroVad(SyntheticSpeech.sileroWeights()), timeline)
        val rate = 16_000
        val block = FloatArray(1_600) { (0.1 * sin(2 * PI * 220 * it / rate)).toFloat() }
        // Ten seconds of audio, then a seek to 10 minutes.
        var timeUs = 0L
        repeat(100) {
            analyzer.accept(block, block.size, rate, timeUs)
            timeUs += block.size * 1_000_000L / rate
        }
        timeUs = 600_000_000L
        repeat(100) {
            analyzer.accept(block, block.size, rate, timeUs)
            timeUs += block.size * 1_000_000L / rate
        }
        val gapStart = SpeechTimeline.frameForTimeUs(11_000_000L)
        val gapEnd = SpeechTimeline.frameForTimeUs(599_000_000L)
        assertEquals(0, timeline.knownFramesIn(gapStart, gapEnd))
    }

    @Test
    fun weightsFileIsRejectedWhenItIsNotTheExpectedFormat() {
        var failed = false
        try {
            SileroVadWeights.read("not a weight file".byteInputStream())
        } catch (_: IllegalArgumentException) {
            failed = true
        } catch (_: java.io.EOFException) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun referenceSignal(length: Int): FloatArray {
        var state = 12_345L
        return FloatArray(length) { n ->
            state = (state * 1_103_515_245L + 12_345L) % (1L shl 31)
            val noise = state.toDouble() / (1L shl 31) - 0.5
            val envelope = 0.5 + 0.5 * sin(2 * PI * 3 * n / 16_000)
            val t = 2 * PI * n / 16_000
            (0.25 * envelope * (sin(180 * t) + 0.5 * sin(360 * t) + 0.25 * sin(540 * t)) + 0.02 * noise).toFloat()
        }
    }

    private companion object {
        /** Silero VAD v5 (official ONNX export, onnxruntime) on [referenceSignal], one value per 512 samples. */
        val REFERENCE_PROBABILITIES = floatArrayOf(
            0.5301f, 0.7079f, 0.7212f, 0.6686f, 0.5150f, 0.5388f, 0.9100f, 0.9640f, 0.8622f, 0.8879f,
            0.8539f, 0.7242f, 0.6466f, 0.6541f, 0.5937f, 0.5775f, 0.6165f, 0.8755f, 0.8050f, 0.7650f,
            0.7258f, 0.6130f, 0.5258f, 0.4998f, 0.4177f, 0.3685f, 0.2827f, 0.3774f, 0.2654f, 0.3670f,
            0.4804f, 0.3676f, 0.1679f, 0.0845f, 0.0941f, 0.0768f, 0.0639f, 0.0839f, 0.0938f, 0.1370f,
        )
    }
}

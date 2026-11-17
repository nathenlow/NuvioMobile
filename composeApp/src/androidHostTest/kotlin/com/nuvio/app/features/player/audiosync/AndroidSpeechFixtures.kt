package com.nuvio.app.features.player.audiosync

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import java.io.File

/** Fixtures that need Android or the JVM; the rest live in [SyntheticSpeech], shared with iOS. */
internal object AndroidSpeechFixtures {
    /** The bundled Silero VAD weights. Host tests run with the module directory as working directory. */
    fun sileroWeights(): SileroVadWeights =
        SileroVadWeights.read(File("src/androidMain/res/raw/silero_vad_v5_16k.bin").readBytes())

    /** [cues] as a sidecar subtitle holds them. */
    fun cuesWithTiming(cues: List<TestCue>): List<CuesWithTiming> =
        cues.map { (startMs, endMs, text) ->
            CuesWithTiming(listOf(Cue.Builder().setText(text).build()), startMs * 1_000L, (endMs - startMs) * 1_000L)
        }
}

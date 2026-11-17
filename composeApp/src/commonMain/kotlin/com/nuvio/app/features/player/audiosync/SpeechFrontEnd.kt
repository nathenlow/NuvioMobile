package com.nuvio.app.features.player.audiosync

/**
 * The speech detector for a timeline: Silero VAD run over PCM stamped with its place on the media clock.
 * Null when the bundled weights cannot be read.
 */
internal suspend fun sileroFrontEnd(timeline: SpeechTimeline): PcmSink? {
    val weights = SileroVadLoader.weights() ?: return null
    val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
    return PcmSink(analyzer::accept)
}

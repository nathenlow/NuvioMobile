package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.audiosync.LookaheadAudioSource
import kotlin.math.min

/**
 * The audio of a downloaded episode. The whole file is already on the device, so there is nothing to
 * record: the headless mpv decodes the file itself, from its start, and the engine treats it as a
 * recording that is complete and never grows. The decoded stretch is capped at what the engine
 * needs, since decoding a whole film to PCM would be hundreds of megabytes.
 */
internal class IosLocalFileAudioSource(
    private val bridge: NuvioPlayerBridge,
    private val filePath: String,
    private val pcm: LookaheadAudioSource = IosLookaheadAudioSource(bridge),
) : LookaheadAudioSource by pcm {
    override fun startRecording(path: String): Boolean = true

    override fun stopRecording() = Unit

    /** The file starts at the media clock's 0. */
    override fun anchorSeconds(): Double = 0.0

    /** All of it is there. NaN until the player knows the length. */
    override fun bufferEndSeconds(): Double =
        bridge.getDurationMs().takeIf { it > 0 }?.let { it / 1_000.0 } ?: Double.NaN

    /** The engine names a recording file; the real file is the one decoded. */
    override fun decode(recordingPath: String, pcmPath: String, endSeconds: Double): Boolean =
        pcm.decode(filePath, pcmPath, min(endSeconds, MAX_DECODE_SECONDS))

    private companion object {
        /** Just past the engine's recording limit, so it stops after this one pass. */
        const val MAX_DECODE_SECONDS = 361.0
    }
}

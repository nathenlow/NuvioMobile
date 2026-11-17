package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.audiosync.LookaheadAudioSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/** The iOS player's read-ahead, through the Swift bridge (LookaheadAudio.swift). */
@OptIn(ExperimentalForeignApi::class)
internal class IosLookaheadAudioSource(private val bridge: NuvioPlayerBridge) : LookaheadAudioSource {
    override fun startRecording(path: String): Boolean = bridge.startLookaheadRecording(path)

    override fun stopRecording() = bridge.stopLookaheadRecording()

    override fun anchorSeconds(): Double = bridge.getLookaheadAnchorSeconds()

    override fun bufferEndSeconds(): Double = bridge.getBufferEndSeconds()

    override fun positionMs(): Long = bridge.getPositionMs()

    override fun decode(recordingPath: String, pcmPath: String, endSeconds: Double): Boolean =
        bridge.decodeLookaheadAudio(recordingPath, pcmPath, endSeconds)

    override fun readPcm(pcmPath: String, fromSample: Int): FloatArray {
        val file = fopen(pcmPath, "rb") ?: return FloatArray(0)
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            val start = fromSample.toLong() * Float.SIZE_BYTES
            val count = ((size - start) / Float.SIZE_BYTES).toInt()
            if (count <= 0) return FloatArray(0)
            fseek(file, start, SEEK_SET)
            val samples = FloatArray(count)
            val read = samples.usePinned { fread(it.addressOf(0), Float.SIZE_BYTES.convert(), count.convert(), file) }
            return if (read.toInt() == count) samples else samples.copyOf(read.toInt())
        } finally {
            fclose(file)
        }
    }

    override fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}

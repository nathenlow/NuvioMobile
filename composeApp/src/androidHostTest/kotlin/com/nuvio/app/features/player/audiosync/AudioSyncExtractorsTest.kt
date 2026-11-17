@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioSyncExtractorsTest {
    private val audioFormat = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
    private val sink = RecordingSink()
    private val inner = FakeExtractor()
    private val player = RecordingOutput()
    private val extractor = AudioSyncExtractorsFactory(FixedFactory(inner), sink).createExtractors().single()

    init {
        extractor.init(player)
    }

    private fun audioTrack(): TrackOutput = inner.output.track(0, C.TRACK_TYPE_AUDIO).also { it.format(audioFormat) }

    @Test
    fun everyAudioSampleIsCopiedAndForwardedUnchanged() {
        val track = audioTrack()
        track.writeSample(byteArrayOf(1, 2, 3), timeUs = 1_000L)
        track.writeSample(byteArrayOf(4, 5), timeUs = 2_000L)

        assertEquals(listOf(1_000L, 2_000L), sink.samples.map { it.timeUs })
        assertContentEquals(byteArrayOf(1, 2, 3), sink.samples[0].data)
        assertContentEquals(byteArrayOf(4, 5), sink.samples[1].data)
        // The player's own track sees exactly the same bytes and metadata.
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), player.audio.written())
        assertEquals(listOf(1_000L to 3, 2_000L to 2), player.audio.metadata)
    }

    @Test
    fun aSampleWrittenInPartsIsDeliveredWhole() {
        val track = audioTrack()
        track.sampleData(ParsableByteArray(byteArrayOf(1, 2)), 2, 0)
        track.sampleData(ParsableByteArray(byteArrayOf(3, 4, 5)), 3, 0)
        track.sampleMetadata(7_000L, C.BUFFER_FLAG_KEY_FRAME, 5, 0, null)

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), sink.samples.single().data)
    }

    @Test
    fun metadataReportedLateStillFindsItsOwnBytes() {
        // An extractor may write several samples before reporting the first: offset counts the
        // bytes of later samples that are already written.
        val track = audioTrack()
        track.sampleData(ParsableByteArray(byteArrayOf(1, 1)), 2, 0)
        track.sampleData(ParsableByteArray(byteArrayOf(2, 2, 2)), 3, 0)
        track.sampleData(ParsableByteArray(byteArrayOf(3)), 1, 0)
        track.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, 2, 4, null)
        track.sampleMetadata(2_000L, C.BUFFER_FLAG_KEY_FRAME, 3, 1, null)
        track.sampleMetadata(3_000L, C.BUFFER_FLAG_KEY_FRAME, 1, 0, null)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), sink.samples.map { it.timeUs })
        assertContentEquals(byteArrayOf(1, 1), sink.samples[0].data)
        assertContentEquals(byteArrayOf(2, 2, 2), sink.samples[1].data)
        assertContentEquals(byteArrayOf(3), sink.samples[2].data)
    }

    @Test
    fun bytesReadFromADataReaderAreCopiedAndForwarded() {
        val track = audioTrack()
        val source = byteArrayOf(9, 8, 7, 6)
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            val count = minOf(length, source.size - position)
            if (count <= 0) return@DataReader C.RESULT_END_OF_INPUT
            source.copyInto(buffer, offset, position, position + count)
            position += count
            count
        }
        var total = 0
        while (total < source.size) total += track.sampleData(reader, source.size - total, false, 0)
        track.sampleMetadata(5_000L, C.BUFFER_FLAG_KEY_FRAME, source.size, 0, null)

        assertContentEquals(source, sink.samples.single().data)
        assertContentEquals(source, player.audio.written())
    }

    @Test
    fun nothingIsCopiedWhenTheSinkDoesNotWantSamples() {
        sink.wants = false
        val track = audioTrack()
        track.writeSample(byteArrayOf(1, 2, 3), timeUs = 1_000L)

        assertTrue(sink.samples.isEmpty())
        assertContentEquals(byteArrayOf(1, 2, 3), player.audio.written())
        assertEquals(listOf(1_000L to 3), player.audio.metadata)
    }

    @Test
    fun onlyAudioTracksAreTapped() {
        val video = inner.output.track(1, C.TRACK_TYPE_VIDEO)
        assertSame(player.video, video)
        val audio = inner.output.track(0, C.TRACK_TYPE_AUDIO)
        assertTrue(audio !== player.audio)
    }

    @Test
    fun aSeekIsReportedAsADiscontinuity() {
        extractor.seek(1_000L, 5_000_000L)
        assertEquals(1, sink.discontinuities)
        assertEquals(listOf(1_000L to 5_000_000L), inner.seeks)
    }

    @Test
    fun aFailingSinkNeverDisturbsPlayback() {
        sink.fail = true
        val track = audioTrack()
        track.writeSample(byteArrayOf(1, 2, 3), timeUs = 1_000L)

        assertContentEquals(byteArrayOf(1, 2, 3), player.audio.written())
        assertEquals(listOf(1_000L to 3), player.audio.metadata)
    }

    private fun TrackOutput.writeSample(bytes: ByteArray, timeUs: Long) {
        sampleData(ParsableByteArray(bytes), bytes.size, 0)
        sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    private class Sample(val timeUs: Long, val data: ByteArray)

    private class RecordingSink : AudioSampleSink {
        val samples = mutableListOf<Sample>()
        var discontinuities = 0
        var wants = true
        var fail = false

        override fun wantsSamples(format: Format): Boolean = wants

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            if (fail) error("sink failure")
            samples += Sample(timeUs, data.copyOfRange(offset, offset + size))
        }

        override fun onDiscontinuity() {
            discontinuities++
        }
    }

    /** The player's side: records what reaches it. */
    private class RecordingTrack : TrackOutput {
        private val bytes = java.io.ByteArrayOutputStream()
        val metadata = mutableListOf<Pair<Long, Int>>()

        fun written(): ByteArray = bytes.toByteArray()

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val buffer = ByteArray(length)
            val read = input.read(buffer, 0, length)
            if (read > 0) bytes.write(buffer, 0, read)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            bytes.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            metadata += timeUs to size
        }
    }

    private class RecordingOutput : ExtractorOutput {
        val audio = RecordingTrack()
        val video = RecordingTrack()

        override fun track(id: Int, type: Int): TrackOutput = if (type == C.TRACK_TYPE_AUDIO) audio else video

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class FakeExtractor : Extractor {
        lateinit var output: ExtractorOutput
        val seeks = mutableListOf<Pair<Long, Long>>()

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT

        override fun seek(position: Long, timeUs: Long) {
            seeks += position to timeUs
        }

        override fun release() = Unit
    }

    private class FixedFactory(private val extractor: Extractor) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = arrayOf(extractor)

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            arrayOf(extractor)
    }
}

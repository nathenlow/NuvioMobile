package com.nuvio.app.features.player.audiosync

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SileroVadWeightsTest {
    @Test
    fun aFileThatIsNotTheExpectedFormatIsRejected() {
        assertFailsWith<IllegalArgumentException> { SileroVadWeights.read("not a weight file".encodeToByteArray()) }
    }

    @Test
    fun aTruncatedFileIsRejectedWithoutACrash() {
        val header = "NSVAD16\u0000".encodeToByteArray() + byteArrayOf(14, 0, 0, 0, 100, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> { SileroVadWeights.read(header) }
    }

    @Test
    fun anEmptyFileIsRejected() {
        assertFailsWith<IllegalArgumentException> { SileroVadWeights.read(ByteArray(0)) }
    }
}

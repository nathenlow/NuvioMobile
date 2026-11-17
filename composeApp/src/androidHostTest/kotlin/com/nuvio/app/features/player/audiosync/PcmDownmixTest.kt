package com.nuvio.app.features.player.audiosync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class PcmDownmixTest {
    @Test
    fun stereoFloatIsAveraged() {
        val buffer = floats(0.2f, 0.4f, -0.6f, 0.2f)
        val out = FloatArray(2)
        PcmDownmix.toMono(buffer, startByte = 0, frames = 2, channels = 2, isFloat = true, centerIndex = -1, out = out)
        assertEquals(0.3f, out[0], 1e-6f)
        assertEquals(-0.2f, out[1], 1e-6f)
    }

    @Test
    fun sixteenBitSamplesAreScaledToUnitRange() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        buffer.putShort(16_384).putShort(-16_384)
        val out = FloatArray(1)
        PcmDownmix.toMono(buffer, startByte = 0, frames = 1, channels = 2, isFloat = false, centerIndex = -1, out = out)
        assertEquals(0f, out[0], 1e-6f)
        val single = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort(16_384)
        PcmDownmix.toMono(single, startByte = 0, frames = 1, channels = 1, isFloat = false, centerIndex = -1, out = out)
        assertEquals(0.5f, out[0], 1e-6f)
    }

    @Test
    fun surroundFavoursTheCentreChannelWhereDialogueLives() {
        // Front left, front right, centre, then LFE and two surrounds that must not leak in.
        val buffer = floats(0.1f, 0.1f, 0.8f, 0.9f, 0.9f, 0.9f)
        val out = FloatArray(1)
        PcmDownmix.toMono(buffer, startByte = 0, frames = 1, channels = 6, isFloat = true, centerIndex = 2, out = out)
        assertEquals(0.7f * 0.8f + 0.15f * (0.1f + 0.1f), out[0], 1e-6f)
    }

    @Test
    fun startByteSelectsWhereReadingBegins() {
        val buffer = floats(9f, 9f, 0.5f)
        val out = FloatArray(1)
        PcmDownmix.toMono(buffer, startByte = 8, frames = 1, channels = 1, isFloat = true, centerIndex = -1, out = out)
        assertEquals(0.5f, out[0], 1e-6f)
    }

    private fun floats(vararg values: Float): ByteBuffer {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        return buffer
    }
}

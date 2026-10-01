package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFrameAccumulatorTest {
    @Test
    fun emitsExactlyOneTwentyMsStereoFrame() {
        val accumulator = PcmFrameAccumulator(sampleRate = 48_000, channels = 2, frameMs = 20)
        val bytes = ByteArray(960 * 2 * 2)
        for (frame in 0 until 960) {
            val left = (frame and 0x7FFF).toShort()
            val right = (-frame).toShort()
            writeLe16(bytes, frame * 4, left)
            writeLe16(bytes, frame * 4 + 2, right)
        }

        val out = mutableListOf<PcmFrame>()
        accumulator.push(bytes, 1372, 1_000_000_000L) { out += it }
        assertTrue(out.isEmpty())
        accumulator.push(bytes.copyOfRange(1372, bytes.size), bytes.size - 1372, 1_020_000_000L) { out += it }

        assertEquals(1, out.size)
        assertEquals(960, out[0].sampleCount)
        assertEquals(1920, out[0].pcm.size)
        assertEquals(0L, out[0].samplePosition)
    }

    @Test
    fun sequenceSamplePositionAdvancesPerChannelFrames() {
        val accumulator = PcmFrameAccumulator(sampleRate = 48_000, channels = 2, frameMs = 20)
        val bytes = ByteArray(960 * 2 * 2 * 2)
        val out = mutableListOf<PcmFrame>()
        accumulator.push(bytes, bytes.size, 2_000_000_000L) { out += it }

        assertEquals(2, out.size)
        assertEquals(0L, out[0].samplePosition)
        assertEquals(960L, out[1].samplePosition)
        assertEquals(1920L, accumulator.samplePosition())
    }

    @Test
    fun recoveryGapAdvancesLiveTimeline() {
        val accumulator = PcmFrameAccumulator(sampleRate = 48_000, channels = 2, frameMs = 20)
        val bytes = ByteArray(960 * 2 * 2)
        val out = mutableListOf<PcmFrame>()

        accumulator.push(bytes, bytes.size, 1_020_000_000L) { out += it }
        accumulator.advanceGapNanos(3_000_000_000L)
        accumulator.push(bytes, bytes.size, 4_040_000_000L) { out += it }

        assertEquals(2, out.size)
        assertEquals(144_960L, out[1].samplePosition)
    }

    private fun writeLe16(target: ByteArray, offset: Int, value: Short) {
        val v = value.toInt()
        target[offset] = (v and 0xFF).toByte()
        target[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}

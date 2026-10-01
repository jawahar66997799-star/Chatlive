package com.jawahar.livesync

import org.junit.Assert.assertTrue
import org.junit.Test

class OpusEncoderEngineTest {
    @Test
    fun encodesTwentyMsStereoMusicFrame() {
        val encoder = OpusEncoderEngine(frameMs = 20, targetBitrateBps = 144_000)
        val frame = PcmFrame(
            pcm = ShortArray(960 * 2) { i -> ((i * 31) % 20_000 - 10_000).toShort() },
            captureMonoNs = 1L,
            samplePosition = 0L,
            sampleCount = 960
        )

        val encoded = encoder.encode(frame)
        assertTrue(encoded.payload.isNotEmpty())
        assertTrue(encoded.payload.size <= 1275)
        assertTrue(encoded.encodeTimeUs >= 0)
    }

    @Test
    fun encodesTenMsBenchmarkFrame() {
        val encoder = OpusEncoderEngine(frameMs = 10, targetBitrateBps = 144_000)
        val frame = PcmFrame(
            pcm = ShortArray(480 * 2) { i -> ((i * 17) % 16_000 - 8_000).toShort() },
            captureMonoNs = 1L,
            samplePosition = 0L,
            sampleCount = 480
        )

        val encoded = encoder.encode(frame)
        assertTrue(encoded.payload.isNotEmpty())
        assertTrue(encoded.payload.size <= 1275)
    }
}

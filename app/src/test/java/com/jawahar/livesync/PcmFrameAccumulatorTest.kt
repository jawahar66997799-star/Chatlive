package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFrameAccumulatorTest {
    @Test
    fun emitsExactlyOneTwentyMillisecondStereoFrame() {
        val acc = PcmFrameAccumulator(
            sampleRate = 48_000,
            channels = 2,
            frameMs = 20
        )
        val bytes = ByteArray(960 * 2 * 2)

        var emitted: PcmFrame? = null
        acc.push(
            bytes,
            bytes.size,
            1_000_000_000L
        ) {
            emitted = it
        }

        val frame = emitted!!
        assertEquals(960, frame.sampleCount)
        assertEquals(0L, frame.samplePosition)
        assertEquals(1920, frame.pcm.size)
        assertTrue(frame.captureMonoNs < 1_000_000_000L)
    }

    @Test
    fun carriesSamplePositionAcrossPartialReads() {
        val acc = PcmFrameAccumulator(
            sampleRate = 48_000,
            channels = 2,
            frameMs = 20
        )
        val half = ByteArray(480 * 2 * 2)
        val frames = mutableListOf<PcmFrame>()

        acc.push(
            half,
            half.size,
            1_000_000_000L,
            frames::add
        )
        assertTrue(frames.isEmpty())

        acc.push(
            half,
            half.size,
            1_010_000_000L,
            frames::add
        )
        assertEquals(1, frames.size)
        assertEquals(0L, frames[0].samplePosition)

        val full = ByteArray(960 * 2 * 2)
        acc.push(
            full,
            full.size,
            1_030_000_000L,
            frames::add
        )
        assertEquals(2, frames.size)
        assertEquals(960L, frames[1].samplePosition)
    }
}

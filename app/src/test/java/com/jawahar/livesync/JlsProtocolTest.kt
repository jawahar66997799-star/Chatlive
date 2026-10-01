package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JlsProtocolTest {
    @Test
    fun audioHeaderIsStableAndBigEndian() {
        val frame = EncodedAudioFrame(
            epoch = 11L,
            sequence = 22L,
            captureMonoNs = 33L,
            samplePosition = 44L,
            sampleCount = 960,
            layerId = 0,
            flags = 0,
            payload = byteArrayOf(1, 2, 3),
            encodeTimeUs = 100L
        )

        val bytes = JlsProtocol.encodeAudio(frame)
        assertEquals(
            JlsProtocol.HEADER_BYTES + 3,
            bytes.size
        )
        assertEquals("JLS2", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))

        val bb =
            ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)

        bb.position(4)
        assertEquals(2, bb.get().toInt())
        assertEquals(1, bb.get().toInt())
        assertEquals(1, bb.get().toInt())
        assertEquals(0, bb.get().toInt())
        assertEquals(0, bb.short.toInt())
        assertEquals(52, bb.short.toInt())
        assertEquals(11L, bb.long)
        assertEquals(22L, bb.long)
        assertEquals(33L, bb.long)
        assertEquals(44L, bb.long)
        assertEquals(960, bb.int)
        assertEquals(3, bb.int)
        assertTrue(bytes.takeLast(3) == listOf<Byte>(1, 2, 3))
    }
}

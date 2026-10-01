package com.jawahar.livesync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JlsProtocolTest {
    @Test
    fun packetHeaderIsStableAndBigEndian() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = JlsProtocol.packetize(
            EncodedAudioFrame(
                epoch = 0x01020304,
                sequence = 9,
                captureMonoNs = 10,
                samplePosition = 960,
                sampleCount = 960,
                bitrateBps = 144_000,
                flags = JlsProtocol.FLAG_DISCONTINUITY,
                payload = payload
            )
        )

        assertEquals(JlsProtocol.HEADER_BYTES + payload.size, packet.size)
        assertArrayEquals(byteArrayOf('J'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte()), packet.copyOfRange(0, 4))

        val b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        b.position(4)
        assertEquals(2, b.get().toInt() and 0xFF)
        assertEquals(48, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.CODEC_OPUS, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.LAYER_HIGH, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.FLAG_DISCONTINUITY, b.short.toInt() and 0xFFFF)
        assertEquals(2, b.get().toInt() and 0xFF)
        assertEquals(20, b.get().toInt() and 0xFF)
        assertEquals(0x01020304, b.int)
        assertEquals(9, b.int)
        assertEquals(10L, b.long)
        assertEquals(960L, b.long)
        assertEquals(960, b.int)
        assertEquals(payload.size, b.int)
        assertEquals(144_000, b.int)
        assertArrayEquals(payload, packet.copyOfRange(JlsProtocol.HEADER_BYTES, packet.size))
    }
}

package com.jawahar.livesync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class JlsProtocolTest {
    @Test
    fun packetHeaderMatchesRelayV1Contract() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet = JlsProtocol.packetize(
            EncodedAudioFrame(
                epoch = 0x0102030405060708L,
                sequence = 9,
                captureMonoNs = 10,
                samplePosition = 960,
                sampleCount = 960,
                flags = JlsProtocol.FLAG_DISCONTINUITY,
                payload = payload
            )
        )

        assertEquals(JlsProtocol.HEADER_BYTES + payload.size, packet.size)
        assertArrayEquals(
            byteArrayOf('J'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte()),
            packet.copyOfRange(0, 4)
        )

        val b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        b.position(4)
        assertEquals(1, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.MESSAGE_AUDIO, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.FLAG_DISCONTINUITY, b.get().toInt() and 0xFF)
        assertEquals(64, b.get().toInt() and 0xFF)
        assertEquals(0x0102030405060708L, b.long)
        assertEquals(9L, b.long)
        assertEquals(10L, b.long)
        assertEquals(960L, b.long)
        assertEquals(0L, b.long)
        assertEquals(48_000, b.int)
        assertEquals(960, b.short.toInt() and 0xFFFF)
        assertEquals(2, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.CODEC_OPUS, b.get().toInt() and 0xFF)
        assertEquals(JlsProtocol.LAYER_HIGH, b.get().toInt() and 0xFF)
        b.get()
        assertEquals(payload.size, b.short.toInt() and 0xFFFF)
        assertEquals(0, b.int)
        assertArrayEquals(payload, packet.copyOfRange(JlsProtocol.HEADER_BYTES, packet.size))
    }
}

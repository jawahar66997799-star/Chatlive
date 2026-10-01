package com.jawahar.livesync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class JlsGoldenVectorTest {
    @Test
    fun packetizerMatchesSharedGoldenVectorByteForByte() {
        val expected = hexToBytes(loadPacketHex())
        val actual = JlsProtocol.packetize(
            EncodedAudioFrame(
                epoch = 0x0102030405060708L,
                sequence = 0x1112131415161718L,
                captureMonoNs = 0x2122232425262728L,
                samplePosition = 960L,
                sampleCount = 960,
                flags = JlsProtocol.FLAG_DISCONTINUITY,
                payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                layer = JlsProtocol.LAYER_HIGH
            ),
            channels = 2
        )
        assertEquals(68, actual.size)
        assertArrayEquals(expected, actual)
    }

    private fun loadPacketHex(): String {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(cwd, "protocol/jls_v1_golden_vectors.json"),
            File(cwd, "../protocol/jls_v1_golden_vectors.json"),
            File(cwd, "../../protocol/jls_v1_golden_vectors.json")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("shared golden vector not found from cwd=${cwd.absolutePath}")
        val match = Regex("\\\"packet_hex\\\"\\s*:\\s*\\\"([0-9a-fA-F]+)\\\"").find(file.readText())
            ?: error("packet_hex missing from shared golden vector")
        return match.groupValues[1]
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

package com.jawahar.livesync

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EncodedAudioFrame(
    val epoch: Long,
    val sequence: Long,
    val captureMonoNs: Long,
    val samplePosition: Long,
    val sampleCount: Int,
    val flags: Int,
    val payload: ByteArray,
    val layer: Int = JlsProtocol.LAYER_HIGH
)

object JlsProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 64
    const val MESSAGE_AUDIO = 1
    const val CODEC_OPUS = 1
    const val LAYER_HIGH = 0
    const val LAYER_LOW = 1

    const val FLAG_DISCONTINUITY = 1 shl 0
    const val FLAG_KEY_BOUNDARY = 1 shl 1

    private const val SAMPLE_RATE = 48_000
    private val MAGIC = byteArrayOf('J'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())

    fun packetize(frame: EncodedAudioFrame, channels: Int = 2): ByteArray {
        require(channels in 1..2)
        require(frame.epoch > 0)
        require(frame.sequence >= 0)
        require(frame.sampleCount in 1..0xFFFF)
        require(frame.payload.size <= 0xFFFF)

        val out = ByteBuffer.allocate(HEADER_BYTES + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.put(VERSION.toByte())
        out.put(MESSAGE_AUDIO.toByte())
        out.put((frame.flags and 0xFF).toByte())
        out.put(HEADER_BYTES.toByte())
        out.putLong(frame.epoch)
        out.putLong(frame.sequence)
        out.putLong(frame.captureMonoNs)
        out.putLong(frame.samplePosition)
        out.putLong(0L) // relay_ingress_ns is stamped by the relay
        out.putInt(SAMPLE_RATE)
        out.putShort(frame.sampleCount.toShort())
        out.put(channels.toByte())
        out.put(CODEC_OPUS.toByte())
        out.put(frame.layer.toByte())
        out.put(0) // reserved
        out.putShort(frame.payload.size.toShort())
        out.putInt(0) // reserved bytes 60..63
        out.put(frame.payload)
        return out.array()
    }
}

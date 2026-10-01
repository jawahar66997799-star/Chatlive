package com.jawahar.livesync

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EncodedAudioFrame(
    val epoch: Int,
    val sequence: Long,
    val captureMonoNs: Long,
    val samplePosition: Long,
    val sampleCount: Int,
    val bitrateBps: Int,
    val flags: Int,
    val payload: ByteArray,
    val layer: Int = JlsProtocol.LAYER_HIGH
)

object JlsProtocol {
    const val VERSION = 2
    const val HEADER_BYTES = 48
    const val CODEC_OPUS = 1
    const val LAYER_HIGH = 0
    const val LAYER_LOW = 1

    const val FLAG_DISCONTINUITY = 1 shl 0
    const val FLAG_RECOVERY = 1 shl 1
    const val FLAG_END_OF_STREAM = 1 shl 2
    const val FLAG_SOURCE_SILENT = 1 shl 3

    private val MAGIC = byteArrayOf('J'.code.toByte(), 'L'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte())

    fun packetize(frame: EncodedAudioFrame, channels: Int = 2, frameMs: Int = 20): ByteArray {
        require(channels in 1..2)
        require(frameMs == 10 || frameMs == 20)
        require(frame.payload.size <= 1_500)

        val out = ByteBuffer.allocate(HEADER_BYTES + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(MAGIC)
        out.put(VERSION.toByte())
        out.put(HEADER_BYTES.toByte())
        out.put(CODEC_OPUS.toByte())
        out.put(frame.layer.toByte())
        out.putShort((frame.flags and 0xFFFF).toShort())
        out.put(channels.toByte())
        out.put(frameMs.toByte())
        out.putInt(frame.epoch)
        out.putInt(frame.sequence.toInt())
        out.putLong(frame.captureMonoNs)
        out.putLong(frame.samplePosition)
        out.putInt(frame.sampleCount)
        out.putInt(frame.payload.size)
        out.putInt(frame.bitrateBps)
        out.put(frame.payload)
        return out.array()
    }
}

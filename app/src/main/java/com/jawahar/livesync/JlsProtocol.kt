package com.jawahar.livesync

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class EncodedAudioFrame(
    val epoch: Long,
    val sequence: Long,
    val captureMonoNs: Long,
    val samplePosition: Long,
    val sampleCount: Int,
    val layerId: Int,
    val flags: Int,
    val payload: ByteArray,
    val encodeTimeUs: Long
)

object JlsProtocol {
    const val VERSION = 2
    const val CODEC_OPUS = 1
    const val MESSAGE_AUDIO = 1
    const val HEADER_BYTES = 52
    const val FLAG_DISCONTINUITY = 1
    const val FLAG_RECOVERY = 1 shl 1

    private val MAGIC = byteArrayOf(
        'J'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte()
    )

    fun encodeAudio(frame: EncodedAudioFrame): ByteArray {
        require(frame.payload.size <= 65_535) { "payload too large" }
        val out = ByteBuffer
            .allocate(HEADER_BYTES + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)

        out.put(MAGIC)
        out.put(VERSION.toByte())
        out.put(MESSAGE_AUDIO.toByte())
        out.put(CODEC_OPUS.toByte())
        out.put(frame.layerId.toByte())
        out.putShort(frame.flags.toShort())
        out.putShort(HEADER_BYTES.toShort())
        out.putLong(frame.epoch)
        out.putLong(frame.sequence)
        out.putLong(frame.captureMonoNs)
        out.putLong(frame.samplePosition)
        out.putInt(frame.sampleCount)
        out.putInt(frame.payload.size)
        out.put(frame.payload)
        return out.array()
    }
}

package com.jawahar.livesync

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import kotlin.system.measureNanoTime

data class EncodedOpus(
    val payload: ByteArray,
    val encodeTimeUs: Long
)

class OpusEncoderEngine(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val frameMs: Int = 20,
    val targetBitrateBps: Int = 144_000
) {
    private val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
        setBitrate(targetBitrateBps)
        setUseVBR(true)
        setUseConstrainedVBR(true)
        setUseDTX(false)
        setUseInbandFEC(false)
        setPacketLossPercent(0)
        setComplexity(7)
        setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC)
    }
    private val maxPacketBytes = 1275

    val samplesPerChannel: Int = sampleRate * frameMs / 1000

    fun encode(frame: PcmFrame): EncodedOpus {
        require(frame.sampleCount == samplesPerChannel)
        require(frame.pcm.size == samplesPerChannel * channels)

        val output = ByteArray(maxPacketBytes)
        var encodedBytes = 0
        val elapsedNs = measureNanoTime {
            encodedBytes = encoder.encode(
                frame.pcm,
                0,
                samplesPerChannel,
                output,
                0,
                output.size
            )
        }
        return EncodedOpus(output.copyOf(encodedBytes), elapsedNs / 1_000L)
    }
}

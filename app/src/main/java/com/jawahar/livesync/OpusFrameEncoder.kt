package com.jawahar.livesync

import org.concentus.OpusApplication
import org.concentus.OpusEncoder
import org.concentus.OpusSignal

class OpusFrameEncoder(val bitrateBps: Int = 144_000) {
    private val encoder = OpusEncoder(48_000, 2, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
        setBitrate(bitrateBps)
        setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC)
        setUseDTX(false)
        setUseInbandFEC(false)
        setPacketLossPercent(0)
        setUseVBR(true)
        setUseConstrainedVBR(true)
        setComplexity(8)
    }

    fun encode(frame: PcmFrame): ByteArray {
        val out = ByteArray(1275)
        val n = encoder.encode(frame.pcm, 0, frame.sampleCount, out, 0, out.size)
        return out.copyOf(n)
    }
}

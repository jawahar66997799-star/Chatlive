package com.jawahar.livesync

import kotlin.math.min

data class PcmFrame(
    val pcm: ShortArray,
    val captureMonoNs: Long,
    val samplePosition: Long,
    val sampleCount: Int
)

class PcmFrameAccumulator(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    frameMs: Int = 20
) {
    val frameSamplesPerChannel: Int = sampleRate * frameMs / 1000
    private val frame = ShortArray(frameSamplesPerChannel * channels)
    private var fillFrames = 0
    private var frameCaptureNs = 0L
    private var nextSamplePosition = 0L
    private val bytesPerAudioFrame = channels * 2

    fun push(
        input: ByteArray,
        length: Int,
        readCompletedNs: Long,
        consumer: (PcmFrame) -> Unit
    ) {
        val aligned = (length.coerceAtMost(input.size) / bytesPerAudioFrame) * bytesPerAudioFrame
        if (aligned <= 0) return

        val framesInRead = aligned / bytesPerAudioFrame
        val readDurationNs = framesInRead * 1_000_000_000L / sampleRate
        val readStartNs = readCompletedNs - readDurationNs

        var src = 0
        while (src < aligned) {
            if (fillFrames == 0) {
                val sourceFrameOffset = src / bytesPerAudioFrame
                frameCaptureNs = readStartNs + sourceFrameOffset * 1_000_000_000L / sampleRate
            }

            val availableFrames = (aligned - src) / bytesPerAudioFrame
            val copyFrames = min(frameSamplesPerChannel - fillFrames, availableFrames)

            var f = 0
            while (f < copyFrames) {
                var c = 0
                while (c < channels) {
                    val p = src + (f * channels + c) * 2
                    val lo = input[p].toInt() and 0xFF
                    val hi = input[p + 1].toInt()
                    frame[(fillFrames + f) * channels + c] = ((hi shl 8) or lo).toShort()
                    c++
                }
                f++
            }

            fillFrames += copyFrames
            src += copyFrames * bytesPerAudioFrame

            if (fillFrames == frameSamplesPerChannel) {
                consumer(
                    PcmFrame(
                        pcm = frame.copyOf(),
                        captureMonoNs = frameCaptureNs,
                        samplePosition = nextSamplePosition,
                        sampleCount = frameSamplesPerChannel
                    )
                )
                nextSamplePosition += frameSamplesPerChannel
                fillFrames = 0
            }
        }
    }

    fun discardPartial() {
        fillFrames = 0
    }

    fun advanceGapNanos(gapNs: Long) {
        if (gapNs <= 0L) return
        val skippedSamples = gapNs * sampleRate / 1_000_000_000L
        if (skippedSamples > 0L) {
            nextSamplePosition += skippedSamples
        }
    }

    fun samplePosition(): Long = nextSamplePosition
}

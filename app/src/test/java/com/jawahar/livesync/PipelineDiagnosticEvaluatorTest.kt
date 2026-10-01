package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipelineDiagnosticEvaluatorTest {
    private fun healthy() = PipelineDiagnosticInputs(
        running = true,
        projectionStopped = false,
        captureHealth = CaptureHealth.CAPTURE_OK,
        runningAgeMs = 5_000,
        lastReadAgeMs = 20,
        lastPcmFrameAgeMs = 20,
        lastEncodedAgeMs = 20,
        encoderQueueDepth = 1,
        uplinkQueueDepth = 1,
        webSocketQueueBytes = 0,
        relayState = RelayState.CONNECTED,
        lastSendAgeMs = 20,
        lastRelayControlAgeMs = 1_000
    )

    @Test
    fun healthyPipelineHasNoBrokenStage() {
        val result = PipelineDiagnosticEvaluator.evaluate(healthy())
        assertNull(result.brokenStage)
        assertEquals(PipelineStageState.OK, result.encoder)
        assertEquals(PipelineStageState.OK, result.relayAckState)
    }

    @Test
    fun networkFailureDoesNotPretendSourcePaused() {
        val result = PipelineDiagnosticEvaluator.evaluate(
            healthy().copy(
                captureHealth = CaptureHealth.SOURCE_PAUSED,
                relayState = RelayState.NETWORK_INTERRUPTED,
                uplinkQueueDepth = 3
            )
        )
        assertEquals(PipelineStage.WSS, result.brokenStage)
    }

    @Test
    fun encoderBackpressureIsExplicit() {
        val result = PipelineDiagnosticEvaluator.evaluate(
            healthy().copy(encoderQueueDepth = 7)
        )
        assertEquals(PipelineStage.ENCODER, result.brokenStage)
        assertEquals(PipelineStageState.BACKPRESSURE, result.encoder)
    }

    @Test
    fun relayControlStallIsExplicit() {
        val result = PipelineDiagnosticEvaluator.evaluate(
            healthy().copy(lastRelayControlAgeMs = 13_000)
        )
        assertEquals(PipelineStage.RELAY_ACK_STATE, result.brokenStage)
        assertEquals(PipelineStageState.STALLED, result.relayAckState)
    }
}

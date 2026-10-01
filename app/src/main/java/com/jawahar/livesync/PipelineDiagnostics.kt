package com.jawahar.livesync

enum class PipelineStage(val label: String) {
    CAPTURE("CAPTURE"),
    PCM("PCM"),
    ENCODER("ENCODER"),
    UPLINK_QUEUE("UPLINK_QUEUE"),
    WSS("WSS"),
    RELAY_ACK_STATE("RELAY_ACK/STATE")
}

enum class PipelineStageState {
    WAITING,
    OK,
    SILENT,
    RECOVERING,
    BACKPRESSURE,
    STALLED,
    DISCONNECTED,
    ERROR
}

data class PipelineDiagnostics(
    val capture: PipelineStageState = PipelineStageState.WAITING,
    val pcm: PipelineStageState = PipelineStageState.WAITING,
    val encoder: PipelineStageState = PipelineStageState.WAITING,
    val uplinkQueue: PipelineStageState = PipelineStageState.WAITING,
    val wss: PipelineStageState = PipelineStageState.WAITING,
    val relayAckState: PipelineStageState = PipelineStageState.WAITING,
    val brokenStage: PipelineStage? = null,
    val detail: String = "Pipeline waiting."
) {
    fun compactSummary(): String =
        "CAPTURE=$capture · PCM=$pcm · ENCODER=$encoder · UPLINK_QUEUE=$uplinkQueue · WSS=$wss · RELAY_ACK/STATE=$relayAckState"
}

data class PipelineDiagnosticInputs(
    val running: Boolean,
    val projectionStopped: Boolean,
    val captureHealth: CaptureHealth,
    val runningAgeMs: Long,
    val lastReadAgeMs: Long?,
    val lastPcmFrameAgeMs: Long?,
    val lastEncodedAgeMs: Long?,
    val encoderQueueDepth: Int,
    val uplinkQueueDepth: Int,
    val webSocketQueueBytes: Long,
    val relayState: RelayState,
    val lastSendAgeMs: Long?,
    val lastRelayControlAgeMs: Long?
)

object PipelineDiagnosticEvaluator {
    private const val READ_STALL_MS = 1_200L
    private const val ENCODER_STALL_MS = 750L
    private const val ENCODER_BACKPRESSURE_FRAMES = 6
    private const val UPLINK_BACKPRESSURE_FRAMES = 18
    private const val WSS_BACKPRESSURE_BYTES = 12L * 1024L
    private const val RELAY_CONTROL_STALL_MS = 12_000L

    fun evaluate(input: PipelineDiagnosticInputs): PipelineDiagnostics {
        if (!input.running) {
            return PipelineDiagnostics(detail = "Pipeline is not running.")
        }

        val capture = when {
            input.projectionStopped -> PipelineStageState.ERROR
            input.captureHealth == CaptureHealth.ERROR -> PipelineStageState.ERROR
            input.captureHealth == CaptureHealth.CAPTURE_STALLED -> PipelineStageState.STALLED
            input.captureHealth == CaptureHealth.RECOVERING -> PipelineStageState.RECOVERING
            input.captureHealth == CaptureHealth.CAPTURE_BLOCKED_SUSPECTED -> PipelineStageState.ERROR
            input.captureHealth == CaptureHealth.STARTING -> PipelineStageState.WAITING
            input.captureHealth == CaptureHealth.SOURCE_PAUSED -> PipelineStageState.WAITING
            else -> PipelineStageState.OK
        }

        val pcm = when {
            input.lastReadAgeMs == null && input.runningAgeMs < 1_500L -> PipelineStageState.WAITING
            input.lastReadAgeMs == null -> PipelineStageState.STALLED
            input.lastReadAgeMs > READ_STALL_MS -> PipelineStageState.STALLED
            input.captureHealth == CaptureHealth.CAPTURE_BLOCKED_SUSPECTED -> PipelineStageState.SILENT
            input.captureHealth == CaptureHealth.SOURCE_SILENT ||
                input.captureHealth == CaptureHealth.SOURCE_PAUSED -> PipelineStageState.SILENT
            else -> PipelineStageState.OK
        }

        val encoder = when {
            input.encoderQueueDepth >= ENCODER_BACKPRESSURE_FRAMES -> PipelineStageState.BACKPRESSURE
            input.encoderQueueDepth > 0 &&
                input.lastEncodedAgeMs != null &&
                input.lastEncodedAgeMs > ENCODER_STALL_MS -> PipelineStageState.STALLED
            input.lastEncodedAgeMs == null && input.runningAgeMs < 2_000L -> PipelineStageState.WAITING
            input.lastPcmFrameAgeMs != null &&
                input.lastPcmFrameAgeMs < READ_STALL_MS &&
                input.lastEncodedAgeMs == null -> PipelineStageState.STALLED
            else -> PipelineStageState.OK
        }

        val uplinkQueue = when {
            input.relayState == RelayState.DISABLED -> PipelineStageState.DISCONNECTED
            input.uplinkQueueDepth >= UPLINK_BACKPRESSURE_FRAMES ||
                input.webSocketQueueBytes >= WSS_BACKPRESSURE_BYTES -> PipelineStageState.BACKPRESSURE
            input.relayState == RelayState.NETWORK_INTERRUPTED -> PipelineStageState.RECOVERING
            else -> PipelineStageState.OK
        }

        val wss = when (input.relayState) {
            RelayState.CONNECTED -> PipelineStageState.OK
            RelayState.CONNECTING,
            RelayState.RECONNECTING -> PipelineStageState.RECOVERING
            RelayState.NETWORK_INTERRUPTED,
            RelayState.DISCONNECTED,
            RelayState.DISABLED -> PipelineStageState.DISCONNECTED
            RelayState.AUTH_FAILED,
            RelayState.ERROR -> PipelineStageState.ERROR
        }

        val relayAckState = when {
            input.relayState == RelayState.CONNECTED &&
                input.lastRelayControlAgeMs != null &&
                input.lastRelayControlAgeMs > RELAY_CONTROL_STALL_MS -> PipelineStageState.STALLED
            input.relayState == RelayState.CONNECTED -> PipelineStageState.OK
            input.relayState == RelayState.AUTH_FAILED ||
                input.relayState == RelayState.ERROR -> PipelineStageState.ERROR
            else -> PipelineStageState.WAITING
        }

        val states = listOf(
            PipelineStage.CAPTURE to capture,
            PipelineStage.PCM to pcm,
            PipelineStage.ENCODER to encoder,
            PipelineStage.UPLINK_QUEUE to uplinkQueue,
            PipelineStage.WSS to wss,
            PipelineStage.RELAY_ACK_STATE to relayAckState
        )
        val broken = states.firstOrNull { (_, state) ->
            state == PipelineStageState.ERROR ||
                state == PipelineStageState.STALLED ||
                state == PipelineStageState.BACKPRESSURE ||
                state == PipelineStageState.DISCONNECTED
        }?.first

        val detail = when (broken) {
            PipelineStage.CAPTURE -> when (input.captureHealth) {
                CaptureHealth.CAPTURE_BLOCKED_SUSPECTED ->
                    "CAPTURE failure: system media playback appears active but permitted YouTube/YT Music capture PCM is silent; target capture policy or source-side silence is suspected."
                CaptureHealth.CAPTURE_STALLED ->
                    "CAPTURE failure: AudioRecord is not delivering PCM and recorder recovery is active."
                CaptureHealth.PROJECTION_STOPPED ->
                    "CAPTURE failure: MediaProjection was stopped by Android."
                else -> "CAPTURE failure: ${input.captureHealth.name}."
            }
            PipelineStage.PCM ->
                "PCM failure: last AudioRecord delivery is ${input.lastReadAgeMs ?: -1} ms old."
            PipelineStage.ENCODER ->
                "ENCODER failure: queue=${input.encoderQueueDepth} frames, last encoded=${input.lastEncodedAgeMs ?: -1} ms ago."
            PipelineStage.UPLINK_QUEUE ->
                "UPLINK_QUEUE failure: queue=${input.uplinkQueueDepth} frames, WebSocket queued=${input.webSocketQueueBytes} bytes."
            PipelineStage.WSS ->
                "WSS failure: relay socket state is ${input.relayState.name}; reconnect is required."
            PipelineStage.RELAY_ACK_STATE ->
                "RELAY_ACK/STATE failure: no relay control response for ${input.lastRelayControlAgeMs ?: -1} ms."
            null -> when (input.captureHealth) {
                CaptureHealth.SOURCE_PAUSED -> "Pipeline healthy; target YouTube playback is not active."
                CaptureHealth.SOURCE_SILENT -> "Pipeline healthy; PCM is currently digital silence."
                else -> "Pipeline healthy through relay control response."
            }
        }

        return PipelineDiagnostics(
            capture = capture,
            pcm = pcm,
            encoder = encoder,
            uplinkQueue = uplinkQueue,
            wss = wss,
            relayAckState = relayAckState,
            brokenStage = broken,
            detail = detail
        )
    }
}

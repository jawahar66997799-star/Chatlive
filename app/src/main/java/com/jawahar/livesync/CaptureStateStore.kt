package com.jawahar.livesync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CaptureHealth {
    IDLE,
    STARTING,
    CAPTURE_OK,
    SOURCE_SILENT,
    SOURCE_PAUSED,
    CAPTURE_BLOCKED_SUSPECTED,
    CAPTURE_STALLED,
    PROJECTION_STOPPED,
    NETWORK_INTERRUPTED,
    RECONNECTING,
    RECOVERING,
    ERROR
}

enum class RelayState {
    DISABLED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NETWORK_INTERRUPTED,
    RECONNECTING,
    AUTH_FAILED,
    ERROR
}

data class CaptureSnapshot(
    val health: CaptureHealth = CaptureHealth.IDLE,
    val captureHealth: CaptureHealth = CaptureHealth.IDLE,
    val relayState: RelayState = RelayState.DISABLED,
    val pipeline: PipelineDiagnostics = PipelineDiagnostics(),
    val rmsDb: Double = -120.0,
    val peakDb: Double = -120.0,
    val secondsRunning: Long = 0,
    val activePlayback: Boolean = false,
    val framesCaptured: Long = 0,
    val readFaults: Long = 0,
    val packetsEncoded: Long = 0,
    val bytesUploaded: Long = 0,
    val bitrateBps: Int = 0,
    val encodeTimeUs: Long = 0,
    val relayRttMs: Long? = null,
    val reconnects: Int = 0,
    val encoderQueueDepth: Int = 0,
    val sendBufferDepth: Int = 0,
    val webSocketQueueBytes: Long = 0,
    val lastReadAgeMs: Long? = null,
    val lastPcmFrameAgeMs: Long? = null,
    val lastEncodedAgeMs: Long? = null,
    val lastSendAgeMs: Long? = null,
    val lastRelayControlAgeMs: Long? = null,
    val droppedFrames: Long = 0,
    val projectionStops: Int = 0,
    val thermalStatus: Int = 0,
    val batteryPct: Int = -1,
    val room: String = "",
    val guestUrl: String = "",
    val detail: String = "Ready",
    val logPath: String? = null
)

object CaptureStateStore {
    private val _state = MutableStateFlow(CaptureSnapshot())
    val state = _state.asStateFlow()
    fun update(snapshot: CaptureSnapshot) { _state.value = snapshot }
}

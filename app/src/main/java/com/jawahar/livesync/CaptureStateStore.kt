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
    RESTARTING,
    ERROR
}

data class CaptureSnapshot(
    val health: CaptureHealth = CaptureHealth.IDLE,
    val rmsDb: Double = -120.0,
    val peakDb: Double = -120.0,
    val secondsRunning: Long = 0,
    val activePlayback: Boolean = false,
    val framesRead: Long = 0,
    val droppedReads: Long = 0,
    val detail: String = "Ready",
    val logPath: String? = null
)

object CaptureStateStore {
    private val _state = MutableStateFlow(CaptureSnapshot())
    val state = _state.asStateFlow()
    fun update(snapshot: CaptureSnapshot) { _state.value = snapshot }
}

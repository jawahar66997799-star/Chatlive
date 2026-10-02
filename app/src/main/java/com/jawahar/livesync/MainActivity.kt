package com.jawahar.livesync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var captureStatus: TextView
    private lateinit var relayStatus: TextView
    private lateinit var meter: ProgressBar
    private lateinit var roomText: TextView
    private lateinit var guestUrlText: TextView
    private lateinit var stats: TextView
    private lateinit var detail: TextView
    private lateinit var startStop: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val micOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            if (micOk) {
                requestProjection()
            } else {
                renderError("RECORD_AUDIO permission is required for Android playback capture.")
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                renderError("Android screen/audio capture consent was not granted.")
                return@registerForActivityResult
            }

            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshRoomUi()

        if (CaptureStateStore.state.value.health == CaptureHealth.IDLE &&
            SessionState.consumeInterruptedSession(this)
        ) {
            CaptureStateStore.update(
                CaptureSnapshot(
                    health = CaptureHealth.RECOVERING,
                    captureHealth = CaptureHealth.RECOVERING,
                    relayState = RelayState.DISCONNECTED,
                    detail = "A previous capture session ended with the app process. Android requires fresh MediaProjection consent; tap START."
                )
            )
        }

        observeState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 44, 44, 44)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            text = "Jawahar Live Sync"
            textSize = 28f
        })

        root.addView(TextView(this).apply {
            text = "Host · YouTube playback capture + Opus uplink"
            textSize = 15f
            setPadding(0, 8, 0, 30)
        })

        captureStatus = TextView(this).apply {
            textSize = 21f
            text = "Capture: IDLE"
        }
        relayStatus = TextView(this).apply {
            textSize = 17f
            text = "Relay: DISABLED"
            setPadding(0, 6, 0, 12)
            setOnClickListener { showRelaySettings() }
            setOnLongClickListener {
                showRelaySettings()
                true
            }
        }

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        roomText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 18, 0, 4)
            text = "Room code: —"
        }
        guestUrlText = TextView(this).apply {
            textSize = 14f
            text = "Guest link: —"
        }

        stats = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 10)
        }

        detail = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 20)
        }

        startStop = Button(this).apply {
            text = "START"
            setOnClickListener {
                if (isCaptureLikelyRunning(CaptureStateStore.state.value)) {
                    stopCapture()
                } else {
                    ensurePermissionsThenStart()
                }
            }
        }

        val openYouTube = Button(this).apply {
            text = "OPEN YOUTUBE"
            setOnClickListener { openOfficialSourceApp("com.google.android.youtube", "YouTube") }
        }

        val openYouTubeMusic = Button(this).apply {
            text = "OPEN YOUTUBE MUSIC"
            setOnClickListener {
                openOfficialSourceApp("com.google.android.apps.youtube.music", "YouTube Music")
            }
        }

        val createRoom = Button(this).apply {
            text = "CREATE / CHANGE ROOM"
            setOnClickListener { showCreateRoomDialog() }
        }

        val relaySetup = Button(this).apply {
            text = "ADVANCED RELAY SETUP"
            setOnClickListener { showRelaySettings() }
        }

        val shareGuest = Button(this).apply {
            text = "SHARE GUEST LINK"
            setOnClickListener { shareGuestLink() }
        }

        val shareLog = Button(this).apply {
            text = "SHARE LOG"
            setOnClickListener { shareLatestLog() }
        }

        val battery = Button(this).apply {
            text = "BATTERY SETTINGS"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Throwable) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }

        val keepAwake = CheckBox(this).apply {
            text = "Keep this screen awake while visible"
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        val lockNote = TextView(this).apply {
            textSize = 12f
            text = "Android 15 QPR1+ stops MediaProjection when the device locks. Keep the phone unlocked during a live room unless real-device testing proves a supported alternative."
            setPadding(0, 20, 0, 0)
        }

        root.addView(captureStatus)
        root.addView(relayStatus)
        root.addView(meter, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
        root.addView(roomText, matchWrap())
        root.addView(guestUrlText, matchWrap())
        root.addView(stats, matchWrap())
        root.addView(detail, matchWrap())
        root.addView(startStop, matchWrap())
        root.addView(openYouTube, matchWrap())
        root.addView(openYouTubeMusic, matchWrap())
        root.addView(createRoom, matchWrap())
        root.addView(relaySetup, matchWrap())
        root.addView(shareGuest, matchWrap())
        root.addView(shareLog, matchWrap())
        root.addView(battery, matchWrap())
        root.addView(keepAwake, matchWrap())
        root.addView(lockNote, matchWrap())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun matchWrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureStateStore.state.collect { s ->
                    val captureLabel = if (s.captureHealth == CaptureHealth.SOURCE_PAUSED) "SOURCE_STATE_UNKNOWN" else s.captureHealth.name
                    captureStatus.text = "Capture: " + captureLabel
                    relayStatus.text = "Relay: " + s.relayState.name

                    val normalized = (((s.rmsDb + 60.0) / 60.0) * 100.0)
                        .roundToInt()
                        .coerceIn(0, 100)
                    meter.progress = normalized

                    val hostingActive =
                        isCaptureLikelyRunning(s) && s.relayState == RelayState.CONNECTED
                    val visibleRoom = s.room.ifBlank {
                        RelayConfig.loadPublicRoomCode(this@MainActivity).ifBlank { "—" }
                    }
                    roomText.text =
                        "Room code (" + (if (hostingActive) "ACTIVE" else "SAVED") + "): " + visibleRoom
                    guestUrlText.text = if (hostingActive && s.guestUrl.isNotBlank()) {
                        "Guest link (ACTIVE): " + s.guestUrl
                    } else {
                        "Guest link: available after Relay: CONNECTED"
                    }

                    stats.text = buildString {
                        append("Audio: ")
                        append(String.format(Locale.US, "%.1f", s.rmsDb))
                        append(" dBFS RMS · peak ")
                        append(String.format(Locale.US, "%.1f", s.peakDb))
                        append(" dBFS\n")
                        append("Bitrate: ")
                        append(if (s.bitrateBps > 0) "${s.bitrateBps / 1000} kbps" else "—")
                        append(" · RTT: ")
                        append(s.relayRttMs?.let { "${it} ms" } ?: "—")
                        append("\nPipeline: ")
                        append(s.pipeline.compactSummary())
                        append("\nBroken stage: ")
                        append(s.pipeline.brokenStage?.label ?: "NONE")
                        append("\nSystem media playback hint: ")
                        append(if (s.activePlayback) "ACTIVE" else "NOT OBSERVED")
                        append(" (not package-specific)")
                        append("\nQueues: encoder ${s.encoderQueueDepth} · uplink ${s.sendBufferDepth} · WSS ${s.webSocketQueueBytes} B")
                        append("\nAges ms: read ${s.lastReadAgeMs ?: -1} · PCM ${s.lastPcmFrameAgeMs ?: -1} · encoded ${s.lastEncodedAgeMs ?: -1} · sent ${s.lastSendAgeMs ?: -1} · relay ${s.lastRelayControlAgeMs ?: -1}")
                        append("\nReconnects: ${s.reconnects}")
                        append("\nCaptured: ${s.framesCaptured} frames · encoded: ${s.packetsEncoded} packets")
                        append("\nUploaded: ${s.bytesUploaded} B · dropped: ${s.droppedFrames}")
                        append("\nEncode: ${s.encodeTimeUs} µs · battery: ")
                        append(if (s.batteryPct >= 0) "${s.batteryPct}%" else "—")
                        append(" · thermal: ${s.thermalStatus}")
                    }

                    detail.text = s.detail
                    startStop.text = if (isCaptureLikelyRunning(s)) "STOP" else "START"
                }
            }
        }
    }

    private fun isCaptureLikelyRunning(s: CaptureSnapshot): Boolean {
        return s.captureHealth !in setOf(
            CaptureHealth.IDLE,
            CaptureHealth.ERROR,
            CaptureHealth.PROJECTION_STOPPED
        )
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            requestProjection()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        })
    }

    private fun refreshRoomUi() {
        val code = RelayConfig.loadPublicRoomCode(this)
        roomText.text = "Room code (SAVED): " + code.ifBlank { "—" }
        guestUrlText.text = "Guest link: available after Relay: CONNECTED"
    }

    private fun shareGuestLink() {
        val state = CaptureStateStore.state.value
        if (!isCaptureLikelyRunning(state) || state.relayState != RelayState.CONNECTED) {
            renderError(
                "This room is not active yet. Tap START and wait for Relay: CONNECTED " +
                    "before sharing the guest link."
            )
            return
        }
        val link = state.guestUrl
        if (link.isBlank()) {
            renderError("Relay is connected, but no active public guest link is available.")
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                },
                "Share Jawahar Live Sync room"
            )
        )
    }

    private fun shareLatestLog() {
        val path = CaptureStateStore.state.value.logPath ?: run {
            renderError("No host log exists yet.")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            renderError("The latest log file no longer exists.")
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share host log"
            )
        )
    }


    private fun showCreateRoomDialog() {
        val state = CaptureStateStore.state.value
        if (isCaptureLikelyRunning(state)) {
            AlertDialog.Builder(this)
                .setTitle("Stop hosting to change room")
                .setMessage(
                    "The active relay session keeps its current room code until hosting stops. " +
                        "Stop first so the saved code can never disagree with the active room."
                )
                .setPositiveButton("STOP HOSTING") { _, _ ->
                    stopCapture()
                    detail.text =
                        "Stopping the active room. Create or change the room after Capture becomes IDLE."
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        val current = RelayConfig.load(this)
        val input = EditText(this).apply {
            hint = "6-digit room code"
            setText(current.publicRoomCode.ifBlank { RelayConfig.generatePublicRoomCode() })
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            selectAll()
        }

        val randomButton = Button(this).apply {
            text = "GENERATE RANDOM CODE"
            setOnClickListener {
                input.setText(RelayConfig.generatePublicRoomCode())
                input.selectAll()
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 0, 36, 0)
            addView(input)
            addView(randomButton)
        }

        AlertDialog.Builder(this)
            .setTitle("Create room")
            .setMessage(
                "Use the generated 6-digit code when possible. The code is a shareable room address, " +
                    "not a private host credential. It becomes ACTIVE only after START reaches Relay: CONNECTED."
            )
            .setView(box)
            .setPositiveButton("CREATE ROOM") { _, _ ->
                val code = input.text.toString().filter { it.isDigit() }
                if (!RelayConfig.isValidPublicRoomCode(code)) {
                    renderError("Room code must be exactly 6 digits.")
                } else {
                    RelayConfig.savePublicRoomCode(this, code)
                    refreshRoomUi()
                    detail.text =
                        "Room $code SAVED. Tap START and wait for Relay: CONNECTED. " +
                            "Only then is the room ACTIVE and ready to share."
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showRelaySettings() {
        val current = RelayConfig.load(this)

        fun field(hintText: String, value: String, secret: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(value)
                isSingleLine = true
                if (secret) {
                    inputType =
                        InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }

        val relayInput = field(
            "wss://relay.example",
            current.relayBaseUrl
        )
        val roomInput = field(
            "Room ID (32+ chars)",
            current.room
        )
        val hostInput = field(
            "Host secret (32+ chars)",
            current.hostToken,
            secret = true
        )
        val guestTokenInput = field(
            "Guest token (22+ chars)",
            current.guestToken,
            secret = true
        )
        val guestBaseInput = field(
            "Guest base URL (optional)",
            current.guestBaseUrl
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 0, 36, 0)
            addView(relayInput)
            addView(roomInput)
            addView(hostInput)
            addView(guestTokenInput)
            addView(guestBaseInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Relay setup")
            .setMessage(
                "Enter the private host provisioning values. " +
                    "Stop and restart hosting after saving."
            )
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                val relayUrl = relayInput.text.toString().trim()
                val roomId = roomInput.text.toString().trim()
                val hostSecret = hostInput.text.toString().trim()
                val guestToken = guestTokenInput.text.toString().trim()
                val guestBase = guestBaseInput.text.toString().trim()

                if (!relayUrl.startsWith("wss://")) {
                    renderError("Relay URL must use wss://")
                } else if (roomId.length < 32) {
                    renderError("Room ID must be at least 32 characters.")
                } else if (hostSecret.length < 32) {
                    renderError("Host secret must be at least 32 characters.")
                } else if (guestToken.length < 22) {
                    renderError("Guest token must be at least 22 characters.")
                } else {
                    val saved = RelayConfig.saveOverride(
                        this,
                        relayUrl,
                        roomId,
                        hostSecret,
                        guestToken,
                        guestBase
                    )
                    if (saved) {
                        refreshRoomUi()
                        detail.text =
                            "Relay settings saved securely. Stop and START a fresh host session."
                    } else {
                        renderError(
                            "Could not securely save the private relay credentials. " +
                                "The previous working configuration was kept."
                        )
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun openOfficialSourceApp(packageName: String, displayName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch)
        } else {
            renderError("Official $displayName app was not found.")
        }
    }

    private fun renderError(message: String) {
        captureStatus.text = "Capture: ERROR"
        detail.text = message
    }
}

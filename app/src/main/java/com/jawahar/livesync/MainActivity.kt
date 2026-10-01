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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var relay: TextView
    private lateinit var room: TextView
    private lateinit var guestUrl: TextView
    private lateinit var meter: ProgressBar
    private lateinit var detail: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var shareLink: Button
    private lateinit var keepScreenAwake: CheckBox

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val micOk =
                grants[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

            if (micOk) {
                requestProjection()
            } else {
                renderError(
                    "RECORD_AUDIO is required for Android playback capture."
                )
            }
        }

    private val projectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (
                result.resultCode != Activity.RESULT_OK ||
                result.data == null
            ) {
                renderError(
                    "Screen/audio capture permission was not granted."
                )
                return@registerForActivityResult
            }

            val serviceIntent =
                Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(
                        CaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )
                    putExtra(
                        CaptureService.EXTRA_RESULT_DATA,
                        result.data
                    )
                }

            ContextCompat.startForegroundService(
                this,
                serviceIntent
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        observeState()

        if (
            CaptureStateStore.state.value.health == CaptureHealth.IDLE &&
            SessionState.consumeInterruptedSession(this)
        ) {
            status.text = "SESSION INTERRUPTED"
            detail.text =
                "Android ended the previous capture process. " +
                    "Tap START to grant a fresh MediaProjection session."
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 42, 48, 42)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Jawahar Live Sync"
            textSize = 28f
        }

        val subtitle = TextView(this).apply {
            text = "YouTube → live room"
            textSize = 15f
            setPadding(0, 8, 0, 28)
        }

        status = TextView(this).apply {
            text = "READY"
            textSize = 22f
        }

        relay = TextView(this).apply {
            textSize = 15f
            setPadding(0, 8, 0, 4)
            setOnLongClickListener {
                showRelaySettings()
                true
            }
        }

        room = TextView(this).apply {
            textSize = 15f
        }

        guestUrl = TextView(this).apply {
            textSize = 13f
            setPadding(0, 4, 0, 16)
        }

        meter = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }

        detail = TextView(this).apply {
            textSize = 15f
            setPadding(0, 18, 0, 20)
        }

        start = Button(this).apply {
            text = "START"
            setOnClickListener { ensurePermissionsThenStart() }
        }

        stop = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setOnClickListener { stopCapture() }
        }

        val openYouTube = Button(this).apply {
            text = "OPEN YOUTUBE"
            setOnClickListener {
                val launch =
                    packageManager.getLaunchIntentForPackage(
                        "com.google.android.youtube"
                    )

                if (launch != null) {
                    startActivity(launch)
                } else {
                    renderError(
                        "Official YouTube app was not found."
                    )
                }
            }
        }

        shareLink = Button(this).apply {
            text = "SHARE GUEST LINK"
            isEnabled = false
            setOnClickListener { shareGuestLink() }
        }

        val shareLog = Button(this).apply {
            text = "SHARE LOG"
            setOnClickListener { shareLatestLog() }
        }

        keepScreenAwake = CheckBox(this).apply {
            text = "Keep screen awake while hosting"
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    window.addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                } else {
                    window.clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                }
            }
        }

        val battery = Button(this).apply {
            text = "BATTERY SETTINGS"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    )
                )
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(relay)
        root.addView(room)
        root.addView(guestUrl)
        root.addView(
            meter,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                40
            )
        )
        root.addView(
            detail,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            start,
            fullWidthWrap()
        )
        root.addView(
            stop,
            fullWidthWrap()
        )
        root.addView(
            openYouTube,
            fullWidthWrap()
        )
        root.addView(
            shareLink,
            fullWidthWrap()
        )
        root.addView(
            shareLog,
            fullWidthWrap()
        )
        root.addView(keepScreenAwake)
        root.addView(
            battery,
            fullWidthWrap()
        )

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun fullWidthWrap(): ViewGroup.LayoutParams {
        return ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureStateStore.state.collect { snapshot ->
                    render(snapshot)
                }
            }
        }
    }

    private fun render(snapshot: CaptureSnapshot) {
        status.text = snapshot.health.name
        relay.text = "Relay: ${snapshot.relayState.name}"
        room.text =
            if (snapshot.room.isBlank()) {
                "Room: —"
            } else {
                "Room: ${snapshot.room}"
            }

        guestUrl.text =
            if (snapshot.guestUrl.isBlank()) {
                "Guest link: relay not configured"
            } else {
                "Guest link: ${snapshot.guestUrl}"
            }

        val normalized =
            (
                (
                    snapshot.rmsDb + 60.0
                    ) / 60.0 * 100.0
                ).roundToInt()
                .coerceIn(0, 100)

        meter.progress = normalized

        detail.text = buildString {
            append(
                "Capture: ${snapshot.captureHealth.name}\n"
            )
            append(
                "RMS: %.1f dBFS · Peak: %.1f dBFS\n"
                    .format(
                        snapshot.rmsDb,
                        snapshot.peakDb
                    )
            )
            append(
                "Bitrate: ${snapshot.bitrateBps / 1000} kbps · "
            )
            append(
                "Relay RTT: ${snapshot.relayRttMs?.let { "$it ms" } ?: "—"}\n"
            )
            append(
                "Packets: ${snapshot.packetsEncoded} · "
            )
            append(
                "Uploaded: ${snapshot.bytesUploaded / 1024} KiB\n"
            )
            append(
                "Reconnects: ${snapshot.reconnects} · "
            )
            append(
                "Queue: ${snapshot.sendBufferDepth} · "
            )
            append(
                "Dropped: ${snapshot.droppedFrames}\n"
            )
            append(
                "Battery: ${if (snapshot.batteryPct >= 0) "${snapshot.batteryPct}%" else "—"} · "
            )
            append(
                "Thermal: ${snapshot.thermalStatus}\n"
            )
            append(snapshot.detail)
        }

        val idle =
            snapshot.health == CaptureHealth.IDLE ||
                snapshot.health == CaptureHealth.ERROR ||
                snapshot.health == CaptureHealth.PROJECTION_STOPPED

        start.isEnabled = idle
        stop.isEnabled = !idle
        shareLink.isEnabled =
            snapshot.guestUrl.isNotBlank()
    }

    private fun ensurePermissionsThenStart() {
        val needed =
            mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing =
            needed.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            requestProjection()
        } else {
            permissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    private fun requestProjection() {
        val manager =
            getSystemService(MediaProjectionManager::class.java)

        val intent =
            if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig
                        .createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }

        projectionLauncher.launch(intent)
    }

    private fun stopCapture() {
        startService(
            Intent(
                this,
                CaptureService::class.java
            ).apply {
                action = CaptureService.ACTION_STOP
            }
        )
    }

    private fun shareGuestLink() {
        val url =
            CaptureStateStore.state.value.guestUrl

        if (url.isBlank()) {
            renderError(
                "Relay guest link is not configured."
            )
            return
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                },
                "Share guest link"
            )
        )
    }

    private fun shareLatestLog() {
        val path =
            CaptureStateStore.state.value.logPath
                ?: run {
                    renderError(
                        "No capture log exists yet."
                    )
                    return
                }

        val file = File(path)

        if (!file.exists()) {
            renderError(
                "Capture log file no longer exists."
            )
            return
        }

        val uri =
            FileProvider.getUriForFile(
                this,
                "${packageName}.files",
                file
            )

        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

        startActivity(
            Intent.createChooser(
                send,
                "Share capture log"
            )
        )
    }

    private fun showRelaySettings() {
        val current = RelayConfig.load(this)

        val relayInput = EditText(this).apply {
            hint = "wss://relay.example/ws/host"
            setText(current.relayBaseUrl)
        }

        val tokenInput = EditText(this).apply {
            hint = "Host token (optional)"
            setText(current.hostToken)
        }

        val guestInput = EditText(this).apply {
            hint = "https://relay.example (optional)"
            setText(current.guestBaseUrl)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
            addView(relayInput)
            addView(tokenInput)
            addView(guestInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Relay override")
            .setMessage(
                "Advanced setting. Normal use should be preconfigured."
            )
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                RelayConfig.saveOverride(
                    this,
                    relayInput.text.toString(),
                    tokenInput.text.toString(),
                    guestInput.text.toString()
                )
                val saved =
                    RelayConfig.load(this)
                relay.text =
                    "Relay: saved · restart hosting"
                room.text =
                    "Room: ${saved.room}"
                guestUrl.text =
                    "Guest link: ${saved.guestUrl().ifBlank { "—" }}"
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun renderError(message: String) {
        status.text = "ERROR"
        detail.text = message
    }
}

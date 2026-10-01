package com.jawahar.livesync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
    private lateinit var meter: ProgressBar
    private lateinit var detail: TextView
    private lateinit var startStop: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val micOk = grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micOk) requestProjection() else renderError("RECORD_AUDIO is required for Android playback capture.")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                renderError("Screen/audio capture permission was not granted.")
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
        observeState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "Jawahar Live Sync"
            textSize = 28f
        }
        val subtitle = TextView(this).apply {
            text = "Phase 0 · YouTube internal-audio capture proof"
            textSize = 15f
            setPadding(0, 10, 0, 36)
        }
        status = TextView(this).apply {
            textSize = 22f
            text = "READY"
        }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        detail = TextView(this).apply {
            textSize = 15f
            setPadding(0, 18, 0, 24)
        }
        startStop = Button(this).apply {
            text = "START SYNC TEST"
            setOnClickListener {
                val running = CaptureStateStore.state.value.health !in setOf(
                    CaptureHealth.IDLE, CaptureHealth.ERROR, CaptureHealth.PROJECTION_STOPPED
                )
                if (running) stopCapture() else ensurePermissionsThenStart()
            }
        }
        val openYouTube = Button(this).apply {
            text = "OPEN YOUTUBE"
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (launch != null) startActivity(launch) else renderError("Official YouTube app was not found.")
            }
        }
        val shareLog = Button(this).apply {
            text = "SHARE LATEST LOG"
            setOnClickListener { shareLatestLog() }
        }
        val battery = Button(this).apply {
            text = "BATTERY SETTINGS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(meter, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
        root.addView(detail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(startStop, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(openYouTube, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(shareLog, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(battery, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureStateStore.state.collect { s ->
                    status.text = s.health.name
                    val normalized = (((s.rmsDb + 60.0) / 60.0) * 100.0).roundToInt().coerceIn(0, 100)
                    meter.progress = normalized
                    detail.text = buildString {
                        append("RMS: %.1f dBFS\n".format(s.rmsDb))
                        append("Peak: %.1f dBFS\n".format(s.peakDb))
                        append("Active media player: ${s.activePlayback}\n")
                        append("Frames read: ${s.framesRead}\n")
                        append("Read faults: ${s.droppedReads}\n")
                        append("Running: ${s.secondsRunning}s\n")
                        append(s.detail)
                    }
                    startStop.text = if (s.health in setOf(
                            CaptureHealth.IDLE, CaptureHealth.ERROR, CaptureHealth.PROJECTION_STOPPED
                        )) "START SYNC TEST" else "STOP"
                }
            }
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestProjection() else permissionLauncher.launch(missing.toTypedArray())
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
        startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
    }

    private fun shareLatestLog() {
        val path = CaptureStateStore.state.value.logPath ?: run {
            renderError("No capture log exists yet.")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            renderError("Capture log file no longer exists.")
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share capture log"))
    }

    private fun renderError(message: String) {
        status.text = "ERROR"
        detail.text = message
    }
}

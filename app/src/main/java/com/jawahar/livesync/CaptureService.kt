package com.jawahar.livesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.jawahar.livesync.START"
        const val ACTION_STOP = "com.jawahar.livesync.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 48_000
    }

    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private lateinit var audioManager: AudioManager
    private var activePlayback = false
    private var projectionStopped = false
    private var logFile: File? = null
    private var logWriter: FileWriter? = null
    private var latest = CaptureSnapshot()

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            activePlayback = configs.orEmpty().any {
                val usage = it.audioAttributes.usage
                usage == AudioAttributes.USAGE_MEDIA ||
                    usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projectionStopped = true
            if (running.get()) {
                updateHealth(CaptureHealth.PROJECTION_STOPPED, "Android stopped the MediaProjection session.")
            }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
        createNotificationChannel()
    }

    override fun onDestroy() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        if (!projectionStopped) {
            try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
        }
        projection = null
        try { audioManager.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Exception) {}
        try { logWriter?.flush(); logWriter?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                updateHealth(CaptureHealth.IDLE, "Stopped by user.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (!running.compareAndSet(false, true)) return
        projectionStopped = false
        startAsForeground()
        updateHealth(CaptureHealth.STARTING, "Starting Android playback capture…")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultCode < 0 || resultData == null) {
            fail("Missing MediaProjection consent result.")
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            prepareLog()
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE / 5 * 4)

            recorder = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                fail("AudioRecord failed to initialize.")
                return
            }
            recorder?.startRecording()
            captureThread = Thread({ captureLoop() }, "PlaybackCapture").apply { start() }
        } catch (t: Throwable) {
            fail("Capture startup failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val record = recorder ?: return
        val buffer = ByteArray(8192)
        val startedNs = SystemClock.elapsedRealtimeNanos()
        var lastGoodReadNs = startedNs
        var silentSinceNs: Long? = null
        var framesRead = 0L
        var readFaults = 0L
        var lastReportNs = startedNs

        while (running.get()) {
            val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            val now = SystemClock.elapsedRealtimeNanos()

            if (n <= 0) {
                readFaults++
                if ((now - lastGoodReadNs) > 2_000_000_000L) {
                    publish(
                        CaptureHealth.CAPTURE_STALLED, -120.0, -120.0,
                        framesRead, readFaults, startedNs, "AudioRecord has not produced PCM for >2 seconds."
                    )
                }
                continue
            }

            lastGoodReadNs = now
            val sampleCount = n / 2
            var sumSq = 0.0
            var peak = 0
            var i = 0
            while (i + 1 < n) {
                val lo = buffer[i].toInt() and 0xFF
                val hi = buffer[i + 1].toInt()
                val s = (hi shl 8) or lo
                val signed = s.toShort().toInt()
                val a = kotlin.math.abs(signed)
                if (a > peak) peak = a
                sumSq += signed.toDouble() * signed.toDouble()
                i += 2
            }

            val rms = if (sampleCount > 0) sqrt(sumSq / sampleCount) else 0.0
            val rmsDb = linearToDb(rms / 32768.0)
            val peakDb = linearToDb(peak / 32768.0)
            framesRead += sampleCount / 2

            val audible = rmsDb > -75.0 || peakDb > -65.0
            if (audible) silentSinceNs = null else if (silentSinceNs == null) silentSinceNs = now
            val silentSecs = silentSinceNs?.let { (now - it) / 1_000_000_000.0 } ?: 0.0

            if ((now - lastReportNs) >= 1_000_000_000L) {
                val health = when {
                    audible -> CaptureHealth.CAPTURE_OK
                    silentSecs < 3.0 -> CaptureHealth.SOURCE_SILENT
                    activePlayback -> CaptureHealth.CAPTURE_BLOCKED_SUSPECTED
                    else -> CaptureHealth.SOURCE_PAUSED
                }
                val detail = when (health) {
                    CaptureHealth.CAPTURE_OK -> "Digital playback PCM detected."
                    CaptureHealth.CAPTURE_BLOCKED_SUSPECTED -> "A media player appears active, but captured PCM is digital silence."
                    CaptureHealth.SOURCE_PAUSED -> "No active media playback detected."
                    CaptureHealth.SOURCE_SILENT -> "PCM is currently silent; waiting before classifying."
                    else -> health.name
                }
                publish(health, rmsDb, peakDb, framesRead, readFaults, startedNs, detail)
                writeLogRow(health, rmsDb, peakDb, framesRead, readFaults, startedNs)
                lastReportNs = now
            }
        }
    }

    private fun publish(
        health: CaptureHealth,
        rmsDb: Double,
        peakDb: Double,
        framesRead: Long,
        readFaults: Long,
        startedNs: Long,
        detail: String
    ) {
        val seconds = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000_000L
        latest = CaptureSnapshot(
            health = health,
            rmsDb = rmsDb,
            peakDb = peakDb,
            secondsRunning = seconds,
            activePlayback = activePlayback,
            framesRead = framesRead,
            droppedReads = readFaults,
            detail = detail,
            logPath = logFile?.absolutePath
        )
        CaptureStateStore.update(latest)
        updateNotification(health, rmsDb)
    }

    private fun updateHealth(health: CaptureHealth, detail: String) {
        latest = latest.copy(health = health, detail = detail, logPath = logFile?.absolutePath)
        CaptureStateStore.update(latest)
        updateNotification(health, latest.rmsDb)
    }

    private fun fail(message: String) {
        updateHealth(CaptureHealth.ERROR, message)
        running.set(false)
        stopSelf()
    }

    private fun prepareLog() {
        val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        logFile = File(dir, "capture-$stamp.csv")
        logWriter = FileWriter(logFile!!, false)
        val ytVersion = packageVersion("com.google.android.youtube")
        logWriter?.appendLine("# device=${Build.MANUFACTURER} ${Build.MODEL}")
        logWriter?.appendLine("# android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT} build=${Build.DISPLAY}")
        logWriter?.appendLine("# youtube=$ytVersion")
        logWriter?.appendLine("elapsed_ms,health,rms_dbfs,peak_dbfs,active_playback,frames_read,read_faults")
        logWriter?.flush()
        updateHealth(CaptureHealth.STARTING, "Logging to ${logFile?.name}")
    }

    private fun writeLogRow(
        health: CaptureHealth,
        rmsDb: Double,
        peakDb: Double,
        framesRead: Long,
        readFaults: Long,
        startedNs: Long
    ) {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
        try {
            logWriter?.appendLine(
                "$elapsedMs,${health.name},${"%.2f".format(Locale.US, rmsDb)},${"%.2f".format(Locale.US, peakDb)},$activePlayback,$framesRead,$readFaults"
            )
            logWriter?.flush()
        } catch (_: Throwable) {}
    }

    private fun packageVersion(packageName: String): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "unknown"
        } catch (_: Throwable) {
            "not-visible-or-not-installed"
        }
    }

    private fun linearToDb(value: Double): Double {
        if (value <= 0.000001) return -120.0
        return max(-120.0, 20.0 * log10(value))
    }

    private fun startAsForeground() {
        val notification = buildNotification(CaptureHealth.STARTING, -120.0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(health: CaptureHealth, rms: Double) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(health, rms))
    }

    private fun buildNotification(health: CaptureHealth, rms: Double): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jawahar Live Sync")
            .setContentText("${health.name} · RMS ${"%.0f".format(rms)} dBFS")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Live audio capture",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}

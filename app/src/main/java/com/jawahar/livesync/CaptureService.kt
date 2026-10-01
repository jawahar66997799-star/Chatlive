package com.jawahar.livesync

import android.app.BatteryManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
        private const val CHANNELS = 2
        private const val FRAME_MS = 20
        private const val OPUS_BITRATE = 144_000
    }

    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var encoderThread: Thread? = null
    private val encodeQueue = ArrayBlockingQueue<PcmFrame>(12)

    private lateinit var audioManager: AudioManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager

    @Volatile
    private var activePlayback = false

    @Volatile
    private var projectionStopped = false

    @Volatile
    private var captureHealth = CaptureHealth.IDLE

    @Volatile
    private var relayStats = UplinkStats(state = RelayState.DISABLED)

    private var logFile: File? = null
    private var logWriter: FileWriter? = null
    private var latest = CaptureSnapshot()

    private lateinit var relayConfig: RelayConfig
    private var uplink: UplinkClient? = null
    private var opusEncoder: OpusFrameEncoder? = null
    private var epoch = 0L
    private var sequence = 0L

    @Volatile
    private var framesCaptured = 0L

    @Volatile
    private var readFaults = 0L

    @Volatile
    private var packetsEncoded = 0L

    @Volatile
    private var lastEncodeTimeUs = 0L

    @Volatile
    private var encoderDroppedFrames = 0L

    private var projectionStops = 0

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(
            configs: MutableList<AudioPlaybackConfiguration>?
        ) {
            activePlayback = configs.orEmpty().any {
                when (it.audioAttributes.usage) {
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    AudioAttributes.USAGE_UNKNOWN -> true
                    else -> false
                }
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projectionStopped = true
            projectionStops++
            SessionState.markActive(this@CaptureService, false)
            if (running.get()) {
                setCaptureHealth(
                    CaptureHealth.PROJECTION_STOPPED,
                    "Android stopped the MediaProjection session."
                )
            }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        batteryManager = getSystemService(BatteryManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        audioManager.registerAudioPlaybackCallback(
            playbackCallback,
            Handler(Looper.getMainLooper())
        )
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SessionState.markActive(this, false)
                setCaptureHealth(CaptureHealth.IDLE, "Stopped by user.")
                running.set(false)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)

        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null

        captureThread?.interrupt()
        encoderThread?.interrupt()
        captureThread = null
        encoderThread = null
        encodeQueue.clear()

        try {
            uplink?.stop()
        } catch (_: Throwable) {
        }
        uplink = null

        if (!projectionStopped) {
            try {
                projection?.unregisterCallback(projectionCallback)
            } catch (_: Throwable) {
            }
            try {
                projection?.stop()
            } catch (_: Throwable) {
            }
        }
        projection = null

        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Throwable) {
        }

        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Throwable) {
        }
        logWriter = null

        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (!running.compareAndSet(false, true)) return

        projectionStopped = false
        epoch = positiveEpoch()
        sequence = 0L
        captureHealth = CaptureHealth.STARTING
        relayConfig = RelayConfig.load(this)
        SessionState.markActive(this, true)

        startAsForeground()
        publishSnapshot("Starting Android playback capture.")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultCode < 0 || resultData == null) {
            fail("Missing MediaProjection consent result.")
            return
        }

        try {
            val projectionManager =
                getSystemService(MediaProjectionManager::class.java)

            projection =
                projectionManager.getMediaProjection(resultCode, resultData)

            projection?.registerCallback(
                projectionCallback,
                Handler(Looper.getMainLooper())
            )

            prepareLog()

            val config =
                AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE / 5 * CHANNELS * 2)

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

            opusEncoder = OpusFrameEncoder(OPUS_BITRATE)

            uplink = UplinkClient(
                context = this,
                config = relayConfig,
                epoch = epoch
            ) {
                relayStats = it
                publishSnapshot(it.detail)
            }.also { it.start() }

            recorder?.startRecording()

            encoderThread = Thread(
                { encoderLoop() },
                "JlsOpusEncoder"
            ).apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }

            captureThread = Thread(
                { captureLoop() },
                "PlaybackCapture"
            ).apply {
                start()
            }
        } catch (t: Throwable) {
            fail(
                "Capture startup failed: " +
                    "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val record = recorder ?: return
        val accumulator = PcmFrameAccumulator(
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            frameMs = FRAME_MS
        )

        val buffer = ByteArray(8192)
        val startedNs = SystemClock.elapsedRealtimeNanos()
        var lastGoodReadNs = startedNs
        var silentSinceNs: Long? = null
        var lastReportNs = startedNs

        while (running.get()) {
            val n = try {
                record.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_NON_BLOCKING
                )
            } catch (_: Throwable) {
                AudioRecord.ERROR_INVALID_OPERATION
            }

            val now = SystemClock.elapsedRealtimeNanos()

            if (n <= 0) {
                if (n < 0) readFaults++

                if (now - lastGoodReadNs > 2_000_000_000L) {
                    if (captureHealth != CaptureHealth.CAPTURE_STALLED) {
                        setCaptureHealth(
                            CaptureHealth.CAPTURE_STALLED,
                            "AudioRecord has not produced PCM for more than 2 seconds."
                        )
                    }
                }

                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            lastGoodReadNs = now

            val level = analyzePcm(buffer, n)
            val frameCount = n / (CHANNELS * 2)
            framesCaptured += frameCount

            val audible =
                level.first > -75.0 || level.second > -65.0

            if (audible) {
                silentSinceNs = null
            } else if (silentSinceNs == null) {
                silentSinceNs = now
            }

            accumulator.push(buffer, n, now) { frame ->
                if (!encodeQueue.offer(frame)) {
                    encodeQueue.poll()
                    if (!encodeQueue.offer(frame)) {
                        encoderDroppedFrames++
                    } else {
                        encoderDroppedFrames++
                    }
                }
            }

            val silentSeconds =
                silentSinceNs?.let {
                    (now - it) / 1_000_000_000.0
                } ?: 0.0

            if (now - lastReportNs >= 1_000_000_000L) {
                val nextHealth = classifyHealth(
                    audible = audible,
                    silentSeconds = silentSeconds
                )
                val detail = healthDetail(nextHealth)
                captureHealth = nextHealth

                publishSnapshot(
                    detail = detail,
                    rmsDb = level.first,
                    peakDb = level.second,
                    startedNs = startedNs
                )

                uplink?.sendHostState(nextHealth, detail)

                writeLogRow(
                    health = nextHealth,
                    rmsDb = level.first,
                    peakDb = level.second,
                    startedNs = startedNs
                )

                lastReportNs = now
            }
        }
    }

    private fun encoderLoop() {
        val encoder = opusEncoder ?: return

        while (running.get()) {
            val frame = try {
                encodeQueue.take()
            } catch (_: InterruptedException) {
                break
            }

            try {
                val started = SystemClock.elapsedRealtimeNanos()
                val payload = encoder.encode(frame)
                lastEncodeTimeUs =
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000L

                val encoded = EncodedAudioFrame(
                    epoch = epoch,
                    sequence = sequence++,
                    captureMonoNs = frame.captureMonoNs,
                    samplePosition = frame.samplePosition,
                    sampleCount = frame.sampleCount,
                    layerId = 0,
                    flags = 0,
                    payload = payload,
                    encodeTimeUs = lastEncodeTimeUs
                )

                packetsEncoded++
                uplink?.enqueue(encoded)
            } catch (t: Throwable) {
                encoderDroppedFrames++
                if (running.get()) {
                    setCaptureHealth(
                        CaptureHealth.ERROR,
                        "Opus encode failed: " +
                            "${t.javaClass.simpleName}: ${t.message}"
                    )
                }
            }
        }
    }

    private fun classifyHealth(
        audible: Boolean,
        silentSeconds: Double
    ): CaptureHealth {
        if (audible) {
            return if (
                captureHealth == CaptureHealth.CAPTURE_STALLED ||
                captureHealth == CaptureHealth.CAPTURE_BLOCKED_SUSPECTED
            ) {
                CaptureHealth.RECOVERING
            } else {
                CaptureHealth.CAPTURE_OK
            }
        }

        if (silentSeconds < 3.0) {
            return CaptureHealth.SOURCE_SILENT
        }

        return if (activePlayback) {
            CaptureHealth.CAPTURE_BLOCKED_SUSPECTED
        } else {
            CaptureHealth.SOURCE_PAUSED
        }
    }

    private fun healthDetail(health: CaptureHealth): String {
        return when (health) {
            CaptureHealth.CAPTURE_OK ->
                "Digital playback PCM detected."

            CaptureHealth.SOURCE_SILENT ->
                "PCM is silent; waiting before classifying."

            CaptureHealth.SOURCE_PAUSED ->
                "No active media playback detected."

            CaptureHealth.CAPTURE_BLOCKED_SUSPECTED ->
                "Media playback appears active but captured PCM remains digital silence."

            CaptureHealth.CAPTURE_STALLED ->
                "AudioRecord is not producing PCM."

            CaptureHealth.RECOVERING ->
                "PCM recovered after a capture fault."

            CaptureHealth.PROJECTION_STOPPED ->
                "MediaProjection stopped."

            else -> health.name
        }
    }

    private fun analyzePcm(
        buffer: ByteArray,
        length: Int
    ): Pair<Double, Double> {
        val sampleCount = length / 2
        var sumSq = 0.0
        var peak = 0
        var i = 0

        while (i + 1 < length) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val signed = ((hi shl 8) or lo).toShort().toInt()
            val amplitude = abs(signed)
            if (amplitude > peak) peak = amplitude
            sumSq += signed.toDouble() * signed.toDouble()
            i += 2
        }

        val rms =
            if (sampleCount > 0) {
                sqrt(sumSq / sampleCount)
            } else {
                0.0
            }

        return Pair(
            linearToDb(rms / 32768.0),
            linearToDb(peak / 32768.0)
        )
    }

    private fun setCaptureHealth(
        health: CaptureHealth,
        detail: String
    ) {
        captureHealth = health
        publishSnapshot(detail)
        uplink?.sendHostState(health, detail)
    }

    private fun publishSnapshot(
        detail: String,
        rmsDb: Double = latest.rmsDb,
        peakDb: Double = latest.peakDb,
        startedNs: Long? = null
    ) {
        val seconds =
            if (startedNs != null) {
                (
                    SystemClock.elapsedRealtimeNanos() -
                        startedNs
                    ) / 1_000_000_000L
            } else {
                latest.secondsRunning
            }

        val effectiveHealth = when (relayStats.state) {
            RelayState.NETWORK_INTERRUPTED ->
                CaptureHealth.NETWORK_INTERRUPTED

            RelayState.RECONNECTING,
            RelayState.CONNECTING ->
                if (
                    captureHealth != CaptureHealth.IDLE &&
                    captureHealth != CaptureHealth.STARTING
                ) {
                    CaptureHealth.RECONNECTING
                } else {
                    captureHealth
                }

            else -> captureHealth
        }

        val battery = try {
            batteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            )
        } catch (_: Throwable) {
            -1
        }

        val thermal =
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    powerManager.currentThermalStatus
                } catch (_: Throwable) {
                    0
                }
            } else {
                0
            }

        latest = CaptureSnapshot(
            health = effectiveHealth,
            captureHealth = captureHealth,
            relayState = relayStats.state,
            rmsDb = rmsDb,
            peakDb = peakDb,
            secondsRunning = seconds,
            activePlayback = activePlayback,
            framesCaptured = framesCaptured,
            readFaults = readFaults,
            packetsEncoded = packetsEncoded,
            bytesUploaded = relayStats.bytesAccepted,
            bitrateBps = OPUS_BITRATE,
            encodeTimeUs = lastEncodeTimeUs,
            relayRttMs = relayStats.rttMs,
            reconnects = relayStats.reconnects,
            sendBufferDepth = relayStats.queueDepth,
            droppedFrames =
                relayStats.droppedFrames + encoderDroppedFrames,
            projectionStops = projectionStops,
            thermalStatus = thermal,
            batteryPct = battery,
            room = relayConfigOrEmpty().room,
            guestUrl = relayConfigOrEmpty().guestUrl(),
            detail = detail.ifBlank {
                relayStats.detail.ifBlank {
                    healthDetail(captureHealth)
                }
            },
            logPath = logFile?.absolutePath
        )

        CaptureStateStore.update(latest)
        updateNotification(effectiveHealth, rmsDb)
    }

    private fun relayConfigOrEmpty(): RelayConfig {
        return if (::relayConfig.isInitialized) {
            relayConfig
        } else {
            RelayConfig("", "", "", "")
        }
    }

    private fun fail(message: String) {
        captureHealth = CaptureHealth.ERROR
        publishSnapshot(message)
        SessionState.markActive(this, false)
        running.set(false)
        stopSelf()
    }

    private fun prepareLog() {
        val dir =
            File(getExternalFilesDir(null), "logs")
                .apply { mkdirs() }

        val stamp =
            SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.US
            ).format(Date())

        logFile = File(dir, "capture-$stamp.csv")
        logWriter = FileWriter(logFile!!, false)

        logWriter?.appendLine(
            "# device=${Build.MANUFACTURER} ${Build.MODEL}"
        )
        logWriter?.appendLine(
            "# android=${Build.VERSION.RELEASE} " +
                "api=${Build.VERSION.SDK_INT} " +
                "build=${Build.DISPLAY}"
        )
        logWriter?.appendLine(
            "# youtube=${packageVersion("com.google.android.youtube")}"
        )
        logWriter?.appendLine(
            "# youtube_music=" +
                packageVersion(
                    "com.google.android.apps.youtube.music"
                )
        )
        logWriter?.appendLine(
            "# stream=opus 48000Hz stereo 20ms 144000bps " +
                "dtx=false inband_fec=false"
        )
        logWriter?.appendLine(
            "# room=${relayConfig.room} relay_configured=${relayConfig.enabled}"
        )
        logWriter?.appendLine(
            "elapsed_ms,capture_health,relay_state," +
                "rms_dbfs,peak_dbfs,active_playback," +
                "frames_captured,read_faults,packets_encoded," +
                "bytes_uploaded,bitrate_bps,encode_us,relay_rtt_ms," +
                "reconnects,send_buffer_depth,dropped_frames," +
                "projection_stops,thermal_status,battery_pct"
        )
        logWriter?.flush()
    }

    private fun writeLogRow(
        health: CaptureHealth,
        rmsDb: Double,
        peakDb: Double,
        startedNs: Long
    ) {
        val elapsedMs =
            (
                SystemClock.elapsedRealtimeNanos() -
                    startedNs
                ) / 1_000_000L

        try {
            logWriter?.appendLine(
                buildString {
                    append(elapsedMs)
                    append(",")
                    append(health.name)
                    append(",")
                    append(relayStats.state.name)
                    append(",")
                    append("%.2f".format(Locale.US, rmsDb))
                    append(",")
                    append("%.2f".format(Locale.US, peakDb))
                    append(",")
                    append(activePlayback)
                    append(",")
                    append(framesCaptured)
                    append(",")
                    append(readFaults)
                    append(",")
                    append(packetsEncoded)
                    append(",")
                    append(relayStats.bytesAccepted)
                    append(",")
                    append(OPUS_BITRATE)
                    append(",")
                    append(lastEncodeTimeUs)
                    append(",")
                    append(relayStats.rttMs ?: -1)
                    append(",")
                    append(relayStats.reconnects)
                    append(",")
                    append(relayStats.queueDepth)
                    append(",")
                    append(
                        relayStats.droppedFrames +
                            encoderDroppedFrames
                    )
                    append(",")
                    append(projectionStops)
                    append(",")
                    append(
                        if (Build.VERSION.SDK_INT >= 29) {
                            powerManager.currentThermalStatus
                        } else {
                            0
                        }
                    )
                    append(",")
                    append(
                        batteryManager.getIntProperty(
                            BatteryManager.BATTERY_PROPERTY_CAPACITY
                        )
                    )
                }
            )
            logWriter?.flush()
        } catch (_: Throwable) {
        }
    }

    private fun packageVersion(packageName: String): String {
        return try {
            val info =
                if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        packageName,
                        0
                    )
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
        val notification =
            buildNotification(
                CaptureHealth.STARTING,
                -120.0
            )

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun updateNotification(
        health: CaptureHealth,
        rms: Double
    ) {
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                buildNotification(health, rms)
            )
    }

    private fun buildNotification(
        health: CaptureHealth,
        rms: Double
    ): Notification {
        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jawahar Live Sync")
            .setContentText(
                "${health.name} · " +
                    "RMS ${"%.0f".format(rms)} dBFS"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Live audio capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
    }

    private fun positiveEpoch(): Long {
        var value = SecureRandom().nextLong()
        if (value == Long.MIN_VALUE) value = 1L
        return abs(value)
    }
}

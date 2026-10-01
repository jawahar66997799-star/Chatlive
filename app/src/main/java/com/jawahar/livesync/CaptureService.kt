package com.jawahar.livesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        private const val TARGET_BITRATE = 144_000
        private val SOURCE_PACKAGES = listOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music"
        )
    }

    private val running = AtomicBoolean(false)
    private val recoveryRequested = AtomicBoolean(false)
    private val lastGoodReadNs = AtomicLong(0)
    private val encoderQueue = ArrayBlockingQueue<PcmFrame>(12)
    private val pendingDiscontinuity = AtomicBoolean(false)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "JlsCaptureWatchdog").apply { isDaemon = true }
    }

    private var projection: MediaProjection? = null
    @Volatile private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var encoderThread: Thread? = null
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var activePlayback = false
    @Volatile private var projectionStopped = false
    @Volatile private var sourceHealth = CaptureHealth.IDLE
    @Volatile private var relayState = RelayState.DISABLED
    @Volatile private var relayDetail = ""
    @Volatile private var rmsDb = -120.0
    @Volatile private var peakDb = -120.0
    @Volatile private var framesCaptured = 0L
    @Volatile private var readFaults = 0L
    @Volatile private var packetsEncoded = 0L
    @Volatile private var bytesUploaded = 0L
    @Volatile private var bitrateBps = 0
    @Volatile private var encodeTimeUs = 0L
    @Volatile private var relayRttMs: Long? = null
    @Volatile private var reconnects = 0
    @Volatile private var sendBufferDepth = 0
    @Volatile private var droppedFrames = 0L
    @Volatile private var projectionStops = 0
    @Volatile private var thermalStatus = 0
    @Volatile private var recoveryUntilNs = 0L

    private var startedNs = 0L
    private var epoch = 0L
    private val sequence = AtomicLong(0)
    private lateinit var relayConfig: RelayConfig
    private var captureSourceUids: List<Int> = emptyList()
    private var relayClient: RelayClient? = null
    private var logFile: File? = null
    private var logWriter: FileWriter? = null
    private val logLock = Any()

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
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
            sourceHealth = CaptureHealth.PROJECTION_STOPPED
            publishSnapshot("Android stopped MediaProjection. On Android 15 QPR1+ this also happens when the device locks.")
            running.set(false)
            stopSelf()
        }
    }

    private val thermalListener = object : PowerManager.OnThermalStatusChangedListener {
        override fun onThermalStatusChanged(status: Int) {
            thermalStatus = status
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        thermalStatus = powerManager.currentThermalStatus
        audioManager.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
        powerManager.addThermalStatusListener(mainExecutor, thermalListener)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                sourceHealth = CaptureHealth.IDLE
                publishSnapshot("Stopped by user.")
                SessionState.markActive(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
            else -> {
                if (!running.get()) {
                    sourceHealth = CaptureHealth.ERROR
                    publishSnapshot("Capture service cannot restore MediaProjection after process recreation. Tap START to grant fresh consent.")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (!running.compareAndSet(false, true)) return

        startAsForeground()
        sourceHealth = CaptureHealth.STARTING
        projectionStopped = false
        startedNs = SystemClock.elapsedRealtimeNanos()
        lastGoodReadNs.set(startedNs)
        SessionState.markActive(this, true)
        acquireWakeLock()
        publishSnapshot("Starting playback capture.")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultCode < 0 || resultData == null) {
            fail("Missing or invalid MediaProjection consent result.")
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            relayConfig = RelayConfig.load(this)
            captureSourceUids = SOURCE_PACKAGES.mapNotNull(::packageUid).distinct()
            if (captureSourceUids.isEmpty()) {
                fail("Official YouTube or YouTube Music is not installed or visible to this app.")
                return
            }
            epoch = SecureRandom().nextLong().and(Long.MAX_VALUE).let { if (it == 0L) 1L else it }
            sequence.set(0)
            prepareLog()
            startRelay()

            if (!createAndStartRecorder()) {
                fail("AudioRecord failed to initialize.")
                return
            }

            startEncoderThread()
            captureThread = Thread({ captureLoop() }, "PlaybackCapture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            watchdog.scheduleAtFixedRate({ watchdogTick() }, 1, 1, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            fail("Capture startup failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    private fun startRelay() {
        if (!relayConfig.enabled) {
            relayState = RelayState.DISABLED
            relayDetail = "Relay URL is not configured; capture and Opus encoding remain active."
            return
        }

        relayClient = RelayClient(
            context = this,
            config = relayConfig,
            epoch = epoch,
            sampleRate = SAMPLE_RATE,
            channels = CHANNELS,
            frameMs = FRAME_MS,
            bitrateBps = TARGET_BITRATE,
            listener = object : RelayClient.Listener {
                override fun onRelayState(state: RelayState, detail: String) {
                    val old = relayState
                    relayState = state
                    relayDetail = detail
                    if (state == RelayState.CONNECTED && old != RelayState.CONNECTED && reconnects > 0) {
                        recoveryUntilNs = SystemClock.elapsedRealtimeNanos() + 1_000_000_000L
                        pendingDiscontinuity.set(true)
                    }
                    publishSnapshot(detail)
                }

                override fun onRelayMetrics(metrics: RelayMetrics) {
                    bytesUploaded = metrics.bytesUploaded
                    relayRttMs = metrics.relayRttMs
                    reconnects = metrics.reconnects
                    sendBufferDepth = metrics.sendBufferDepth
                    droppedFrames = max(droppedFrames, metrics.droppedFrames)
                }
            }
        ).also { it.start() }
    }

    private fun createAndStartRecorder(): Boolean {
        val p = projection ?: return false
        val captureBuilder = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        captureSourceUids.forEach { captureBuilder.addMatchingUid(it) }
        val config = captureBuilder.build()

        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val frameBytes = SAMPLE_RATE * CHANNELS * 2 * FRAME_MS / 1000
        val bufferBytes = max(minBuffer * 2, frameBytes * 8)

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }

        recorder = record
        lastGoodReadNs.set(SystemClock.elapsedRealtimeNanos())
        return true
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(16_384)
        val accumulator = PcmFrameAccumulator(SAMPLE_RATE, CHANNELS, FRAME_MS)
        var silentSinceNs: Long? = null
        var lastReportNs = startedNs

        while (running.get()) {
            if (recoveryRequested.get()) {
                if (!recoverRecorder(accumulator)) return
            }

            val record = recorder ?: break
            val n = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (_: Throwable) {
                readFaults++
                recoveryRequested.set(true)
                continue
            }
            val now = SystemClock.elapsedRealtimeNanos()

            if (!running.get()) break

            if (n <= 0) {
                readFaults++
                if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) {
                    recoveryRequested.set(true)
                }
                continue
            }

            lastGoodReadNs.set(now)
            framesCaptured += n / (CHANNELS * 2)

            val level = analyzePcm(buffer, n)
            rmsDb = level.first
            peakDb = level.second

            val audible = rmsDb > -75.0 || peakDb > -65.0
            if (audible) {
                silentSinceNs = null
            } else if (silentSinceNs == null) {
                silentSinceNs = now
            }
            val silentSecs = silentSinceNs?.let { (now - it) / 1_000_000_000.0 } ?: 0.0

            sourceHealth = when {
                audible -> CaptureHealth.CAPTURE_OK
                silentSecs < 3.0 -> CaptureHealth.SOURCE_SILENT
                activePlayback -> CaptureHealth.CAPTURE_BLOCKED_SUSPECTED
                else -> CaptureHealth.SOURCE_PAUSED
            }

            accumulator.push(buffer, n, now) { frame ->
                if (!encoderQueue.offer(frame)) {
                    encoderQueue.poll()
                    droppedFrames++
                    pendingDiscontinuity.set(true)
                    if (!encoderQueue.offer(frame)) droppedFrames++
                }
            }

            if (now - lastReportNs >= 1_000_000_000L) {
                val detail = when (sourceHealth) {
                    CaptureHealth.CAPTURE_OK -> "Digital playback PCM verified."
                    CaptureHealth.SOURCE_SILENT -> "Captured PCM is momentarily silent."
                    CaptureHealth.SOURCE_PAUSED -> "No active media playback detected."
                    CaptureHealth.CAPTURE_BLOCKED_SUSPECTED -> "Media playback appears active, but captured PCM remains digital silence."
                    else -> sourceHealth.name
                }
                val snapshot = publishSnapshot(detail)
                writeLogRow(snapshot)
                relayClient?.sendHostState(snapshot)
                lastReportNs = now
            }
        }
    }

    private fun startEncoderThread() {
        encoderThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val encoder = try {
                OpusEncoderEngine(
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS,
                    frameMs = FRAME_MS,
                    targetBitrateBps = TARGET_BITRATE
                )
            } catch (t: Throwable) {
                fail("Opus encoder initialization failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
                return@Thread
            }

            var bitrateWindowBytes = 0L
            var bitrateWindowStart = SystemClock.elapsedRealtimeNanos()

            while (running.get() || encoderQueue.isNotEmpty()) {
                val frame = try {
                    encoderQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@Thread
                } ?: continue

                try {
                    val encoded = encoder.encode(frame)
                    encodeTimeUs = encoded.encodeTimeUs
                    packetsEncoded++
                    bitrateWindowBytes += encoded.payload.size

                    val now = SystemClock.elapsedRealtimeNanos()
                    val dt = now - bitrateWindowStart
                    if (dt >= 1_000_000_000L) {
                        bitrateBps = ((bitrateWindowBytes * 8L * 1_000_000_000L) / dt).toInt()
                        bitrateWindowBytes = 0
                        bitrateWindowStart = now
                    }

                    var flags = 0
                    if (pendingDiscontinuity.getAndSet(false) ||
                        effectiveHealth() == CaptureHealth.RECOVERING
                    ) {
                        flags = flags or JlsProtocol.FLAG_DISCONTINUITY
                    }

                    val seq = sequence.incrementAndGet()
                    val packet = JlsProtocol.packetize(
                        EncodedAudioFrame(
                            epoch = epoch,
                            sequence = seq,
                            captureMonoNs = frame.captureMonoNs,
                            samplePosition = frame.samplePosition,
                            sampleCount = frame.sampleCount,
                            flags = flags,
                            payload = encoded.payload
                        ),
                        channels = CHANNELS
                    )
                    relayClient?.offer(packet, frame.captureMonoNs, seq)
                } catch (_: Throwable) {
                    droppedFrames++
                    pendingDiscontinuity.set(true)
                }
            }
        }, "OpusEncoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun watchdogTick() {
        if (!running.get() || projectionStopped) return
        val now = SystemClock.elapsedRealtimeNanos()
        val last = lastGoodReadNs.get()
        if (last > 0 && now - last > 2_000_000_000L && recoveryRequested.compareAndSet(false, true)) {
            sourceHealth = CaptureHealth.CAPTURE_STALLED
            publishSnapshot("AudioRecord produced no PCM for more than 2 seconds; restarting the recorder.")
            try {
                recorder?.stop()
            } catch (_: Throwable) {
            }
        }
    }

    private fun recoverRecorder(accumulator: PcmFrameAccumulator): Boolean {
        sourceHealth = CaptureHealth.RECOVERING
        publishSnapshot("Recovering AudioRecord.")
        encoderQueue.clear()
        accumulator.discardPartial()
        pendingDiscontinuity.set(true)

        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null

        for (attempt in 0 until 5) {
            if (!running.get() || projectionStopped) return false
            if (attempt > 0) {
                try {
                    Thread.sleep(RetryPolicy.baseDelayMs(attempt - 1).coerceAtMost(2_000))
                } catch (_: InterruptedException) {
                    return false
                }
            }
            try {
                if (createAndStartRecorder()) {
                    recoveryRequested.set(false)
                    recoveryUntilNs = SystemClock.elapsedRealtimeNanos() + 1_000_000_000L
                    sourceHealth = CaptureHealth.RECOVERING
                    publishSnapshot("AudioRecord recovered.")
                    return true
                }
            } catch (_: Throwable) {
            }
        }

        fail("AudioRecord recovery failed after 5 attempts.")
        return false
    }

    private fun analyzePcm(buffer: ByteArray, n: Int): Pair<Double, Double> {
        val usable = (n.coerceAtMost(buffer.size) / 2) * 2
        val samples = usable / 2
        if (samples == 0) return -120.0 to -120.0

        var sumSq = 0.0
        var peak = 0
        var i = 0
        while (i + 1 < usable) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val signed = ((hi shl 8) or lo).toShort().toInt()
            val a = abs(signed)
            if (a > peak) peak = a
            sumSq += signed.toDouble() * signed.toDouble()
            i += 2
        }

        val rms = sqrt(sumSq / samples)
        return linearToDb(rms / 32768.0) to linearToDb(peak / 32768.0)
    }

    private fun effectiveHealth(): CaptureHealth {
        if (projectionStopped) return CaptureHealth.PROJECTION_STOPPED
        if (recoveryRequested.get()) return CaptureHealth.RECOVERING
        if (SystemClock.elapsedRealtimeNanos() < recoveryUntilNs) return CaptureHealth.RECOVERING

        return when (relayState) {
            RelayState.NETWORK_INTERRUPTED -> CaptureHealth.NETWORK_INTERRUPTED
            RelayState.CONNECTING,
            RelayState.RECONNECTING -> CaptureHealth.RECONNECTING
            RelayState.AUTH_FAILED,
            RelayState.ERROR -> CaptureHealth.ERROR
            else -> sourceHealth
        }
    }

    private fun publishSnapshot(detailOverride: String = ""): CaptureSnapshot {
        val detail = if (relayState in setOf(
                RelayState.NETWORK_INTERRUPTED,
                RelayState.RECONNECTING,
                RelayState.AUTH_FAILED,
                RelayState.ERROR
            )
        ) {
            relayDetail.ifBlank { detailOverride }
        } else {
            detailOverride.ifBlank { relayDetail }
        }

        val snapshot = CaptureSnapshot(
            health = effectiveHealth(),
            captureHealth = sourceHealth,
            relayState = relayState,
            rmsDb = rmsDb,
            peakDb = peakDb,
            secondsRunning = if (startedNs == 0L) 0 else
                (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0) / 1_000_000_000L,
            activePlayback = activePlayback,
            framesCaptured = framesCaptured,
            readFaults = readFaults,
            packetsEncoded = packetsEncoded,
            bytesUploaded = bytesUploaded,
            bitrateBps = bitrateBps,
            encodeTimeUs = encodeTimeUs,
            relayRttMs = relayRttMs,
            reconnects = reconnects,
            sendBufferDepth = sendBufferDepth,
            droppedFrames = droppedFrames,
            projectionStops = projectionStops,
            thermalStatus = thermalStatus,
            batteryPct = batteryPercent(),
            room = if (::relayConfig.isInitialized) relayConfig.room else "",
            guestUrl = if (::relayConfig.isInitialized) relayConfig.guestUrl() else "",
            detail = detail,
            logPath = logFile?.absolutePath
        )
        CaptureStateStore.update(snapshot)
        updateNotification(snapshot.health, snapshot.rmsDb, snapshot.relayState)
        return snapshot
    }

    private fun fail(message: String) {
        sourceHealth = CaptureHealth.ERROR
        relayDetail = message
        running.set(false)
        publishSnapshot(message)
        stopSelf()
    }

    private fun prepareLog() {
        val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        logFile = File(dir, "jls-host-$stamp.csv")
        logWriter = FileWriter(logFile!!, false)
        synchronized(logLock) {
            logWriter?.appendLine("# manufacturer=" + Build.MANUFACTURER)
            logWriter?.appendLine("# model=" + Build.MODEL)
            logWriter?.appendLine("# android=" + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT + " build=" + Build.DISPLAY)
            logWriter?.appendLine("# youtube=" + packageVersion("com.google.android.youtube"))
            logWriter?.appendLine("# youtube_music=" + packageVersion("com.google.android.apps.youtube.music"))
            logWriter?.appendLine("# protocol=" + JlsProtocol.VERSION + " codec=opus sample_rate=48000 channels=2 frame_ms=20 target_bitrate=144000 dtx=false fec=false")
            logWriter?.appendLine("# room=" + relayConfig.room)
            logWriter?.appendLine("elapsed_ms,health,capture_health,relay_state,rms_dbfs,peak_dbfs,active_playback,frames_captured,read_faults,packets_encoded,bytes_uploaded,bitrate_bps,encode_us,relay_rtt_ms,reconnects,send_buffer_depth,dropped_frames,projection_stops,thermal_status,battery_pct")
            logWriter?.flush()
        }
    }

    private fun writeLogRow(snapshot: CaptureSnapshot) {
        val elapsedMs = if (startedNs == 0L) 0 else
            (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0) / 1_000_000L
        synchronized(logLock) {
            try {
                logWriter?.appendLine(
                    listOf(
                        elapsedMs,
                        snapshot.health.name,
                        snapshot.captureHealth.name,
                        snapshot.relayState.name,
                        String.format(Locale.US, "%.2f", snapshot.rmsDb),
                        String.format(Locale.US, "%.2f", snapshot.peakDb),
                        snapshot.activePlayback,
                        snapshot.framesCaptured,
                        snapshot.readFaults,
                        snapshot.packetsEncoded,
                        snapshot.bytesUploaded,
                        snapshot.bitrateBps,
                        snapshot.encodeTimeUs,
                        snapshot.relayRttMs ?: -1,
                        snapshot.reconnects,
                        snapshot.sendBufferDepth,
                        snapshot.droppedFrames,
                        snapshot.projectionStops,
                        snapshot.thermalStatus,
                        snapshot.batteryPct
                    ).joinToString(",")
                )
                logWriter?.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun packageUid(packageName: String): Int? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            info.uid
        } catch (_: Throwable) {
            null
        }
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

    private fun batteryPercent(): Int {
        val intent = try {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        } ?: return -1

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) ((level * 100.0) / scale).toInt() else -1
    }

    private fun linearToDb(value: Double): Double {
        if (value <= 0.000001) return -120.0
        return max(-120.0, 20.0 * log10(value))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JawaharLiveSync:Capture"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun startAsForeground() {
        val notification = buildNotification(CaptureHealth.STARTING, -120.0, RelayState.DISCONNECTED)
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun updateNotification(health: CaptureHealth, rms: Double, relay: RelayState) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(health, rms, relay))
    }

    private fun buildNotification(health: CaptureHealth, rms: Double, relay: RelayState): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jawahar Live Sync")
            .setContentText(
                health.name + " · " + relay.name + " · RMS " +
                    String.format(Locale.US, "%.0f", rms) + " dBFS"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Live audio capture",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        running.set(false)
        SessionState.markActive(this, false)

        watchdog.shutdownNow()
        relayClient?.stop()
        relayClient = null

        captureThread?.interrupt()
        encoderThread?.interrupt()
        captureThread = null
        encoderThread = null
        encoderQueue.clear()

        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null

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
            powerManager.removeThermalStatusListener(thermalListener)
        } catch (_: Throwable) {
        }
        releaseWakeLock()

        synchronized(logLock) {
            try {
                logWriter?.flush()
                logWriter?.close()
            } catch (_: Throwable) {
            }
            logWriter = null
        }

        super.onDestroy()
    }
}

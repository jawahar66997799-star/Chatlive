package com.jawahar.livesync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.random.Random

data class RelayMetrics(
    val bytesUploaded: Long = 0,
    val packetsUploaded: Long = 0,
    val relayRttMs: Long? = null,
    val reconnects: Int = 0,
    val sendBufferDepth: Int = 0,
    val droppedFrames: Long = 0
)

class RelayClient(
    context: Context,
    private val config: RelayConfig,
    private val epoch: Int,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val frameMs: Int = 20,
    private val bitrateBps: Int = 144_000,
    private val listener: Listener
) {
    interface Listener {
        fun onRelayState(state: RelayState, detail: String)
        fun onRelayMetrics(metrics: RelayMetrics)
    }

    private data class QueuedFrame(
        val packet: ByteArray,
        val captureMonoNs: Long,
        val sequence: Long
    )

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val authBlocked = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectCount = AtomicInteger(0)
    private val bytesUploaded = AtomicLong(0)
    private val packetsUploaded = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val lastSentSequence = AtomicLong(0)
    private val lastRttMs = AtomicLong(-1)
    private val queue = ArrayBlockingQueue<QueuedFrame>(25)
    private val pingSentNs = ConcurrentHashMap<Long, Long>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "JlsRelayScheduler").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var senderThread: Thread? = null

    @Volatile
    private var socket: WebSocket? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!running.get() || authBlocked.get()) return
            if (!connected.get() && !connecting.get()) scheduleConnect(0)
        }

        override fun onLost(network: Network) {
            if (!running.get()) return
            if (!hasUsableNetwork()) {
                connected.set(false)
                socket?.cancel()
                socket = null
                listener.onRelayState(RelayState.NETWORK_INTERRUPTED, "No usable network is available.")
                emitMetrics()
            }
        }
    }

    fun start() {
        if (!config.enabled) {
            listener.onRelayState(RelayState.DISABLED, "Relay is not configured with a secure wss:// URL.")
            return
        }
        if (!running.compareAndSet(false, true)) return

        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }

        senderThread = Thread(::senderLoop, "JlsRelaySender").apply {
            isDaemon = true
            start()
        }
        scheduler.scheduleAtFixedRate({ pingTick() }, 3, 5, TimeUnit.SECONDS)
        scheduleConnect(0)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        reconnectFuture?.cancel(true)
        reconnectFuture = null
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        socket?.close(1000, "host-stop")
        socket = null
        connected.set(false)
        connecting.set(false)
        queue.clear()
        senderThread?.interrupt()
        senderThread = null
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        listener.onRelayState(RelayState.DISCONNECTED, "Relay stopped.")
        emitMetrics()
    }

    fun offer(packet: ByteArray, captureMonoNs: Long, sequence: Long) {
        if (!running.get()) return
        val item = QueuedFrame(packet, captureMonoNs, sequence)
        if (!queue.offer(item)) {
            queue.poll()
            droppedFrames.incrementAndGet()
            if (!queue.offer(item)) droppedFrames.incrementAndGet()
        }
        emitMetrics()
    }

    fun sendHostState(snapshot: CaptureSnapshot) {
        val ws = socket ?: return
        if (!connected.get() || ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) return

        val msg = JSONObject()
            .put("type", "host-state")
            .put("protocolVersion", JlsProtocol.VERSION)
            .put("epoch", epoch.toLong() and 0xFFFF_FFFFL)
            .put("sequence", lastSentSequence.get())
            .put("captureHealth", snapshot.captureHealth.name)
            .put("effectiveHealth", snapshot.health.name)
            .put("rmsDb", snapshot.rmsDb)
            .put("peakDb", snapshot.peakDb)
            .put("bitrateBps", snapshot.bitrateBps)
            .put("bufferDepth", queue.size)
            .put("droppedFrames", droppedFrames.get())
            .put("reconnects", reconnectCount.get())
        ws.send(msg.toString())
    }

    private fun scheduleConnect(delayMs: Long) {
        if (!running.get() || connected.get() || connecting.get() || authBlocked.get()) return
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({ connectNow() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun connectNow() {
        if (!running.get() || connected.get() || authBlocked.get()) return
        if (!hasUsableNetwork()) {
            listener.onRelayState(RelayState.NETWORK_INTERRUPTED, "Waiting for network.")
            return
        }
        if (!connecting.compareAndSet(false, true)) return

        val isReconnect = reconnectAttempts.get() > 0 || reconnectCount.get() > 0
        listener.onRelayState(
            if (isReconnect) RelayState.RECONNECTING else RelayState.CONNECTING,
            if (isReconnect) "Reconnecting to relay." else "Connecting to relay."
        )

        val requestBuilder = Request.Builder()
            .url(config.hostWsUrl())
            .header("X-JLS-Protocol", JlsProtocol.VERSION.toString())
            .header("X-JLS-Room", config.room)
        if (config.hostToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer " + config.hostToken)
        }

        client.newWebSocket(requestBuilder.build(), wsListener)
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connecting.set(false)
            connected.set(true)
            socket = webSocket
            val previousAttempts = reconnectAttempts.getAndSet(0)
            if (previousAttempts > 0) reconnectCount.incrementAndGet()

            val hello = JSONObject()
                .put("type", "host-hello")
                .put("protocolVersion", JlsProtocol.VERSION)
                .put("room", config.room)
                .put("epoch", epoch.toLong() and 0xFFFF_FFFFL)
                .put("resumeSequence", lastSentSequence.get())
                .put("sampleRate", sampleRate)
                .put("channels", channels)
                .put("codec", "opus")
                .put("codecId", JlsProtocol.CODEC_OPUS)
                .put("layer", JlsProtocol.LAYER_HIGH)
                .put("frameMs", frameMs)
                .put("bitrateBps", bitrateBps)
                .put("dtx", false)
                .put("inbandFec", false)
            webSocket.send(hello.toString())

            listener.onRelayState(
                RelayState.CONNECTED,
                if (reconnectCount.get() > 0) "Relay reconnected; stale audio is discarded." else "Relay connected."
            )
            emitMetrics()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "host-pong", "pong" -> {
                        val id = obj.optLong("id", Long.MIN_VALUE)
                        val sent = pingSentNs.remove(id)
                        if (sent != null) {
                            val ms = max(0L, (SystemClock.elapsedRealtimeNanos() - sent) / 1_000_000L)
                            lastRttMs.set(ms)
                            emitMetrics()
                        }
                    }
                    "auth-error" -> {
                        authBlocked.set(true)
                        listener.onRelayState(RelayState.AUTH_FAILED, obj.optString("message", "Relay rejected host authentication."))
                        webSocket.close(4001, "auth-failed")
                    }
                }
            } catch (_: Throwable) {
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (code == 4001 || code == 4401 || code == 4403) {
                authBlocked.set(true)
                listener.onRelayState(RelayState.AUTH_FAILED, "Relay authentication failed (code $code).")
            }
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect("Relay closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect("Relay connection failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    private fun handleDisconnect(detail: String) {
        if (socket != null) socket = null
        connected.set(false)
        connecting.set(false)
        if (!running.get()) return
        if (authBlocked.get()) {
            listener.onRelayState(RelayState.AUTH_FAILED, detail)
            return
        }
        if (!hasUsableNetwork()) {
            listener.onRelayState(RelayState.NETWORK_INTERRUPTED, "Network interrupted.")
            emitMetrics()
            return
        }

        val attempt = reconnectAttempts.incrementAndGet()
        val base = RetryPolicy.baseDelayMs(attempt - 1)
        val jitter = if (base >= 10) Random.nextLong(-(base / 10), base / 10 + 1) else 0
        val delay = (base + jitter).coerceAtLeast(100)
        listener.onRelayState(RelayState.RECONNECTING, detail + " Retry in " + delay + " ms.")
        emitMetrics()
        scheduleConnect(delay)
    }

    private fun senderLoop() {
        while (running.get()) {
            try {
                if (!connected.get()) {
                    Thread.sleep(20)
                    continue
                }

                val item = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val ageNs = SystemClock.elapsedRealtimeNanos() - item.captureMonoNs
                if (ageNs > STALE_FRAME_NS) {
                    droppedFrames.incrementAndGet()
                    emitMetrics()
                    continue
                }

                val ws = socket
                if (ws == null || !connected.get()) {
                    if (!queue.offer(item)) droppedFrames.incrementAndGet()
                    continue
                }

                if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
                    droppedFrames.incrementAndGet()
                    emitMetrics()
                    continue
                }

                val ok = ws.send(item.packet.toByteString())
                if (ok) {
                    bytesUploaded.addAndGet(item.packet.size.toLong())
                    packetsUploaded.incrementAndGet()
                    lastSentSequence.set(item.sequence)
                } else {
                    droppedFrames.incrementAndGet()
                    ws.cancel()
                }
                emitMetrics()
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                droppedFrames.incrementAndGet()
                emitMetrics()
            }
        }
    }

    private fun pingTick() {
        if (!running.get() || !connected.get()) return
        val ws = socket ?: return
        if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) return

        val id = SystemClock.elapsedRealtimeNanos()
        pingSentNs[id] = id
        val msg = JSONObject()
            .put("type", "host-ping")
            .put("id", id)
            .put("clientMonoNs", id)
        if (!ws.send(msg.toString())) pingSentNs.remove(id)

        val cutoff = id - 30_000_000_000L
        pingSentNs.entries.removeIf { it.value < cutoff }
    }

    private fun hasUsableNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun emitMetrics() {
        listener.onRelayMetrics(
            RelayMetrics(
                bytesUploaded = bytesUploaded.get(),
                packetsUploaded = packetsUploaded.get(),
                relayRttMs = lastRttMs.get().takeIf { it >= 0 },
                reconnects = reconnectCount.get(),
                sendBufferDepth = queue.size,
                droppedFrames = droppedFrames.get()
            )
        )
    }

    companion object {
        private const val MAX_SOCKET_QUEUE_BYTES = 512L * 1024L
        private const val STALE_FRAME_NS = 500_000_000L
    }
}

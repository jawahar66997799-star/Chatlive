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
    private val epoch: Long,
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
    private val pingSentNs = ConcurrentHashMap<String, Long>()
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
                connecting.set(false)
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
        if (config.room.length < 32 || config.hostToken.length < 32) {
            authBlocked.set(true)
            listener.onRelayState(
                RelayState.AUTH_FAILED,
                "Relay room ID and host secret must be configured with at least 128 bits of unguessable entropy."
            )
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
        ws.send(
            JSONObject()
                .put("type", "host_state")
                .put("v", JlsProtocol.VERSION)
                .put("state", snapshot.health.name)
                .toString()
        )
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
        if (config.hostToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer " + config.hostToken)
        }
        client.newWebSocket(requestBuilder.build(), wsListener)
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            val hello = JSONObject()
                .put("type", "hello_host")
                .put("v", JlsProtocol.VERSION)
                .put("room_id", config.room)
                .put("host_secret", config.hostToken)
                .put("epoch", epoch)
                .put("codec", "opus")
                .put("sample_rate", sampleRate)
                .put("channels", channels)
                .put("layer", JlsProtocol.LAYER_HIGH)
                .put("frame_samples", sampleRate * frameMs / 1000)
            if (lastSentSequence.get() > 0) {
                hello.put("resume_last_seq", lastSentSequence.get())
            }
            if (!webSocket.send(hello.toString())) {
                webSocket.cancel()
                return
            }

            scheduler.schedule({
                if (running.get() && socket === webSocket && !connected.get()) {
                    webSocket.cancel()
                }
            }, 8, TimeUnit.SECONDS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "hello_host_ack" -> {
                        if (obj.optInt("v", -1) != JlsProtocol.VERSION) {
                            webSocket.close(1002, "protocol-version-mismatch")
                            return
                        }
                        connecting.set(false)
                        connected.set(true)
                        val previousAttempts = reconnectAttempts.getAndSet(0)
                        if (previousAttempts > 0) reconnectCount.incrementAndGet()
                        val resumeAfter = obj.optLong("resume_after_sequence", 0L)
                        while (true) {
                            val head = queue.peek() ?: break
                            if (head.sequence > resumeAfter) break
                            queue.poll()
                            droppedFrames.incrementAndGet()
                        }
                        listener.onRelayState(
                            RelayState.CONNECTED,
                            if (reconnectCount.get() > 0) {
                                "Relay authenticated and reconnected at server sequence $resumeAfter; stale audio is discarded."
                            } else {
                                "Relay authenticated. Server resume sequence: $resumeAfter."
                            }
                        )
                        emitMetrics()
                    }
                    "pong", "clock_resp" -> {
                        val id = obj.optString("id")
                        val sent = pingSentNs.remove(id)
                        if (sent != null) {
                            val ms = max(0L, (SystemClock.elapsedRealtimeNanos() - sent) / 1_000_000L)
                            lastRttMs.set(ms)
                            emitMetrics()
                        }
                    }
                    "error", "auth_error", "auth-error" -> {
                        val code = obj.optString("code")
                        val retryable = obj.optBoolean("retryable", false)
                        val message = obj.optString("message", "Relay rejected the host connection.")
                        if (!retryable || code.contains("auth", ignoreCase = true) ||
                            code.contains("secret", ignoreCase = true)
                        ) {
                            authBlocked.set(true)
                            connected.set(false)
                            connecting.set(false)
                            listener.onRelayState(RelayState.AUTH_FAILED, message)
                            webSocket.close(1008, "auth-failed")
                        } else {
                            listener.onRelayState(RelayState.ERROR, message)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (code == 1008 || code == 4001 || code == 4401 || code == 4403) {
                authBlocked.set(true)
                listener.onRelayState(RelayState.AUTH_FAILED, "Relay authentication failed (code $code).")
            }
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect("Relay closed: $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401 || response?.code == 403) {
                authBlocked.set(true)
                connected.set(false)
                connecting.set(false)
                listener.onRelayState(RelayState.AUTH_FAILED, "Relay HTTP authentication failed.")
                return
            }
            handleDisconnect(
                "Relay connection failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            )
        }
    }

    private fun handleDisconnect(detail: String) {
        socket = null
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

        val now = SystemClock.elapsedRealtimeNanos()
        val id = now.toString()
        pingSentNs[id] = now
        val msg = JSONObject()
            .put("type", "ping")
            .put("v", JlsProtocol.VERSION)
            .put("id", id)
            .put("t0_ns", now)
        if (!ws.send(msg.toString())) pingSentNs.remove(id)

        val cutoff = now - 30_000_000_000L
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

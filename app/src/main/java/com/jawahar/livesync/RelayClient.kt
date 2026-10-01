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
    val webSocketQueueBytes: Long = 0,
    val lastSendAgeMs: Long? = null,
    val lastRelayControlAgeMs: Long? = null,
    val resumeAfterSequence: Long = 0,
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
    private val everAuthenticated = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectCount = AtomicInteger(0)
    private val bytesUploaded = AtomicLong(0)
    private val packetsUploaded = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val lastSentSequence = AtomicLong(0)
    private val lastRttMs = AtomicLong(-1)
    private val lastSendProgressNs = AtomicLong(0)
    private val lastRelayControlNs = AtomicLong(0)
    private val lastResumeAfterSequence = AtomicLong(0)
    private val relayDiscontinuity = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<QueuedFrame>(UPLINK_QUEUE_CAPACITY)
    private val pingSentNs = ConcurrentHashMap<String, Long>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "JlsRelayScheduler").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var senderThread: Thread? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var currentNetwork: Network? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = currentNetwork
            currentNetwork = network
            if (!running.get() || authBlocked.get()) return
            if (previous != null && previous != network && (connected.get() || connecting.get())) {
                forceReconnect("Default network route changed; reconnecting immediately on the new route.")
            } else if (!connected.get() && !connecting.get()) {
                scheduleConnect(0)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            currentNetwork = network
            if (!running.get() || authBlocked.get()) return
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !connected.get() &&
                !connecting.get()
            ) {
                scheduleConnect(0)
            }
        }

        override fun onLost(network: Network) {
            if (currentNetwork == network) currentNetwork = connectivity.activeNetwork
            if (!running.get()) return
            if (!hasUsableNetwork()) {
                connected.set(false)
                connecting.set(false)
                socket?.cancel()
                socket = null
                listener.onRelayState(RelayState.NETWORK_INTERRUPTED, "No validated network is available.")
                emitMetrics()
            } else if (connected.get() || connecting.get()) {
                forceReconnect("Active network changed after route loss; reconnecting immediately.")
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

        currentNetwork = connectivity.activeNetwork
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
        currentNetwork = null
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
            queue.poll()?.let { markDroppedFrame() }
            if (!queue.offer(item)) markDroppedFrame()
        }
        trimToLiveEdge()
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

        val isReconnect = everAuthenticated.get() || reconnectAttempts.get() > 0
        listener.onRelayState(
            if (isReconnect) RelayState.RECONNECTING else RelayState.CONNECTING,
            if (isReconnect) "Reconnecting to relay." else "Connecting to relay."
        )

        val requestBuilder = Request.Builder()
            .url(config.hostWsUrl())
            .header("X-JLS-Protocol", JlsProtocol.VERSION.toString())
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
            lastRelayControlNs.set(SystemClock.elapsedRealtimeNanos())
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
                        reconnectAttempts.set(0)
                        val wasReconnect = everAuthenticated.getAndSet(true)
                        if (wasReconnect) reconnectCount.incrementAndGet()
                        val resumeAfter = obj.optLong("resume_after_sequence", 0L)
                        lastResumeAfterSequence.set(resumeAfter)
                        while (true) {
                            val head = queue.peek() ?: break
                            if (head.sequence > resumeAfter) break
                            queue.poll()
                        }
                        trimToLiveEdge()
                        listener.onRelayState(
                            RelayState.CONNECTED,
                            if (wasReconnect) {
                                "Relay authenticated and reconnected at server sequence $resumeAfter; stale audio was discarded."
                            } else {
                                "Relay authenticated. Server resume sequence: $resumeAfter."
                            }
                        )
                        emitMetrics()
                    }
                    "clock_resp" -> {
                        val id = obj.optString("id")
                        val sent = pingSentNs.remove(id)
                        if (sent != null) {
                            val ms = max(0L, (SystemClock.elapsedRealtimeNanos() - sent) / 1_000_000L)
                            lastRttMs.set(ms)
                            emitMetrics()
                        }
                    }
                    "error", "auth_error", "auth-error" -> {
                        val code = obj.optString("code").lowercase()
                        val retryable = obj.optBoolean("retryable", false)
                        val message = obj.optString("message", "Relay rejected the host connection.")
                        when {
                            code == "unauthorized" ||
                                code == "auth_failed" ||
                                code.contains("secret") -> {
                                authBlocked.set(true)
                                connected.set(false)
                                connecting.set(false)
                                listener.onRelayState(RelayState.AUTH_FAILED, message)
                                webSocket.close(1008, "unauthorized")
                            }
                            code == "bad_hello" -> {
                                authBlocked.set(true)
                                connected.set(false)
                                connecting.set(false)
                                listener.onRelayState(RelayState.ERROR, message)
                                webSocket.close(1002, "bad-hello")
                            }
                            retryable -> forceReconnect("Relay requested reconnect: " + message)
                            else -> {
                                listener.onRelayState(RelayState.ERROR, code + ": " + message)
                                webSocket.close(1011, "relay-error")
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val authReason = reason.contains("unauthorized", true) ||
                reason.contains("auth", true) ||
                reason.contains("secret", true)
            if (authReason) {
                authBlocked.set(true)
                listener.onRelayState(RelayState.AUTH_FAILED, "Relay authentication failed: $reason")
            }
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(webSocket, "Relay closed: $code $reason")
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
                webSocket,
                "Relay connection failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            )
        }
    }

    private fun handleDisconnect(webSocket: WebSocket, detail: String) {
        if (socket !== webSocket) return
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

        trimToLiveEdge()
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
                    markDroppedFrame()
                    emitMetrics()
                    continue
                }

                if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
                    markDroppedFrame()
                    emitMetrics()
                    forceReconnect(
                        "WebSocket backpressure exceeded " + MAX_SOCKET_QUEUE_BYTES +
                            " bytes; flushing stale socket state and returning to live edge."
                    )
                    continue
                }

                val outbound = if (relayDiscontinuity.getAndSet(false)) {
                    item.packet.copyOf().also { packet ->
                        if (packet.size > 6) {
                            packet[6] = (packet[6].toInt() or JlsProtocol.FLAG_DISCONTINUITY).toByte()
                        }
                    }
                } else {
                    item.packet
                }

                val ok = ws.send(outbound.toByteString())
                if (ok) {
                    bytesUploaded.addAndGet(outbound.size.toLong())
                    packetsUploaded.incrementAndGet()
                    lastSentSequence.set(item.sequence)
                    lastSendProgressNs.set(SystemClock.elapsedRealtimeNanos())
                } else {
                    markDroppedFrame()
                    forceReconnect("WebSocket send queue rejected an audio frame; reconnecting at live edge.")
                }
                emitMetrics()
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                markDroppedFrame()
                emitMetrics()
            }
        }
    }

    private fun discardStale(maxAgeNs: Long) {
        val now = SystemClock.elapsedRealtimeNanos()
        while (true) {
            val head = queue.peek() ?: break
            if (now - head.captureMonoNs <= maxAgeNs) break
            queue.poll()
            markDroppedFrame()
        }
    }

    private fun trimToLiveEdge() {
        discardStale(RECONNECT_LIVE_EDGE_NS)
        val maxFrames = (250 / frameMs).coerceAtLeast(1)
        while (queue.size > maxFrames) {
            queue.poll() ?: break
            markDroppedFrame()
        }
    }

    private fun markDroppedFrame() {
        droppedFrames.incrementAndGet()
        relayDiscontinuity.set(true)
    }

    private fun forceReconnect(detail: String) {
        if (!running.get() || authBlocked.get()) return
        val old = socket
        socket = null
        connected.set(false)
        connecting.set(false)
        old?.cancel()
        trimToLiveEdge()
        listener.onRelayState(RelayState.RECONNECTING, detail)
        emitMetrics()
        scheduleConnect(0)
    }

    private fun pingTick() {
        if (!running.get() || !connected.get()) return
        val ws = socket ?: return

        val now = SystemClock.elapsedRealtimeNanos()
        val oldestOutstanding = pingSentNs.values.minOrNull()
        if (oldestOutstanding != null && now - oldestOutstanding > CONTROL_STALL_NS) {
            forceReconnect(
                "RELAY_ACK/STATE stalled: no clock response within " +
                    (CONTROL_STALL_NS / 1_000_000L) + " ms."
            )
            return
        }
        if (ws.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
            forceReconnect("WebSocket control path is backpressured; reconnecting at live edge.")
            return
        }

        val id = now.toString()
        pingSentNs[id] = now
        val msg = JSONObject()
            .put("type", "clock_req")
            .put("v", JlsProtocol.VERSION)
            .put("id", id)
            .put("t0_ns", now)
        if (!ws.send(msg.toString())) {
            pingSentNs.remove(id)
            forceReconnect("WebSocket liveness probe could not be queued; reconnecting immediately.")
            return
        }

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
        val now = SystemClock.elapsedRealtimeNanos()
        val sendNs = lastSendProgressNs.get()
        val controlNs = lastRelayControlNs.get()
        listener.onRelayMetrics(
            RelayMetrics(
                bytesUploaded = bytesUploaded.get(),
                packetsUploaded = packetsUploaded.get(),
                relayRttMs = lastRttMs.get().takeIf { it >= 0 },
                reconnects = reconnectCount.get(),
                sendBufferDepth = queue.size,
                webSocketQueueBytes = socket?.queueSize() ?: 0L,
                lastSendAgeMs = sendNs.takeIf { it > 0 }?.let { max(0L, (now - it) / 1_000_000L) },
                lastRelayControlAgeMs = controlNs.takeIf { it > 0 }?.let { max(0L, (now - it) / 1_000_000L) },
                resumeAfterSequence = lastResumeAfterSequence.get(),
                droppedFrames = droppedFrames.get()
            )
        )
    }

    companion object {
        private const val UPLINK_QUEUE_CAPACITY = 24
        private const val MAX_SOCKET_QUEUE_BYTES = 12L * 1024L
        private const val STALE_FRAME_NS = 400_000_000L
        private const val RECONNECT_LIVE_EDGE_NS = 250_000_000L
        private const val CONTROL_STALL_NS = 12_000_000_000L
    }
}

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
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class UplinkStats(
    val state: RelayState = RelayState.DISCONNECTED,
    val bytesAccepted: Long = 0,
    val reconnects: Int = 0,
    val queueDepth: Int = 0,
    val droppedFrames: Long = 0,
    val rttMs: Long? = null,
    val detail: String = ""
)

class UplinkClient(
    context: Context,
    private val config: RelayConfig,
    private val epoch: Long,
    private val onStats: (UplinkStats) -> Unit
) {
    companion object {
        private const val MAX_LOCAL_FRAMES = 75
        private const val MAX_AGE_NS = 1_500_000_000L
        private const val RECONNECT_CATCHUP_NS = 250_000_000L
        private const val SOCKET_QUEUE_LIMIT = 512L * 1024L
    }

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "JlsUplink").apply { isDaemon = true }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val lock = Any()
    private val queue = ArrayDeque<EncodedAudioFrame>()

    private var socket: WebSocket? = null
    private var scheduledReconnect: ScheduledFuture<*>? = null
    private var active = false
    private var connected = false
    private var reconnectAttempt = 0
    private var reconnects = 0
    private var bytesAccepted = 0L
    private var dropped = 0L
    private var lastSequence = -1L
    private var relayRttMs: Long? = null
    private var pingId = 0L
    private val pings = mutableMapOf<Long, Long>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!active) return
            publish(
                if (connected) RelayState.CONNECTED else RelayState.RECONNECTING,
                "Network available."
            )
            scheduleConnect(0)
        }

        override fun onLost(network: Network) {
            if (!active || hasUsableNetwork()) return
            val old = synchronized(lock) {
                connected = false
                socket.also { socket = null }
            }
            old?.cancel()
            publish(RelayState.NETWORK_INTERRUPTED, "No validated network.")
            scheduleReconnect()
        }
    }

    fun start() {
        if (!config.enabled) {
            publish(RelayState.DISABLED, "Relay is not configured.")
            return
        }
        active = true
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        scheduler.scheduleAtFixedRate({ drain() }, 0, 10, TimeUnit.MILLISECONDS)
        scheduler.scheduleAtFixedRate({ sendPing() }, 3, 5, TimeUnit.SECONDS)
        scheduleConnect(0)
    }

    fun stop() {
        active = false
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        synchronized(lock) {
            scheduledReconnect?.cancel(false)
            scheduledReconnect = null
            socket?.close(1000, "host-stop")
            socket = null
            connected = false
            queue.clear()
            pings.clear()
        }
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        publish(RelayState.DISCONNECTED, "Stopped.")
    }

    fun enqueue(frame: EncodedAudioFrame) {
        if (!active || !config.enabled) return
        synchronized(lock) {
            lastSequence = frame.sequence
            discardStaleLocked(SystemClock.elapsedRealtimeNanos(), MAX_AGE_NS)
            while (queue.size >= MAX_LOCAL_FRAMES) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(frame)
        }
        publishCurrent()
    }

    fun sendHostState(captureHealth: CaptureHealth, detail: String) {
        val ws = synchronized(lock) { if (connected) socket else null } ?: return
        ws.send(
            JSONObject()
                .put("type", "host-state")
                .put("protocolVersion", JlsProtocol.VERSION)
                .put("epoch", epoch.toString())
                .put("captureHealth", captureHealth.name)
                .put("detail", detail)
                .put("clientMonoNs", SystemClock.elapsedRealtimeNanos().toString())
                .toString()
        )
    }

    private fun connect() {
        if (!active || !config.enabled) return
        synchronized(lock) {
            if (connected || socket != null) return
        }

        if (!hasUsableNetwork()) {
            publish(RelayState.NETWORK_INTERRUPTED, "Waiting for network.")
            scheduleReconnect()
            return
        }

        publish(
            if (reconnectAttempt == 0) RelayState.CONNECTING else RelayState.RECONNECTING,
            "Opening secure relay uplink."
        )

        val request = Request.Builder()
            .url(config.hostWsUrl())
            .apply {
                if (config.hostToken.isNotBlank()) {
                    header("Authorization", "Bearer ${config.hostToken}")
                }
            }
            .build()

        val ws = client.newWebSocket(request, listener)
        synchronized(lock) {
            socket = ws
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (!active) {
                    webSocket.close(1000, "inactive")
                    return
                }
                socket = webSocket
                connected = true
                reconnectAttempt = 0
                discardStaleLocked(
                    SystemClock.elapsedRealtimeNanos(),
                    RECONNECT_CATCHUP_NS
                )
            }

            webSocket.send(
                JSONObject()
                    .put("type", "host-hello")
                    .put("protocolVersion", JlsProtocol.VERSION)
                    .put("codec", "opus")
                    .put("sampleRate", 48_000)
                    .put("channels", 2)
                    .put("frameMs", 20)
                    .put("layer", 0)
                    .put("targetBitrate", 144_000)
                    .put("dtx", false)
                    .put("inbandFec", false)
                    .put("epoch", epoch.toString())
                    .put("resumeAfterSequence", lastSequence.toString())
                    .toString()
            )
            publish(RelayState.CONNECTED, "Relay connected.")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = JSONObject(text)
                when (obj.optString("type")) {
                    "pong" -> {
                        val id = obj.optLong("id", -1L)
                        val sent = synchronized(lock) { pings.remove(id) }
                        if (sent != null) {
                            relayRttMs =
                                (SystemClock.elapsedRealtimeNanos() - sent) / 1_000_000L
                            publishCurrent()
                        }
                    }

                    "auth-error" -> {
                        publish(
                            RelayState.AUTH_FAILED,
                            "Relay rejected host authentication."
                        )
                        webSocket.close(1008, "auth-failed")
                    }
                }
            } catch (_: Throwable) {
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(webSocket, "Closed: $code $reason")
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?
        ) {
            val code = response?.code
            if (code == 401 || code == 403) {
                publish(
                    RelayState.AUTH_FAILED,
                    "Relay authentication failed (HTTP $code)."
                )
            }
            handleDisconnect(
                webSocket,
                "Relay failure: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            )
        }
    }

    private fun handleDisconnect(webSocket: WebSocket, reason: String) {
        val wasCurrent = synchronized(lock) {
            if (socket !== webSocket) {
                false
            } else {
                socket = null
                connected = false
                true
            }
        }
        if (!wasCurrent || !active) return

        reconnects++
        publish(
            if (hasUsableNetwork()) {
                RelayState.RECONNECTING
            } else {
                RelayState.NETWORK_INTERRUPTED
            },
            reason
        )
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!active) return
        reconnectAttempt++
        val delayMs = when (reconnectAttempt) {
            1 -> 250L
            2 -> 500L
            3 -> 1_000L
            else -> {
                val power = min(4, reconnectAttempt - 3)
                min(15_000L, 1_000L * (1L shl power))
            }
        }
        scheduleConnect(delayMs)
    }

    private fun scheduleConnect(delayMs: Long) {
        synchronized(lock) {
            if (!active || connected || socket != null) return
            scheduledReconnect?.cancel(false)
            scheduledReconnect = scheduler.schedule(
                {
                    synchronized(lock) { scheduledReconnect = null }
                    connect()
                },
                delayMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun drain() {
        val ws = synchronized(lock) {
            if (!active || !connected) return
            discardStaleLocked(
                SystemClock.elapsedRealtimeNanos(),
                MAX_AGE_NS
            )
            socket
        } ?: return

        if (ws.queueSize() >= SOCKET_QUEUE_LIMIT) {
            synchronized(lock) {
                while (queue.size > 10) {
                    queue.removeFirst()
                    dropped++
                }
            }
            publishCurrent()
            return
        }

        var sent = 0
        while (sent < 4) {
            val frame = synchronized(lock) {
                if (connected) queue.pollFirst() else null
            } ?: break

            val packet = JlsProtocol.encodeAudio(frame)
            if (!ws.send(packet.toByteString())) {
                synchronized(lock) { dropped++ }
                break
            }
            bytesAccepted += packet.size
            sent++
        }
        if (sent > 0) publishCurrent()
    }

    private fun sendPing() {
        val ws = synchronized(lock) {
            if (connected) socket else null
        } ?: return

        val id = ++pingId
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            pings[id] = now
            val cutoff = now - 30_000_000_000L
            pings.entries.removeAll { it.value < cutoff }
        }

        ws.send(
            JSONObject()
                .put("type", "ping")
                .put("id", id)
                .put("clientMonoNs", now.toString())
                .toString()
        )
    }

    private fun discardStaleLocked(nowNs: Long, maxAgeNs: Long) {
        while (
            queue.isNotEmpty() &&
            nowNs - queue.first.captureMonoNs > maxAgeNs
        ) {
            queue.removeFirst()
            dropped++
        }
    }

    private fun hasUsableNetwork(): Boolean {
        return try {
            val network = connectivity.activeNetwork ?: return false
            val caps =
                connectivity.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) {
            true
        }
    }

    private fun publish(state: RelayState, detail: String) {
        onStats(
            UplinkStats(
                state = state,
                bytesAccepted = bytesAccepted,
                reconnects = reconnects,
                queueDepth = synchronized(lock) { queue.size },
                droppedFrames = dropped,
                rttMs = relayRttMs,
                detail = detail
            )
        )
    }

    private fun publishCurrent() {
        publish(
            when {
                !config.enabled -> RelayState.DISABLED
                connected -> RelayState.CONNECTED
                !hasUsableNetwork() -> RelayState.NETWORK_INTERRUPTED
                active -> RelayState.RECONNECTING
                else -> RelayState.DISCONNECTED
            },
            ""
        )
    }
}

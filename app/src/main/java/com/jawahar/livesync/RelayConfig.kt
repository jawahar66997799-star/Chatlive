package com.jawahar.livesync

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.SecureRandom
import java.util.Locale

data class RelayConfig(
    val relayBaseUrl: String,
    val room: String,
    val hostToken: String,
    val guestToken: String,
    val guestBaseUrl: String,
    val publicRoomCode: String
) {
    val enabled: Boolean
        get() = relayBaseUrl.startsWith("wss://")

    fun hostWsUrl(): String {
        if (!enabled) return ""
        val uri = Uri.parse(relayBaseUrl)
        val path = when {
            uri.path.isNullOrBlank() || uri.path == "/" -> "/v1/ws/host"
            uri.path!!.endsWith("/v1/ws/host") -> uri.path!!
            else -> uri.path!!.trimEnd('/') + "/v1/ws/host"
        }
        return uri.buildUpon()
            .path(path)
            .clearQuery()
            .fragment(null)
            .build()
            .toString()
    }

    fun guestBase(): String {
        if (guestBaseUrl.isNotBlank()) return guestBaseUrl.trimEnd('/')
        if (!enabled) return ""
        val u = Uri.parse(relayBaseUrl)
        return u.buildUpon()
            .scheme("https")
            .path("")
            .clearQuery()
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    fun guestUrl(): String {
        val base = guestBase()
        if (base.isBlank()) return ""
        return if (isValidPublicRoomCode(publicRoomCode)) {
            "${base}/room/${Uri.encode(publicRoomCode)}"
        } else if (guestToken.isNotBlank()) {
            "${base}/r/${Uri.encode(guestToken)}"
        } else {
            ""
        }
    }

    companion object {
        private const val PREFS = "jls_relay"
        private const val KEY_ROOM = "room"
        private const val KEY_RELAY = "relay"
        private const val KEY_TOKEN = "token"
        private const val KEY_GUEST_TOKEN = "guest_token"
        private const val KEY_GUEST = "guest"
        private const val KEY_PUBLIC_ROOM_CODE = "public_room_code"

        fun load(context: Context): RelayConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val room = prefs.getString(KEY_ROOM, "").orEmpty().trim()
                .ifBlank { BuildConfig.DEFAULT_ROOM.trim() }
                .ifBlank {
                    randomToken(24).also { prefs.edit().putString(KEY_ROOM, it).apply() }
                }

            val relay = prefs.getString(KEY_RELAY, "").orEmpty().trim()
                .ifBlank { BuildConfig.DEFAULT_RELAY_URL.trim() }
            val token = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
            val guestToken = prefs.getString(KEY_GUEST_TOKEN, "").orEmpty().trim()
            val guest = prefs.getString(KEY_GUEST, "").orEmpty().trim()
                .ifBlank { BuildConfig.DEFAULT_GUEST_BASE_URL.trim() }
            val publicCode = prefs.getString(KEY_PUBLIC_ROOM_CODE, "").orEmpty().trim()

            return RelayConfig(relay, room, token, guestToken, guest, publicCode)
        }

        fun saveOverride(
            context: Context,
            relayBaseUrl: String,
            room: String,
            hostToken: String,
            guestToken: String,
            guestBaseUrl: String = "",
            publicRoomCode: String = load(context).publicRoomCode
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RELAY, relayBaseUrl.trim())
                .putString(KEY_ROOM, room.trim())
                .putString(KEY_TOKEN, hostToken.trim())
                .putString(KEY_GUEST_TOKEN, guestToken.trim())
                .putString(KEY_GUEST, guestBaseUrl.trim())
                .putString(KEY_PUBLIC_ROOM_CODE, publicRoomCode.trim())
                .apply()
        }

        fun savePublicRoomCode(context: Context, code: String) {
            require(isValidPublicRoomCode(code)) { "Room code must be exactly 6 digits." }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PUBLIC_ROOM_CODE, code.trim())
                .apply()
        }

        fun generatePublicRoomCode(): String {
            val n = SecureRandom().nextInt(1_000_000)
            return String.format(Locale.US, "%06d", n)
        }

        fun isValidPublicRoomCode(code: String): Boolean =
            code.length == 6 && code.all { it in '0'..'9' }

        private fun randomToken(bytes: Int): String {
            val raw = ByteArray(bytes)
            SecureRandom().nextBytes(raw)
            return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}

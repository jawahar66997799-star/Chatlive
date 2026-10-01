package com.jawahar.livesync

import android.content.Context

object SessionState {
    private const val PREFS = "jls_session"
    private const val KEY_ACTIVE = "capture_active"

    fun markActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .apply()
    }

    fun consumeInterruptedSession(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        if (active) prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        return active
    }
}

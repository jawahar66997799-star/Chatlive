package com.jawahar.livesync

object RetryPolicy {
    private val DELAYS_MS = longArrayOf(250, 500, 1_000, 2_000, 4_000, 8_000, 15_000)

    fun baseDelayMs(attempt: Int): Long {
        if (attempt <= 0) return DELAYS_MS[0]
        return DELAYS_MS[attempt.coerceAtMost(DELAYS_MS.lastIndex)]
    }
}

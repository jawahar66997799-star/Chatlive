package com.jawahar.livesync

object RetryPolicy {
    private val DELAYS_MS = longArrayOf(100, 200, 400, 800, 1_500, 2_500, 4_000)

    fun baseDelayMs(attempt: Int): Long {
        if (attempt <= 0) return DELAYS_MS[0]
        return DELAYS_MS[attempt.coerceAtMost(DELAYS_MS.lastIndex)]
    }
}

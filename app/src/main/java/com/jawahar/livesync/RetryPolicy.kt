package com.jawahar.livesync

object RetryPolicy {
    private val DELAYS_MS = longArrayOf(100, 150, 250, 400, 600, 800, 1_000, 1_200)

    fun baseDelayMs(attempt: Int): Long {
        if (attempt <= 0) return DELAYS_MS[0]
        return DELAYS_MS[attempt.coerceAtMost(DELAYS_MS.lastIndex)]
    }
}

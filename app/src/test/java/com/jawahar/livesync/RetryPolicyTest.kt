package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun retryBackoffStartsFastAndCaps() {
        assertEquals(250L, RetryPolicy.baseDelayMs(0))
        assertEquals(500L, RetryPolicy.baseDelayMs(1))
        assertEquals(1_000L, RetryPolicy.baseDelayMs(2))
        assertEquals(15_000L, RetryPolicy.baseDelayMs(100))
    }
}

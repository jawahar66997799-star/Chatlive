package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun retryBackoffStartsFastAndCaps() {
        assertEquals(100L, RetryPolicy.baseDelayMs(0))
        assertEquals(200L, RetryPolicy.baseDelayMs(1))
        assertEquals(400L, RetryPolicy.baseDelayMs(2))
        assertEquals(4_000L, RetryPolicy.baseDelayMs(100))
    }
}

package com.jawahar.livesync

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun retryBackoffStartsFastAndCaps() {
        assertEquals(100L, RetryPolicy.baseDelayMs(0))
        assertEquals(150L, RetryPolicy.baseDelayMs(1))
        assertEquals(250L, RetryPolicy.baseDelayMs(2))
        assertEquals(1_200L, RetryPolicy.baseDelayMs(100))
    }
}

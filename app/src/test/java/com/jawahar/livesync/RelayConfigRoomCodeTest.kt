package com.jawahar.livesync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConfigRoomCodeTest {
    @Test
    fun generatedPublicRoomCodesAreAlwaysSixDigits() {
        repeat(256) {
            val code = RelayConfig.generatePublicRoomCode()
            assertTrue("generated code must be valid: $code", RelayConfig.isValidPublicRoomCode(code))
        }
    }

    @Test
    fun validatorRejectsMalformedPublicRoomCodes() {
        listOf(
            "",
            "1",
            "12345",
            "1234567",
            "abcdef",
            "12 456",
            "-12345",
            "１２３４５６"
        ).forEach { candidate ->
            assertFalse(
                "malformed code must be rejected: $candidate",
                RelayConfig.isValidPublicRoomCode(candidate)
            )
        }
    }
}

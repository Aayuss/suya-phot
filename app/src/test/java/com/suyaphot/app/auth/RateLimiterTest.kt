package com.suyaphot.app.auth

import com.suyaphot.app.domain.auth.PinAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimiterTest {

    @Test
    fun testLockoutBackoffThresholds() {
        assertEquals(0L, PinAuthenticator.calculateLockoutMs(1))
        assertEquals(0L, PinAuthenticator.calculateLockoutMs(2))
        assertEquals(0L, PinAuthenticator.calculateLockoutMs(3))
        assertEquals(0L, PinAuthenticator.calculateLockoutMs(4))

        assertEquals(15_000L, PinAuthenticator.calculateLockoutMs(5))
        assertEquals(30_000L, PinAuthenticator.calculateLockoutMs(6))
        assertEquals(60_000L, PinAuthenticator.calculateLockoutMs(7))
        assertEquals(300_000L, PinAuthenticator.calculateLockoutMs(8))
        assertEquals(300_000L, PinAuthenticator.calculateLockoutMs(15))
    }
}

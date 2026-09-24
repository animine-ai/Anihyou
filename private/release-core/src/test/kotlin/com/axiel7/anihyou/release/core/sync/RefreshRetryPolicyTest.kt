package com.axiel7.anihyou.release.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRetryPolicyTest {
    @Test
    fun retriesOnlyBeforeTheBoundedAttemptCount() {
        assertTrue(RefreshRetryPolicy.shouldRetry(0))
        assertTrue(RefreshRetryPolicy.shouldRetry(1))
        assertFalse(RefreshRetryPolicy.shouldRetry(2))
        assertFalse(RefreshRetryPolicy.shouldRetry(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeAttemptCountIsRejected() {
        RefreshRetryPolicy.shouldRetry(-1)
    }
}

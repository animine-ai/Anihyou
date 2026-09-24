package com.axiel7.anihyou.release.core.sync

/**
 * Bounds WorkManager retry loops for forecast-triggered refreshes.
 *
 * A failed refresh never changes release state. It may be retried a bounded
 * number of times, after which WorkManager receives failure and the next
 * foreground/background refresh remains the recovery path.
 */
object RefreshRetryPolicy {
    const val MAX_ATTEMPTS: Int = 3

    fun shouldRetry(runAttemptCount: Int): Boolean {
        require(runAttemptCount >= 0) { "run attempt count must be non-negative" }
        return runAttemptCount + 1 < MAX_ATTEMPTS
    }
}

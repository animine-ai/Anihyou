package com.axiel7.anihyou.release.data.repository

import java.time.Instant
import java.time.temporal.ChronoUnit

enum class LookupCacheDisposition {
    POSITIVE,
    NEGATIVE_OR_AMBIGUOUS,
}

object LookupCachePolicy {
    const val POSITIVE_TTL_DAYS = 30L
    const val NEGATIVE_OR_AMBIGUOUS_TTL_DAYS = 1L

    fun expiry(
        fetchedAt: Instant,
        disposition: LookupCacheDisposition,
    ): Instant = fetchedAt.plus(
        when (disposition) {
            LookupCacheDisposition.POSITIVE -> POSITIVE_TTL_DAYS
            LookupCacheDisposition.NEGATIVE_OR_AMBIGUOUS -> NEGATIVE_OR_AMBIGUOUS_TTL_DAYS
        },
        ChronoUnit.DAYS,
    )
}

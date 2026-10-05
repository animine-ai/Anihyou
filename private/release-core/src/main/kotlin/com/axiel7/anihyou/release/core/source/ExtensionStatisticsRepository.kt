package com.axiel7.anihyou.release.core.source

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Current source series/season pairs, not episode rows or historical mapping records. */
data class ExtensionMatchingStatistics(val found: Int, val matched: Int, val unmatched: Int) {
    init { require(found >= 0 && matched >= 0 && unmatched >= 0 && matched + unmatched == found) }
}

enum class ExtensionDataUpdateStatus { UNKNOWN, SUCCESS, PARTIAL, FAILED }

/** User-facing local measurements only; technical details belong to ExtensionDiagnosticsRepository. */
data class ExtensionUserStatistics(
    val matching: ExtensionMatchingStatistics? = null,
    val lastUpdatedAt: Instant? = null,
    val updateStatus: ExtensionDataUpdateStatus = ExtensionDataUpdateStatus.UNKNOWN,
)

interface ExtensionStatisticsRepository {
    /** Observe locally, never perform a refresh or a matcher/AniList lookup. */
    fun observe(key: ExtensionSelectionKey): Flow<ExtensionUserStatistics>
}

package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ExtensionCenterStatisticsRepositoryTest {
    private val key = ExtensionSelectionKey("source", "extension", "publisher", "provider")
    private val counts = ExtensionMatchingStatistics(73, 65, 8)
    private val updated = Instant.parse("2026-10-05T09:00:00Z")

    @Test fun failedRefreshRetainsLastCommittedTimeAndDoesNotTurnUnmatchedIntoErrors() {
        val value = statisticsForReceipt(ProviderNavigationStoredState(rowsSource = key,
            syncStatistics = mapOf("Last sync outcome" to "FAILED", "Last committed sync" to updated.toString())), key, counts)
        assertEquals(counts, value.matching)
        assertEquals(updated, value.lastUpdatedAt)
        assertEquals(ExtensionDataUpdateStatus.FAILED, value.updateStatus)
    }

    @Test fun firstFailureDoesNotClaimZeroRowsOrAnUpdate() {
        val value = statisticsForReceipt(ProviderNavigationStoredState(
            syncStatistics = mapOf("Last sync outcome" to "FAILED")), key, ExtensionMatchingStatistics(0, 0, 0))
        assertNull(value.matching)
        assertNull(value.lastUpdatedAt)
        assertEquals(ExtensionDataUpdateStatus.FAILED, value.updateStatus)
    }

    @Test fun realEmptySuccessHasZeroCountsAndPartialIsExplicit() {
        val receipt = ProviderNavigationStoredState(rowsSource = key,
            syncStatistics = mapOf("Last sync outcome" to "COMMITTED", "Last committed sync" to updated.toString()))
        assertEquals(ExtensionMatchingStatistics(0, 0, 0),
            statisticsForReceipt(receipt, key, ExtensionMatchingStatistics(0, 0, 0)).matching)
        assertEquals(ExtensionDataUpdateStatus.PARTIAL, statisticsForReceipt(receipt.copy(
            syncStatistics = receipt.syncStatistics + ("Last sync outcome" to "COMMITTED_PARTIAL")), key, counts).updateStatus)
        assertEquals(ExtensionDataUpdateStatus.UNKNOWN, statisticsForReceipt(receipt.copy(
            syncStatistics = emptyMap()), key, counts).updateStatus)
    }
}

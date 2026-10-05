package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.data.repository.SourceSeriesMatchingService
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

class ExtensionCenterStatisticsRepository(
    private val sources: ExtensionSourceRepository,
    private val policy: ExtensionProductPolicyRepository,
    private val store: FileProviderNavigationStateStore,
    private val matching: SourceSeriesMatchingService,
) : ExtensionStatisticsRepository {
    override fun observe(key: ExtensionSelectionKey) = combine(
        sources.sources, policy.policy, store.state, matching.observeStatisticsSnapshot(key),
    ) { catalog, selection, receipt, measured ->
        val extension = catalog.usableExtension(key)
        val current = selection.activeReleaseSource == key && extension != null && receipt.source == key &&
            receipt.releaseGeneration == selection.releaseGeneration && receipt.packageDigest == extension.installedDigest &&
            receipt.packageGeneration == extension.packageGeneration
        if (!current || measured.first != receipt) ExtensionUserStatistics()
        else statisticsForReceipt(receipt, key, measured.second)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}

/** Only host-owned outcome codes and the committed data timestamp are projected into the product UI. */
internal fun statisticsForReceipt(receipt: ProviderNavigationStoredState, key: ExtensionSelectionKey,
    counts: ExtensionMatchingStatistics): ExtensionUserStatistics {
    val status = when (receipt.syncStatistics["Last sync outcome"]) {
        "COMMITTED" -> ExtensionDataUpdateStatus.SUCCESS
        "COMMITTED_PARTIAL" -> ExtensionDataUpdateStatus.PARTIAL
        "FAILED" -> ExtensionDataUpdateStatus.FAILED
        else -> ExtensionDataUpdateStatus.UNKNOWN
    }
    val updated = receipt.syncStatistics["Last committed sync"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return ExtensionUserStatistics(
        matching = counts.takeIf { receipt.rowsSource == key },
        lastUpdatedAt = updated,
        updateStatus = status,
    )
}

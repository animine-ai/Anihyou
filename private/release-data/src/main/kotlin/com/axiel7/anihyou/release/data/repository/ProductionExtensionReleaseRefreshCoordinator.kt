package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore
import com.axiel7.anihyou.release.data.extension.InstalledExtensionAccess
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex

/** Freshness is scoped to a verified identity, source selection and monotone package generation. */
class ProductionExtensionReleaseRefreshCoordinator(
    private val sources: ExtensionSourceRepository,
    private val policy: ExtensionProductPolicyRepository,
    private val installed: InstalledExtensionAccess,
    private val receipt: FileProviderNavigationStateStore,
    private val delegate: WorkScopedShadowRefreshCoordinator,
    private val clock: Clock,
    private val freshFor: Duration = Duration.ofHours(1),
) : ExtensionReleaseRefreshCoordinator {
    private val mutex = Mutex()

    init { require(!freshFor.isNegative && !freshFor.isZero) }

    override suspend fun refresh(workId: String, force: Boolean): ShadowRefreshOutcome {
        if (!mutex.tryLock()) return ShadowRefreshOutcome.Failed("BUSY", retryable = true)
        try {
            // A restarted process restores the installer journal before resolving selection.
            sources.restoreInstalled()
            val selection = policy.policy.value
            val key = selection.activeReleaseSource
                ?: return ShadowRefreshOutcome.Skipped("no-active-release-source")
            val current = installed.loadInstalled(key)
                ?: return ShadowRefreshOutcome.Skipped("active-release-source-unavailable")
            val old = receipt.state.value
            val success = old.syncStatistics["Last successful sync"]?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
            val now = clock.instant()
            if (!force && old.source == key && old.releaseGeneration == selection.releaseGeneration &&
                old.packageDigest == current.packageDigest && old.packageGeneration == current.packageGeneration &&
                success != null && !success.isAfter(now) && now.isBefore(success.plus(freshFor))) {
                return ShadowRefreshOutcome.Skipped("extension-data-fresh")
            }
            val result = delegate.refreshForWork(workId)
            // A concurrent switch/update is retried against the new selection, never committed as old data.
            return when {
                result is ShadowRefreshOutcome.Failed && result.reason == "stale-generation-token" ->
                    result.copy(retryable = true)
                result is ShadowRefreshOutcome.Committed && !result.refreshSucceeded ->
                    ShadowRefreshOutcome.Failed("extension-refresh-partial", retryable = true)
                else -> result
            }
        } finally { mutex.unlock() }
    }
}

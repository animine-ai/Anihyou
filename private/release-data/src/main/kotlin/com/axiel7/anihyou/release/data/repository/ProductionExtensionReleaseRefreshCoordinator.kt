package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.TriggeredShadowRefreshCoordinator
import kotlinx.coroutines.sync.Mutex

/**
 * Product entry for release-data refreshes. The trigger scope, the soft freshness per role and the typed
 * deferral live in the delegate, which resolves the active selection and pins one package for the run.
 * This class only serialises runs and restores the installer journal of a restarted process.
 */
class ProductionExtensionReleaseRefreshCoordinator(
    private val sources: com.axiel7.anihyou.release.core.source.ExtensionSourceRepository,
    private val delegate: TriggeredShadowRefreshCoordinator,
) : ExtensionReleaseRefreshCoordinator {
    private val mutex = Mutex()

    override suspend fun refresh(workId: String, trigger: ExtensionRefreshTrigger): ShadowRefreshOutcome {
        if (!mutex.tryLock()) return ShadowRefreshOutcome.Failed("BUSY", retryable = true)
        try {
            // A restarted process restores the installer journal before resolving selection.
            sources.restoreInstalled()
            val result = delegate.refreshForTrigger(workId, trigger)
            // A concurrent switch/update is retried against the new selection, never committed as old data.
            return if (result is ShadowRefreshOutcome.Failed && result.reason == "stale-generation-token")
                result.copy(retryable = true) else result
        } finally { mutex.unlock() }
    }
}

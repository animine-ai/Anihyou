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
    /**
     * Runs after every refresh, whatever it decided (fresh data that is still unbound needs it as much as new data), outside
     * the run lock. Best effort: a failure here never changes the outcome of the refresh.
     */
    private val afterRefresh: (suspend () -> Unit)? = null,
) : ExtensionReleaseRefreshCoordinator {
    private val mutex = Mutex()

    override suspend fun refresh(workId: String, trigger: ExtensionRefreshTrigger): ShadowRefreshOutcome {
        if (!mutex.tryLock()) return ShadowRefreshOutcome.Failed("BUSY", retryable = true)
        val result = try {
            // A restarted process restores the installer journal before resolving selection.
            sources.restoreInstalled()
            delegate.refreshForTrigger(workId, trigger)
        } finally { mutex.unlock() }
        if (result !is ShadowRefreshOutcome.Failed) {
            try { afterRefresh?.invoke() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.axiel7.anihyou.release.core.log.AppLog.w("sync", failure) { "after-refresh step failed: ${failure.javaClass.simpleName}" }
            }
        }
        // A concurrent switch/update is retried against the new selection, never committed as old data.
        return if (result is ShadowRefreshOutcome.Failed && result.reason == "stale-generation-token")
            result.copy(retryable = true) else result
    }
}

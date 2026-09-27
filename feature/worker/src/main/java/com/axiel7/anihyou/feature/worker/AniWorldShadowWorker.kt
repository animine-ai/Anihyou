package com.axiel7.anihyou.feature.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.api.AniWorldShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome

/** R2-independent worker. The core coordinator owns the OFF-by-default gate. */
class AniWorldShadowWorker(
    context: Context,
    params: WorkerParameters,
    private val coordinator: AniWorldShadowRefreshCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val outcome = coordinator.refresh()) {
        is ShadowRefreshOutcome.Committed,
        is ShadowRefreshOutcome.Skipped -> Result.success()
        is ShadowRefreshOutcome.Failed -> if (outcome.retryable) Result.retry() else Result.failure()
    }
}

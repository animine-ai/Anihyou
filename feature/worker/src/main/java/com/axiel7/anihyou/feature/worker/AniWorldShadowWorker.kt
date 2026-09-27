package com.axiel7.anihyou.feature.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.api.AniWorldShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome

enum class AniWorldShadowWorkerDecision { SUCCESS, RETRY, FAILURE }

internal suspend fun evaluateAniWorldShadowWorker(
    coordinator: AniWorldShadowRefreshCoordinator,
    oneShotCanary: Boolean,
): AniWorldShadowWorkerDecision = when (val outcome = coordinator.refresh()) {
    is ShadowRefreshOutcome.Committed,
    is ShadowRefreshOutcome.Skipped -> AniWorldShadowWorkerDecision.SUCCESS
    is ShadowRefreshOutcome.Failed -> if (outcome.retryable && !oneShotCanary) {
        AniWorldShadowWorkerDecision.RETRY
    } else {
        AniWorldShadowWorkerDecision.FAILURE
    }
}

/** R2-independent worker; the explicit one-shot canary never schedules a retry. */
class AniWorldShadowWorker(
    context: Context,
    params: WorkerParameters,
    private val coordinator: AniWorldShadowRefreshCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result =
        when (evaluateAniWorldShadowWorker(
            coordinator,
            oneShotCanary = inputData.getBoolean(INPUT_ONE_SHOT_CANARY, false),
        )) {
            AniWorldShadowWorkerDecision.SUCCESS -> Result.success()
            AniWorldShadowWorkerDecision.RETRY -> Result.retry()
            AniWorldShadowWorkerDecision.FAILURE -> Result.failure()
        }

    companion object {
        const val INPUT_ONE_SHOT_CANARY = "aniworld_shadow_one_shot_canary"
    }
}

package com.axiel7.anihyou.feature.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.api.ReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.RefreshOutcome
import com.axiel7.anihyou.release.core.api.RefreshReason
import com.axiel7.anihyou.release.core.sync.RefreshRetryPolicy

class ForecastRecheckWorker(
    context: Context,
    params: WorkerParameters,
    private val refreshCoordinator: ReleaseRefreshCoordinator,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (refreshCoordinator.refresh(RefreshReason.WORKER)) {
            is RefreshOutcome.Applied,
            is RefreshOutcome.Skipped,
            -> Result.success()
            is RefreshOutcome.Failed ->
                if (RefreshRetryPolicy.shouldRetry(runAttemptCount)) {
                    Result.retry()
                } else {
                    Result.failure()
                }
        }
}

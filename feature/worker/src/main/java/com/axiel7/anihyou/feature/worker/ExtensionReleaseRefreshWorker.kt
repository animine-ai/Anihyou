package com.axiel7.anihyou.feature.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import kotlinx.coroutines.CancellationException

internal suspend fun evaluateExtensionReleaseRefresh(
    coordinator: ExtensionReleaseRefreshCoordinator,
    workId: String,
    force: Boolean,
    attempt: Int,
): ExtensionSourceRefreshDecision {
    val outcome = try { coordinator.refresh(workId, force) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ShadowRefreshOutcome.Failed("extension-refresh-failure", retryable = true) }
    return when (outcome) {
        is ShadowRefreshOutcome.Committed, is ShadowRefreshOutcome.Skipped -> ExtensionSourceRefreshDecision.SUCCESS
        is ShadowRefreshOutcome.Failed -> if (outcome.retryable && attempt + 1 < MAX_RELEASE_REFRESH_ATTEMPTS)
            ExtensionSourceRefreshDecision.RETRY else ExtensionSourceRefreshDecision.FAILURE
    }
}

class ExtensionReleaseRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
    private val coordinator: ExtensionReleaseRefreshCoordinator,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = when (evaluateExtensionReleaseRefresh(
        coordinator, "${id}_$runAttemptCount", inputData.getBoolean(INPUT_FORCE, false), runAttemptCount,
    )) {
        ExtensionSourceRefreshDecision.SUCCESS -> Result.success()
        ExtensionSourceRefreshDecision.RETRY -> Result.retry()
        ExtensionSourceRefreshDecision.FAILURE -> Result.failure()
    }
    companion object { const val INPUT_FORCE = "extension_release_refresh_force" }
}

private const val MAX_RELEASE_REFRESH_ATTEMPTS = 3

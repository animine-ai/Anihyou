package com.axiel7.anihyou.feature.worker

import com.axiel7.anihyou.release.core.log.AppLog
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import kotlinx.coroutines.CancellationException

enum class ExtensionSourceRefreshDecision { SUCCESS, RETRY, FAILURE }

internal suspend fun evaluateExtensionSourceRefresh(
    repository: ExtensionSourceRepository,
    runAttemptCount: Int,
): ExtensionSourceRefreshDecision {
    val needsRetry = try {
        repository.refreshEnabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    if (!needsRetry) return ExtensionSourceRefreshDecision.SUCCESS
    return if (runAttemptCount + 1 < ExtensionSourceRefreshWorker.MAX_ATTEMPTS) {
        ExtensionSourceRefreshDecision.RETRY
    } else {
        ExtensionSourceRefreshDecision.FAILURE
    }
}

/** Retries transient or unexpected failures at most three times in total. */
class ExtensionSourceRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: ExtensionSourceRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppLog.i("worker") { "source refresh start attempt=$runAttemptCount" }
        val decision = evaluateExtensionSourceRefresh(repository, runAttemptCount)
        AppLog.i("worker") { "source refresh decision=$decision attempt=$runAttemptCount" }
        return when (decision) {
            ExtensionSourceRefreshDecision.SUCCESS -> Result.success()
            ExtensionSourceRefreshDecision.RETRY -> Result.retry()
            ExtensionSourceRefreshDecision.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

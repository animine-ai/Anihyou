package com.axiel7.anihyou.feature.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.source.ExtensionSourceScheduler
import java.util.concurrent.TimeUnit

internal fun extensionSourceRefreshConstraints(): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

class WorkManagerExtensionSourceScheduler(
    private val workManager: WorkManager,
) : ExtensionSourceScheduler {
    override fun scheduleRefresh() {
        val request = OneTimeWorkRequestBuilder<ExtensionSourceRefreshWorker>()
            .setConstraints(extensionSourceRefreshConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "extension-source-refresh"
        const val BACKOFF_MINUTES = 15L
    }
}

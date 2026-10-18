package com.axiel7.anihyou.feature.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<ExtensionSourceRefreshWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(extensionSourceRefreshConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(PERIODIC_WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "extension-source-refresh"
        const val PERIODIC_WORK_NAME = "extension-source-refresh-periodic"
        const val PERIODIC_HOURS = 6L
        const val BACKOFF_MINUTES = 15L
    }
}

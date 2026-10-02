package com.axiel7.anihyou.feature.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import java.util.concurrent.TimeUnit

class WorkManagerExtensionReleaseRefreshScheduler(private val manager: WorkManager) : ExtensionReleaseRefreshScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleDue() {
        val periodic = PeriodicWorkRequestBuilder<ExtensionReleaseRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        enqueue(NOW, force = false)
    }

    override fun scheduleNow() = enqueue(MANUAL, force = true)

    private fun enqueue(name: String, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<ExtensionReleaseRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(ExtensionReleaseRefreshWorker.INPUT_FORCE to force))
            .addTag(TAG).build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() { manager.cancelAllWorkByTag(TAG) }

    companion object {
        const val TAG = "extension-release-product-refresh"
        const val PERIODIC = "$TAG-periodic"
        const val NOW = "$TAG-due"
        const val MANUAL = "$TAG-manual"
    }
}

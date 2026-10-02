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
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        // A selection/generation change while due work is RUNNING must leave a follow-up check.
        // Each check resolves the current source and skips fresh data; no duplicate network refresh.
        enqueue(NOW, force = false, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    override fun scheduleNow() = enqueue(MANUAL, force = true)

    private fun enqueue(name: String, force: Boolean, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<ExtensionReleaseRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(ExtensionReleaseRefreshWorker.INPUT_FORCE to force))
            .addTag(TAG).build()
        manager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancel() { manager.cancelAllWorkByTag(TAG) }

    companion object {
        const val TAG = "extension-release-product-refresh"
        const val PERIODIC = "$TAG-periodic"
        const val NOW = "$TAG-due"
        const val MANUAL = "$TAG-manual"
    }
}

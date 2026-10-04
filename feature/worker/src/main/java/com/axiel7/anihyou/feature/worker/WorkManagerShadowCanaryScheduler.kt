package com.axiel7.anihyou.feature.worker

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.axiel7.anihyou.release.core.api.ShadowCanaryScheduler

internal fun aniworldShadowCanaryConstraints(): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

class WorkManagerShadowCanaryScheduler(
    private val workManager: WorkManager,
) : ShadowCanaryScheduler {
    override fun scheduleCanaryNow() {
        val request = OneTimeWorkRequestBuilder<AniWorldShadowWorker>()
            .setConstraints(aniworldShadowCanaryConstraints())
            .setInputData(workDataOf(AniWorldShadowWorker.INPUT_ONE_SHOT_CANARY to true))
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "aniworld-v3-shadow-canary-now"
    }
}

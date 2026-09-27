package com.axiel7.anihyou.feature.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.api.AniWorldShadowScheduler
import java.util.concurrent.TimeUnit

class WorkManagerAniWorldShadowScheduler(
    private val workManager: WorkManager,
) : AniWorldShadowScheduler {
    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<AniWorldShadowWorker>(60, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "aniworld-v3-shadow"
    }
}

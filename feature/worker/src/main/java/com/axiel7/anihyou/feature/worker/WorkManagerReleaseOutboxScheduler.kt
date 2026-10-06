package com.axiel7.anihyou.feature.worker

import androidx.work.WorkManager
import com.axiel7.anihyou.feature.worker.ReleaseOutboxDispatcherWorker.Companion.scheduleReleaseOutboxWork
import com.axiel7.anihyou.release.core.api.ReleaseOutboxScheduler

class WorkManagerReleaseOutboxScheduler(
    private val workManager: WorkManager,
) : ReleaseOutboxScheduler {
    init {
        schedule()
    }

    override fun schedule() {
        workManager.scheduleReleaseOutboxWork()
    }
}

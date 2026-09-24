package com.axiel7.anihyou.feature.worker

import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.api.ReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.api.ReleaseOutboxScheduler
import org.koin.dsl.module
import org.koin.plugin.module.dsl.worker

val workerModule = module {
    single { WorkManager.getInstance(get()) }
    worker<NotificationWorker>()
    worker<ReleaseOutboxDispatcherWorker>()
    worker<ForecastRecheckWorker>()
    single<ReleaseOutboxScheduler> { WorkManagerReleaseOutboxScheduler(get()) }
    single<ReleaseForecastRecheckScheduler> { WorkManagerReleaseForecastRecheckScheduler(get()) }
}

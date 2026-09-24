package com.axiel7.anihyou.feature.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.axiel7.anihyou.release.core.api.ReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.sync.ForecastRecheckAction
import com.axiel7.anihyou.release.core.sync.ForecastRecheckActionKind
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

class WorkManagerReleaseForecastRecheckScheduler(
    private val workManager: WorkManager,
    private val clock: Clock = Clock.systemUTC(),
) : ReleaseForecastRecheckScheduler {
    override fun apply(
        actions: List<ForecastRecheckAction>,
        cancelWorkKeys: List<String>,
    ) {
        cancelWorkKeys.forEach { workManager.cancelUniqueWork(uniqueName(it)) }
        actions.forEach { action ->
            val name = uniqueName(action.workKey)
            when (action.kind) {
                ForecastRecheckActionKind.SCHEDULE -> {
                    enqueue(name, action, ExistingWorkPolicy.REPLACE)
                }
                ForecastRecheckActionKind.KEEP -> {
                    enqueue(name, action, ExistingWorkPolicy.KEEP)
                }
                ForecastRecheckActionKind.CANCEL -> {
                    workManager.cancelUniqueWork(name)
                }
            }
        }
    }

    private fun enqueue(
        name: String,
        action: ForecastRecheckAction,
        policy: ExistingWorkPolicy,
    ) {
        val now = clock.instant()
        val runAt = action.runAt ?: now
        val delay = Duration.between(now, runAt)
            .toMillis()
            .coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ForecastRecheckWorker>()
            .setInputData(workDataOf(WORK_KEY to action.workKey))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                10L,
                TimeUnit.MINUTES,
            )
            .build()
        workManager.enqueueUniqueWork(name, policy, request)
    }

    private fun uniqueName(workKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "release-forecast-" + digest.take(32)
    }

    private companion object {
        const val WORK_KEY = "work_key"
    }
}

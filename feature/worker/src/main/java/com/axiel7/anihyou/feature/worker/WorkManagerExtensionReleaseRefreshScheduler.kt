package com.axiel7.anihyou.feature.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One chain of one-time works, no periodic job: every planned slot is its own unique work named by its
 * instant, enqueued with KEEP, so the successor of a running slot never replaces (and never cancels) it.
 * Foreground and process start share one unique work, so they cannot duplicate each other.
 */
class WorkManagerExtensionReleaseRefreshScheduler(
    private val manager: WorkManager,
    private val schedules: ExtensionRefreshScheduleRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ExtensionReleaseRefreshScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val slotMutex = Mutex()

    override fun scheduleDue() {
        // Earlier builds ran an hourly periodic Full job; it is replaced by the slot chain below.
        manager.cancelUniqueWork(LEGACY_PERIODIC)
        ensureSlotScheduled()
        enqueue(AUTO, ExtensionRefreshTrigger.PROCESS_START, ExistingWorkPolicy.KEEP)
    }

    override fun scheduleForeground() = enqueue(AUTO, ExtensionRefreshTrigger.FOREGROUND, ExistingWorkPolicy.KEEP)

    override fun scheduleNow() = enqueue(MANUAL, ExtensionRefreshTrigger.MANUAL, ExistingWorkPolicy.KEEP)

    override fun scheduleDeferred(trigger: ExtensionRefreshTrigger, notBefore: Instant, chain: Int) {
        val now = clock.instant()
        // The name carries the time, so a deferral enqueued by a running deferred work is a different work.
        enqueue("$DEFERRED-${notBefore.epochSecond}", trigger, ExistingWorkPolicy.KEEP,
            delay = Duration.between(now, notBefore).coerceAtLeast(Duration.ZERO), chain = chain)
    }

    override fun ensureSlotScheduled() {
        scope.launch {
            try { slotMutex.withLock { ensureSlot() } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* the next app start or slot run repairs the chain */ }
        }
    }

    private suspend fun ensureSlot() {
        val schedule = schedules.schedule.value
        val zoneId = zone()
        val now = clock.instant()
        val next = schedule.nextSlotAfter(now, zoneId)
        val slotTag = "$SLOT_TAG_PREFIX${next.epochSecond}"
        enqueue("$SLOT-${next.epochSecond}", ExtensionRefreshTrigger.SCHEDULED_SLOT, ExistingWorkPolicy.KEEP,
            delay = Duration.between(now, next), extraTag = slotTag)
        // A changed schedule leaves older future slots pending: retire exactly those. A slot that is running
        // or retrying has a slot instant in the past and is never touched.
        manager.getWorkInfosByTagFlow(SLOT_TAG).first().forEach { info ->
            val pending = info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED
            val instant = info.tags.firstOrNull { it.startsWith(SLOT_TAG_PREFIX) }
                ?.removePrefix(SLOT_TAG_PREFIX)?.toLongOrNull()
            if (pending && instant != null && instant > now.epochSecond && instant != next.epochSecond) {
                manager.cancelWorkById(info.id)
            }
        }
        // One missed slot is made up once: no slot ran since the last planned instant, and it is not too recent.
        val last = schedule.lastSlotAtOrBefore(now, zoneId)
        val lastRun = schedules.lastSlotRun()
        if (lastRun != null && lastRun.isBefore(last) && Duration.between(last, now) >= CATCH_UP_GRACE) {
            enqueue(CATCH_UP, ExtensionRefreshTrigger.SCHEDULED_SLOT, ExistingWorkPolicy.KEEP)
        }
    }

    private fun enqueue(
        name: String, trigger: ExtensionRefreshTrigger, policy: ExistingWorkPolicy,
        delay: Duration = Duration.ZERO, chain: Int = 0, extraTag: String? = null,
    ) {
        val builder = OneTimeWorkRequestBuilder<ExtensionReleaseRefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                ExtensionReleaseRefreshWorker.INPUT_TRIGGER to trigger.name,
                ExtensionReleaseRefreshWorker.INPUT_CHAIN to chain,
            ))
            .addTag(TAG)
        if (trigger == ExtensionRefreshTrigger.SCHEDULED_SLOT) builder.addTag(SLOT_TAG)
        extraTag?.let(builder::addTag)
        if (!delay.isZero) builder.setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
        manager.enqueueUniqueWork(name, policy, builder.build())
    }

    override fun cancel() { manager.cancelAllWorkByTag(TAG) }

    companion object {
        const val TAG = "extension-release-product-refresh"
        const val SLOT_TAG = "$TAG-slot"
        const val SLOT_TAG_PREFIX = "$TAG-slot-at-"
        const val SLOT = "$TAG-slot"
        const val AUTO = "$TAG-auto"
        const val MANUAL = "$TAG-manual"
        const val DEFERRED = "$TAG-deferred"
        const val CATCH_UP = "$TAG-catch-up"
        /** The hourly periodic work of earlier builds. */
        const val LEGACY_PERIODIC = "$TAG-periodic"
        /** A missed slot is not made up in the first minutes after it, so a slot that is just late is not doubled. */
        val CATCH_UP_GRACE: Duration = Duration.ofMinutes(15)
    }
}

package com.axiel7.anihyou.feature.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExtensionReleaseRefreshWorkerTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    // 2026-10-03 14:00 CEST. The next default slot is 01:00 CEST on the 4th = 23:00Z.
    private val now = Instant.parse("2026-10-03T12:00:00Z")

    private class Schedules(initial: ExtensionRefreshSchedule = ExtensionRefreshSchedule(), var last: Instant? = null) :
        ExtensionRefreshScheduleRepository {
        override val schedule = MutableStateFlow(initial)
        override suspend fun update(schedule: ExtensionRefreshSchedule) { this.schedule.value = schedule }
        override suspend fun lastSlotRun(): Instant? = last
        override suspend fun recordSlotRun(at: Instant) { last = at }
    }

    private fun scheduler(manager: WorkManager, schedules: Schedules = Schedules()) =
        WorkManagerExtensionReleaseRefreshScheduler(manager, schedules, Clock.fixed(now, ZoneOffset.UTC),
            { berlin }, CoroutineScope(Dispatchers.Unconfined))

    private fun manager(infos: List<WorkInfo> = emptyList()): WorkManager = mockk<WorkManager>(relaxed = true).also {
        every { it.getWorkInfosByTagFlow(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG) } returns flowOf(infos)
    }

    @Test fun `the slot chain enqueues the next planned slot as a keep-unique work and no periodic job`() {
        val manager = manager()
        val enqueued = mutableMapOf<String, OneTimeWorkRequest>()
        every { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } answers {
            enqueued[firstArg()] = thirdArg(); mockk(relaxed = true)
        }
        scheduler(manager).ensureSlotScheduled()
        val slotName = "${WorkManagerExtensionReleaseRefreshScheduler.SLOT}-${Instant.parse("2026-10-03T23:00:00Z").epochSecond}"
        val slot = requireNotNull(enqueued[slotName]) { "expected $slotName in ${enqueued.keys}" }
        verify { manager.enqueueUniqueWork(slotName, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        assertEquals(11 * 3_600_000L, slot.workSpec.initialDelay)
        assertEquals(NetworkType.CONNECTED, slot.workSpec.constraints.requiredNetworkType)
        assertEquals(ExtensionRefreshTrigger.SCHEDULED_SLOT.name,
            slot.workSpec.input.getString(ExtensionReleaseRefreshWorker.INPUT_TRIGGER))
        assertTrue(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG in slot.tags)
        verify(exactly = 0) { manager.enqueueUniquePeriodicWork(any(), any(), any()) }
    }

    @Test fun `a changed schedule retires older future slots but never a running or retrying one`() {
        val future = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED,
            setOf(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG,
                "${WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG_PREFIX}${Instant.parse("2026-10-04T05:00:00Z").epochSecond}"))
        val running = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING,
            setOf(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG,
                "${WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG_PREFIX}${Instant.parse("2026-10-03T11:00:00Z").epochSecond}"))
        val retrying = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED,
            setOf(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG,
                "${WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG_PREFIX}${Instant.parse("2026-10-03T05:00:00Z").epochSecond}"))
        val manager = manager(listOf(future, running, retrying))
        scheduler(manager).ensureSlotScheduled()
        verify { manager.cancelWorkById(future.id) }
        verify(exactly = 0) { manager.cancelWorkById(running.id) }
        verify(exactly = 0) { manager.cancelWorkById(retrying.id) }
    }

    @Test fun `a missed slot is made up once and a slot that ran is not`() {
        // The 07:00 CEST slot of a 6 hour grid (05:00Z) was missed: the last run was the night before.
        val grid = Schedules(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_6),
            last = Instant.parse("2026-10-02T23:00:00Z"))
        val missed = manager()
        scheduler(missed, grid).ensureSlotScheduled()
        verify { missed.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.CATCH_UP, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        val ran = manager()
        scheduler(ran, Schedules(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_6),
            last = Instant.parse("2026-10-03T11:05:00Z"))).ensureSlotScheduled()
        verify(exactly = 0) { ran.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.CATCH_UP, any(), any<OneTimeWorkRequest>()) }
        // A slot that was only a few minutes ago is just late, not missed.
        val late = manager()
        val recent = Clock.fixed(Instant.parse("2026-10-03T11:05:00Z"), ZoneOffset.UTC)
        WorkManagerExtensionReleaseRefreshScheduler(late, grid, recent, { berlin }, CoroutineScope(Dispatchers.Unconfined))
            .ensureSlotScheduled()
        verify(exactly = 0) { late.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.CATCH_UP, any(), any<OneTimeWorkRequest>()) }
    }

    @Test fun `process start and foreground share one keep-unique work, manual and deferred have their own`() {
        val manager = manager()
        val requests = mutableListOf<Pair<String, OneTimeWorkRequest>>()
        every { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } answers {
            requests += firstArg<String>() to thirdArg<OneTimeWorkRequest>(); mockk(relaxed = true)
        }
        val scheduler = scheduler(manager)
        scheduler.scheduleDue()
        scheduler.scheduleForeground()
        scheduler.scheduleNow()
        scheduler.scheduleDeferred(ExtensionRefreshTrigger.SCHEDULED_SLOT, now.plusSeconds(1800), chain = 1)
        verify { manager.cancelUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.LEGACY_PERIODIC) }
        val auto = requests.filter { it.first == WorkManagerExtensionReleaseRefreshScheduler.AUTO }
        assertEquals(2, auto.size)
        assertEquals(setOf(ExtensionRefreshTrigger.PROCESS_START.name, ExtensionRefreshTrigger.FOREGROUND.name),
            auto.map { it.second.workSpec.input.getString(ExtensionReleaseRefreshWorker.INPUT_TRIGGER) }.toSet())
        verify(exactly = 2) { manager.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.AUTO, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        verify { manager.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.MANUAL, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        val deferred = requests.single { it.first.startsWith(WorkManagerExtensionReleaseRefreshScheduler.DEFERRED) }.second
        assertEquals(1_800_000L, deferred.workSpec.initialDelay)
        assertEquals(1, deferred.workSpec.input.getInt(ExtensionReleaseRefreshWorker.INPUT_CHAIN, 0))
        assertEquals(60_000L, auto.first().second.workSpec.backoffDelayDuration)
        scheduler.cancel()
        verify { manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG) }
    }

    @Test fun `typed skips finish the work, only transient failures retry, and cancellation is never swallowed`() = runBlocking {
        val received = mutableListOf<Pair<String, ExtensionRefreshTrigger>>()
        val fresh = ExtensionReleaseRefreshCoordinator { id, trigger ->
            received += id to trigger
            ShadowRefreshOutcome.Skipped("extension-data-fresh", now.plusSeconds(900))
        }
        assertEquals(ExtensionSourceRefreshDecision.SUCCESS,
            evaluateExtensionReleaseRefresh(fresh, "restart", ExtensionRefreshTrigger.PROCESS_START, 0).decision)
        assertEquals(listOf("restart" to ExtensionRefreshTrigger.PROCESS_START), received)
        val deferred = ExtensionReleaseRefreshCoordinator { _, _ ->
            ShadowRefreshOutcome.Skipped("extension-budget-deferred", now.plusSeconds(7200))
        }
        assertEquals(ExtensionSourceRefreshDecision.SUCCESS,
            evaluateExtensionReleaseRefresh(deferred, "deferred", ExtensionRefreshTrigger.SCHEDULED_SLOT, 0).decision)
        val retry = ExtensionReleaseRefreshCoordinator { _, _ -> ShadowRefreshOutcome.Failed("NETWORK", true) }
        assertEquals(ExtensionSourceRefreshDecision.RETRY,
            evaluateExtensionReleaseRefresh(retry, "attempt", ExtensionRefreshTrigger.MANUAL, 0).decision)
        assertEquals(ExtensionSourceRefreshDecision.FAILURE,
            evaluateExtensionReleaseRefresh(retry, "attempt", ExtensionRefreshTrigger.MANUAL, 2).decision)
        val permanent = ExtensionReleaseRefreshCoordinator { _, _ -> ShadowRefreshOutcome.Failed("TRUST", false) }
        assertEquals(ExtensionSourceRefreshDecision.FAILURE,
            evaluateExtensionReleaseRefresh(permanent, "attempt", ExtensionRefreshTrigger.MANUAL, 0).decision)
        val cancel = ExtensionReleaseRefreshCoordinator { _, _ -> throw CancellationException() }
        var cancelled = false
        try { evaluateExtensionReleaseRefresh(cancel, "cancel", ExtensionRefreshTrigger.MANUAL, 0) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}

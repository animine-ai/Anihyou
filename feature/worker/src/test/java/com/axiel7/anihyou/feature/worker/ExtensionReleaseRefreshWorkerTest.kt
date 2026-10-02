package com.axiel7.anihyou.feature.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExtensionReleaseRefreshWorkerTest {
    @Test fun `source changes append a due check while manual taps deduplicate and network is constrained`() {
        val manager = mockk<WorkManager>(relaxed = true)
        val due = slot<OneTimeWorkRequest>()
        val manual = slot<OneTimeWorkRequest>()
        val periodic = slot<PeriodicWorkRequest>()
        every { manager.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE, capture(due)) } returns mockk(relaxed = true)
        every { manager.enqueueUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.MANUAL,
            ExistingWorkPolicy.KEEP, capture(manual)) } returns mockk(relaxed = true)
        every { manager.enqueueUniquePeriodicWork(WorkManagerExtensionReleaseRefreshScheduler.PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP, capture(periodic)) } returns mockk(relaxed = true)
        val scheduler = WorkManagerExtensionReleaseRefreshScheduler(manager)
        scheduler.scheduleDue()
        scheduler.scheduleNow()
        assertEquals(NetworkType.CONNECTED, due.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(60_000L, due.captured.workSpec.backoffDelayDuration)
        assertEquals(3_600_000L, periodic.captured.workSpec.intervalDuration)
        assertEquals(3_600_000L, periodic.captured.workSpec.initialDelay)
        assertFalse(due.captured.workSpec.input.getBoolean(ExtensionReleaseRefreshWorker.INPUT_FORCE, true))
        assertTrue(manual.captured.workSpec.input.getBoolean(ExtensionReleaseRefreshWorker.INPUT_FORCE, false))
        scheduler.cancel()
        verify { manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG) }
    }

    @Test fun `freshness skips and failures are bounded without swallowing cancellation`() = runBlocking {
        val received = mutableListOf<Pair<String, Boolean>>()
        val fresh = ExtensionReleaseRefreshCoordinator { id, force ->
            received += id to force
            ShadowRefreshOutcome.Skipped("extension-data-fresh")
        }
        assertEquals(ExtensionSourceRefreshDecision.SUCCESS, evaluateExtensionReleaseRefresh(fresh, "restart", false, 0))
        assertEquals(listOf("restart" to false), received)
        val retry = ExtensionReleaseRefreshCoordinator { _, _ -> ShadowRefreshOutcome.Failed("NETWORK", true) }
        assertEquals(ExtensionSourceRefreshDecision.RETRY, evaluateExtensionReleaseRefresh(retry, "attempt", true, 0))
        assertEquals(ExtensionSourceRefreshDecision.FAILURE, evaluateExtensionReleaseRefresh(retry, "attempt", true, 2))
        val permanent = ExtensionReleaseRefreshCoordinator { _, _ -> ShadowRefreshOutcome.Failed("TRUST", false) }
        assertEquals(ExtensionSourceRefreshDecision.FAILURE, evaluateExtensionReleaseRefresh(permanent, "attempt", true, 0))
        val cancel = ExtensionReleaseRefreshCoordinator { _, _ -> throw CancellationException() }
        var cancelled = false
        try { evaluateExtensionReleaseRefresh(cancel, "cancel", true, 0) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}

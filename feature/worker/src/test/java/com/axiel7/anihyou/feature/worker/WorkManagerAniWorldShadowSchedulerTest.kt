package com.axiel7.anihyou.feature.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.api.AniWorldShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerAniWorldShadowSchedulerTest {
    @Test
    fun schedulesUniqueConnectedOneShotWithKeepPolicy() {
        val workManager = mockk<WorkManager>()
        val operation = mockk<Operation>(relaxed = true)
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                WorkManagerAniWorldShadowScheduler.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                capture(request),
            )
        } returns operation

        val scheduler = WorkManagerAniWorldShadowScheduler(workManager)
        scheduler.scheduleCanaryNow()
        scheduler.scheduleCanaryNow()

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                WorkManagerAniWorldShadowScheduler.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
        assertTrue(request.isCaptured)
        assertEquals(NetworkType.CONNECTED, aniworldShadowCanaryConstraints().requiredNetworkType)
    }

    @Test
    fun workerInvokesTheExistingCoordinatorOnce() = runBlocking {
        var calls = 0
        val coordinator = AniWorldShadowRefreshCoordinator {
            calls += 1
            ShadowRefreshOutcome.Skipped("test")
        }

        assertEquals(
            AniWorldShadowWorkerDecision.SUCCESS,
            evaluateAniWorldShadowWorker(coordinator, oneShotCanary = true),
        )
        assertEquals(1, calls)
    }

    @Test
    fun canaryFailureDoesNotScheduleAutomaticRetry() = runBlocking {
        val coordinator = AniWorldShadowRefreshCoordinator {
            ShadowRefreshOutcome.Failed("bounded-test", retryable = true)
        }

        assertEquals(
            AniWorldShadowWorkerDecision.FAILURE,
            evaluateAniWorldShadowWorker(coordinator, oneShotCanary = true),
        )
        assertEquals(
            AniWorldShadowWorkerDecision.RETRY,
            evaluateAniWorldShadowWorker(coordinator, oneShotCanary = false),
        )
    }
}


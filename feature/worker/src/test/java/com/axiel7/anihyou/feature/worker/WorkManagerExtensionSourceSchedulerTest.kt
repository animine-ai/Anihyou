package com.axiel7.anihyou.feature.worker

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkManagerExtensionSourceSchedulerTest {
    @Test
    fun schedulesUniqueConnectedOneShotWithExponentialBackoff() {
        val workManager = mockk<WorkManager>()
        val operation = mockk<Operation>(relaxed = true)
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                WorkManagerExtensionSourceScheduler.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                capture(request),
            )
        } returns operation

        WorkManagerExtensionSourceScheduler(workManager).scheduleRefresh()

        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.captured.workSpec.backoffPolicy)
        assertEquals(
            15L * 60L * 1000L,
            request.captured.workSpec.backoffDelayDuration,
        )
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                WorkManagerExtensionSourceScheduler.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun permanentResultCompletesAndTransientResultRetriesOnlyBeforeThirdAttempt() = runBlocking {
        assertEquals(
            ExtensionSourceRefreshDecision.SUCCESS,
            evaluateExtensionSourceRefresh(repository { false }, runAttemptCount = 0),
        )
        val transientRepository = repository { true }
        assertEquals(
            ExtensionSourceRefreshDecision.RETRY,
            evaluateExtensionSourceRefresh(transientRepository, runAttemptCount = 0),
        )
        assertEquals(
            ExtensionSourceRefreshDecision.RETRY,
            evaluateExtensionSourceRefresh(transientRepository, runAttemptCount = 1),
        )
        assertEquals(
            ExtensionSourceRefreshDecision.FAILURE,
            evaluateExtensionSourceRefresh(transientRepository, runAttemptCount = 2),
        )
    }

    @Test
    fun unexpectedFailureRetriesAtMostThreeAttemptsAndCancellationPropagates() = runBlocking {
        val failingRepository = repository { throw IllegalStateException("unexpected") }
        assertEquals(
            ExtensionSourceRefreshDecision.RETRY,
            evaluateExtensionSourceRefresh(failingRepository, runAttemptCount = 0),
        )
        assertEquals(
            ExtensionSourceRefreshDecision.RETRY,
            evaluateExtensionSourceRefresh(failingRepository, runAttemptCount = 1),
        )
        assertEquals(
            ExtensionSourceRefreshDecision.FAILURE,
            evaluateExtensionSourceRefresh(failingRepository, runAttemptCount = 2),
        )
        var cancellationPropagated = false
        try {
            evaluateExtensionSourceRefresh(
                repository { throw CancellationException("cancelled") },
                runAttemptCount = 0,
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }

    private fun repository(refresh: suspend () -> Boolean): ExtensionSourceRepository =
        object : ExtensionSourceRepository {
            override val sources = MutableStateFlow<List<ExtensionSource>>(emptyList())
            override suspend fun add(url: String): AddExtensionSourceResult =
                AddExtensionSourceResult.InvalidUrl
            override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun refresh(sourceId: String) = Unit
            override suspend fun refreshEnabled(): Boolean = refresh()
            override suspend fun activate(sourceId: String, extensionId: String) = Unit
        }
}

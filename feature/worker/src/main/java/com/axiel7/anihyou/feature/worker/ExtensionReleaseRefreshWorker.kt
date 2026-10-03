package com.axiel7.anihyou.feature.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal data class ExtensionReleaseRefreshEvaluation(
    val decision: ExtensionSourceRefreshDecision,
    val outcome: ShadowRefreshOutcome,
)

internal suspend fun evaluateExtensionReleaseRefresh(
    coordinator: ExtensionReleaseRefreshCoordinator,
    workId: String,
    trigger: ExtensionRefreshTrigger,
    attempt: Int,
): ExtensionReleaseRefreshEvaluation {
    val outcome = try { coordinator.refresh(workId, trigger) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ShadowRefreshOutcome.Failed("extension-refresh-failure", retryable = true) }
    val decision = when (outcome) {
        // A typed skip (fresh, deferred, auto window) is a finished run: waiting for its time is the answer,
        // not a retry. Only a transient failure retries, with the exponential backoff of the work request.
        is ShadowRefreshOutcome.Committed, is ShadowRefreshOutcome.Skipped -> ExtensionSourceRefreshDecision.SUCCESS
        is ShadowRefreshOutcome.Failed -> if (outcome.retryable && attempt + 1 < MAX_RELEASE_REFRESH_ATTEMPTS)
            ExtensionSourceRefreshDecision.RETRY else ExtensionSourceRefreshDecision.FAILURE
    }
    return ExtensionReleaseRefreshEvaluation(decision, outcome)
}

class ExtensionReleaseRefreshWorker(
    context: Context,
    parameters: WorkerParameters,
    private val coordinator: ExtensionReleaseRefreshCoordinator,
    private val scheduler: ExtensionReleaseRefreshScheduler,
    private val schedules: ExtensionRefreshScheduleRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(INPUT_TRIGGER)?.let { runCatching { ExtensionRefreshTrigger.valueOf(it) }.getOrNull() }
            // Work enqueued by an earlier build only knows the force flag.
            ?: if (inputData.getBoolean(INPUT_FORCE, false)) ExtensionRefreshTrigger.MANUAL else ExtensionRefreshTrigger.PROCESS_START
        val chain = inputData.getInt(INPUT_CHAIN, 0)
        // The successor is scheduled before the run, under its own name: a crash or retry cannot end the chain.
        if (trigger == ExtensionRefreshTrigger.SCHEDULED_SLOT) scheduler.ensureSlotScheduled()
        val evaluation = evaluateExtensionReleaseRefresh(coordinator, "${id}_$runAttemptCount", trigger, runAttemptCount)
        val outcome = evaluation.outcome
        if (outcome is ShadowRefreshOutcome.Skipped && outcome.reason == REASON_BUDGET_DEFERRED &&
            outcome.nextEligibleAt != null && chain < MAX_DEFERRED_CHAIN) {
            // Wait for the hard limit to lapse instead of retrying; the chain is short and then ends.
            scheduler.scheduleDeferred(trigger, outcome.nextEligibleAt!!, chain + 1)
        }
        if (trigger == ExtensionRefreshTrigger.SCHEDULED_SLOT && evaluation.decision != ExtensionSourceRefreshDecision.RETRY) {
            schedules.recordSlotRun(Instant.now())
        }
        return when (evaluation.decision) {
            ExtensionSourceRefreshDecision.SUCCESS -> Result.success()
            ExtensionSourceRefreshDecision.RETRY -> Result.retry()
            ExtensionSourceRefreshDecision.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val INPUT_TRIGGER = "extension_release_refresh_trigger"
        const val INPUT_CHAIN = "extension_release_refresh_chain"
        /** Input of builds before the trigger split. */
        const val INPUT_FORCE = "extension_release_refresh_force"
        const val REASON_BUDGET_DEFERRED = "extension-budget-deferred"
        const val MAX_DEFERRED_CHAIN = 3
    }
}

private const val MAX_RELEASE_REFRESH_ATTEMPTS = 3

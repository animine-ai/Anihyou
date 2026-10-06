package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseState

data class ReconciliationRequest(
    val expectedGeneration: Long,
    val activeGeneration: Long,
    val previous: ReleaseState?,
    val snapshot: ReleaseSnapshot,
    val accountProgress: Int,
    val revision: Long? = null,
) {
    init {
        require(expectedGeneration >= 0L) { "expected generation must be non-negative" }
        require(activeGeneration >= 0L) { "active generation must be non-negative" }
    }
}

sealed interface ReconciliationResult {
    data class Applied(
        val generation: Long,
        val reduction: ReductionResult,
    ) : ReconciliationResult

    data class IgnoredStaleGeneration(
        val expectedGeneration: Long,
        val activeGeneration: Long,
    ) : ReconciliationResult
}

object ReleaseReconciler {
    fun reconcile(request: ReconciliationRequest): ReconciliationResult {
        if (request.expectedGeneration != request.activeGeneration) {
            return ReconciliationResult.IgnoredStaleGeneration(
                expectedGeneration = request.expectedGeneration,
                activeGeneration = request.activeGeneration,
            )
        }
        return ReconciliationResult.Applied(
            generation = request.activeGeneration,
            reduction = ReleaseReducer.reduce(
                previous = request.previous,
                snapshot = request.snapshot,
                accountProgress = request.accountProgress,
                revision = request.revision,
            ),
        )
    }
}

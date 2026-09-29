package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.ExpectedSourceInstance
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.extension.ExtensionEvidenceAuthorityAdapter
import com.axiel7.anihyou.release.data.extension.ExtensionHostCoordinator
import com.axiel7.anihyou.release.data.extension.ExtensionHostFailureCode
import com.axiel7.anihyou.release.data.extension.ExtensionHostResult
import com.axiel7.anihyou.release.data.extension.ExtensionRunRequest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Extension-only V3 Shadow route. WorkManager's stable ID fences retries, while the coordinator
 * loads and pins one verified package for the complete plan/transport/parse operation. Neither
 * parsing nor provider URL construction occurs here. R2 remains a separate production route.
 */
class ExtensionShadowSyncOrchestrator(
    private val host: ExtensionHostCoordinator?,
    private val authority: ExtensionEvidenceAuthorityAdapter,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val targets: suspend () -> List<ExtensionTargetV1> = { emptyList() },
) : WorkScopedShadowRefreshCoordinator {
    private val mutex = Mutex()

    override suspend fun refresh(): ShadowRefreshOutcome = refreshForWork(UUID.randomUUID().toString())

    override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome = mutex.withLock {
        val coordinator = host ?: return@withLock ShadowRefreshOutcome.Skipped("extension-host-unprovisioned")
        require(workId.matches(Regex("[A-Za-z0-9_-]{1,111}")))
        val generationId = "aw-ext-shadow-v1:$workId"
        try {
            reconciliation.importBaseline()
            if (reconciliation.hasCompletedCycle(generationId))
                return@withLock ShadowRefreshOutcome.Skipped("generation-already-committed")
            val result = coordinator.execute(ExtensionRunRequest(
                providerId = ProviderId.parse("de.aniworld"), generationId = generationId,
                sourceRoles = SOURCE_ROLES, targets = targets()))
            when (result) {
                is ExtensionHostResult.Failed -> ShadowRefreshOutcome.Failed(
                    result.code.name, result.code in RETRYABLE)
                is ExtensionHostResult.Completed -> {
                    check(result.receipt.generationId == generationId)
                    val evidence = authority.project(result)
                    val started = Instant.parse(result.receipt.startedAt)
                    val completed = Instant.parse(result.receipt.completedAt)
                    val sourceObservations = SOURCE_ROLES.sortedBy(SourceRole::ordinal).map { role ->
                        val type = ROLE_TYPES.getValue(role)
                        val items = evidence.filter { it.sourceType == type }
                        CycleSourceObservation(
                            instanceId = "aw:extension:${role.name.lowercase()}:v1",
                            sourceType = type, targetKey = "aniworld:list:${role.name.lowercase()}",
                            track = null,
                            result = if (items.isEmpty()) CycleResult.INCOMPLETE else CycleResult.PARTIAL_SUCCESS,
                            health = if (items.isEmpty()) SourceHealthStatus.DEGRADED else SourceHealthStatus.HEALTHY,
                            observedAt = completed, evidence = items)
                    }
                    val cycle = CompletedObservationCycle(generationId, SCOPE_ID, started, completed,
                        AbsencePolicySnapshot(), sourceObservations,
                        sourceObservations.map {
                            ExpectedSourceInstance(it.instanceId, it.sourceType, it.targetKey, it.track, false)
                        })
                    reconciliation.persistCompletedCycle(cycle)
                    ShadowRefreshOutcome.Committed(generationId, cycle)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled // WorkManager cancellation tears down runtime and active HTTP calls.
        } catch (failure: Exception) {
            ShadowRefreshOutcome.Failed(failure::class.simpleName ?: "extension-shadow-failure", false)
        }
    }

    companion object {
        private const val SCOPE_ID = "aniworld:extension:shadow:v1"
        private val SOURCE_ROLES = setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT)
        private val ROLE_TYPES = mapOf(
            SourceRole.CALENDAR to ReleaseSourceType.ANIWORLD_CALENDAR,
            SourceRole.RECENT to ReleaseSourceType.ANIWORLD_RECENT,
            SourceRole.POSTPONEMENT to ReleaseSourceType.ANIWORLD_POSTPONEMENT)
        private val RETRYABLE = setOf(ExtensionHostFailureCode.BUSY, ExtensionHostFailureCode.RUNTIME_FAILURE)
    }
}

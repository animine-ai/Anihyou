package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.extension.ExtensionReportOutcome
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.ExpectedSourceInstance
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.extension.ExtensionEvidenceAuthorityAdapter
import com.axiel7.anihyou.release.data.extension.ExtensionHostCoordinator
import com.axiel7.anihyou.release.data.extension.ExtensionHostFailureCode
import com.axiel7.anihyou.release.data.extension.ExtensionHostResult
import com.axiel7.anihyou.release.data.extension.ExtensionRunRequest
import com.axiel7.anihyou.release.data.extension.ExtensionTargetSource
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Extension-only V3 Shadow route. Room provides durable generation fencing and idempotent commit;
 * the target source supplies one immutable target snapshot and the coordinator pins one verified
 * package for the complete plan/transport/parse operation. R2 remains a separate product route.
 */
class ExtensionShadowSyncOrchestrator(
    private val host: ExtensionHostCoordinator?,
    private val authority: ExtensionEvidenceAuthorityAdapter,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val generations: RoomExtensionShadowGenerationStore,
    private val targetSource: ExtensionTargetSource,
    private val clock: Clock = Clock.systemUTC(),
    private val providerId: ProviderId = ProviderId.parse("aniworld"),
    private val sourceRoles: Set<SourceRole> = SOURCE_ROLES,
    private val enabledTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
    private val commitGuard: suspend (suspend () -> Boolean) -> Boolean = { it() },
    private val currentSelection: suspend () -> Boolean = { true },
) : WorkScopedShadowRefreshCoordinator {
    private val mutex = Mutex()

    override suspend fun refresh(): ShadowRefreshOutcome = refreshForWork(UUID.randomUUID().toString())

    override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome = mutex.withLock {
        val coordinator = host ?: return@withLock ShadowRefreshOutcome.Skipped("extension-host-unprovisioned")
        require(workId.matches(WORK_ID_PATTERN))
        var token: ExtensionShadowGenerationToken? = null
        try {
            reconciliation.importBaseline()
            when (val claim = generations.claim(workId, clock.instant())) {
                ExtensionShadowGenerationClaim.AlreadyCommitted ->
                    return@withLock ShadowRefreshOutcome.Skipped("generation-already-committed")
                ExtensionShadowGenerationClaim.Busy ->
                    return@withLock ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.BUSY.name, retryable = true)
                is ExtensionShadowGenerationClaim.Acquired -> token = claim.token
            }
            val lease = checkNotNull(token)
            val targets = targetSource.targets()
            val result = coordinator.execute(ExtensionRunRequest(
                providerId = providerId,
                generationId = lease.executionGenerationId,
                sourceRoles = sourceRoles,
                targets = targets.map { it.target },
            ))
            when (result) {
                is ExtensionHostResult.Failed -> {
                    val now = clock.instant()
                    generations.abort(lease, result.code.name, now,
                        if (result.code == ExtensionHostFailureCode.BUSY || !currentSelection()) emptyList() else blockedHealth(result.code, now))
                    ShadowRefreshOutcome.Failed(result.code.name, result.code in RETRYABLE)
                }
                is ExtensionHostResult.Completed -> {
                    check(result.receipt.generationId == lease.executionGenerationId)
                    val evidence = authority.project(result).filter { it.languageTrack?.name in enabledTracks }
                    val started = Instant.parse(result.receipt.startedAt)
                    val completed = Instant.parse(result.receipt.completedAt)
                    val health = sourceHealth(result, completed)
                    val healthByType = health.associateBy { it.sourceType }
                    val sourceObservations = LIST_ROLES.sortedBy(SourceRole::ordinal).map { role ->
                        val type = ROLE_TYPES.getValue(role)
                        val items = evidence.filter { it.sourceType == type }
                        CycleSourceObservation(
                            instanceId = "aw:extension:${role.name.lowercase()}:v1",
                            sourceType = type,
                            targetKey = "aniworld:list:${role.name.lowercase()}",
                            track = null,
                            result = if (items.isEmpty()) CycleResult.INCOMPLETE else CycleResult.PARTIAL_SUCCESS,
                            health = healthByType[type]?.status ?: SourceHealthStatus.UNKNOWN,
                            observedAt = completed,
                            evidence = items,
                        )
                    } + targets.map { target ->
                        val type = ReleaseSourceType.ANIWORLD_DIRECT_PAGE
                        val items = evidence.filter {
                            it.sourceType == type &&
                                com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity.from(it)?.key == target.canonicalKey
                        }
                        val track = when (target.target.track) {
                            com.axiel7.anihyou.release.core.extension.ObservationTrack.DE_SUB -> LanguageTrack.DE_SUB
                            com.axiel7.anihyou.release.core.extension.ObservationTrack.DE_DUB -> LanguageTrack.DE_DUB
                            com.axiel7.anihyou.release.core.extension.ObservationTrack.UNKNOWN -> null
                        }
                        CycleSourceObservation(
                            instanceId = "aw:extension:direct:v1:${sha256(target.canonicalKey).take(24)}",
                            sourceType = type,
                            targetKey = target.canonicalKey,
                            track = track,
                            result = if (items.isEmpty()) CycleResult.INCOMPLETE else CycleResult.PARTIAL_SUCCESS,
                            health = healthByType[type]?.status ?: SourceHealthStatus.UNKNOWN,
                            observedAt = completed,
                            evidence = items,
                        )
                    }
                    val cycle = CompletedObservationCycle(
                        id = lease.cycleId,
                        scopeId = RoomExtensionShadowGenerationStore.SCOPE_ID,
                        startedAt = started,
                        completedAt = completed,
                        policy = AbsencePolicySnapshot(),
                        sources = sourceObservations,
                        manifest = sourceObservations.map {
                            ExpectedSourceInstance(it.instanceId, it.sourceType, it.targetKey, it.track, false)
                        },
                    )
                    if (!commitGuard { generations.commit(lease, cycle, result.receipt, health, targets) }) {
                        generations.abort(lease, "stale-active-source", clock.instant())
                        ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false)
                    } else {
                        ShadowRefreshOutcome.Committed(lease.cycleId, cycle)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            token?.let { lease -> withContext(NonCancellable) {
                runCatching { generations.abort(lease, "cancelled", clock.instant()) }
            } }
            throw cancelled
        } catch (failure: Exception) {
            token?.let { lease -> withContext(NonCancellable) {
                runCatching { generations.abort(lease,
                    failure::class.simpleName ?: "extension-shadow-failure", clock.instant()) }
            } }
            ShadowRefreshOutcome.Failed(failure::class.simpleName ?: "extension-shadow-failure", false)
        }
    }

    private fun sourceHealth(result: ExtensionHostResult.Completed, completedAt: Instant): List<SourceHealth> =
        ROLE_TYPES.mapNotNull { (role, sourceType) ->
            val requestIds = result.requestRoles.filterValues { it == role }.keys
            if (requestIds.isEmpty()) return@mapNotNull null
            val reports = result.reports.filter { it.requestId in requestIds }
            val responses = result.responseProvenance.filter { it.requestId in requestIds }
            val successfulResponses = responses.filter { it.httpStatus in 200..299 }
            val reportOutcomes = reports.map { it.outcome }
            val hasFailedResponse = responses.any { it.httpStatus !in 200..299 } || requestIds.any { id ->
                responses.none { it.requestId == id }
            }
            val hasMissingReport = requestIds.any { id -> reports.none { it.requestId == id } }
            val hasFailedReport = reportOutcomes.any { it == ExtensionReportOutcome.FAILURE }
            val hasPartialReport = reportOutcomes.any { it == ExtensionReportOutcome.PARTIAL }
            val status = when {
                successfulResponses.isEmpty() -> SourceHealthStatus.UNAVAILABLE
                hasFailedResponse || hasMissingReport || hasFailedReport || hasPartialReport -> SourceHealthStatus.DEGRADED
                else -> SourceHealthStatus.HEALTHY
            }
            val successfulRequestIds = reports.filter {
                it.outcome != ExtensionReportOutcome.FAILURE
            }.map { it.requestId }.toSet()
            val lastSuccess = successfulResponses.filter { it.requestId in successfulRequestIds }
                .maxOfOrNull { it.completedAt }
            val lastAttempt = responses.maxOfOrNull { it.completedAt } ?: completedAt
            SourceHealth(
                sourceType = sourceType,
                status = status,
                lastAttemptAt = lastAttempt,
                lastSuccessAt = lastSuccess,
                parserVersion = EXTENSION_PARSER_VERSION,
                sourceHash = responses.maxByOrNull { it.completedAt }?.sourceHash,
                diagnostic = when (status) {
                    SourceHealthStatus.HEALTHY -> null
                    SourceHealthStatus.DEGRADED -> "extension-response-partial"
                    else -> "extension-response-unavailable"
                },
            )
        }

    private fun blockedHealth(code: ExtensionHostFailureCode, now: Instant): List<SourceHealth> {
        val status = when (code) {
            ExtensionHostFailureCode.DISABLED,
            ExtensionHostFailureCode.HOST_VALIDATION_FAILED,
            ExtensionHostFailureCode.NO_TRUSTED_EXTENSION,
            ExtensionHostFailureCode.OBSERVATION_POLICY_REJECTED,
            ExtensionHostFailureCode.TRANSPORT_NOT_READY -> SourceHealthStatus.BLOCKED
            ExtensionHostFailureCode.BUSY -> SourceHealthStatus.UNKNOWN
            ExtensionHostFailureCode.RUNTIME_FAILURE -> SourceHealthStatus.UNAVAILABLE
        }
        if (status == SourceHealthStatus.UNKNOWN) return emptyList()
        return ROLE_TYPES.values.distinct().map { sourceType -> SourceHealth(
            sourceType = sourceType,
            status = status,
            lastAttemptAt = now,
            lastSuccessAt = null,
            parserVersion = EXTENSION_PARSER_VERSION,
            diagnostic = "extension-host-${code.name.lowercase()}",
        ) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private val WORK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,111}")
        private const val EXTENSION_PARSER_VERSION = "aniworld-v3-extension-v1"
        private val LIST_ROLES = setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT)
        private val SOURCE_ROLES = LIST_ROLES + SourceRole.DIRECT
        private val ROLE_TYPES = mapOf(
            SourceRole.CALENDAR to ReleaseSourceType.ANIWORLD_CALENDAR,
            SourceRole.RECENT to ReleaseSourceType.ANIWORLD_RECENT,
            SourceRole.POSTPONEMENT to ReleaseSourceType.ANIWORLD_POSTPONEMENT,
            SourceRole.DIRECT to ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
        )
        private val RETRYABLE = setOf(ExtensionHostFailureCode.BUSY, ExtensionHostFailureCode.RUNTIME_FAILURE)
    }
}

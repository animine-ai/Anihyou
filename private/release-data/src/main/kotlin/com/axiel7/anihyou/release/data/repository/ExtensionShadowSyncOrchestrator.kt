package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.log.AppLog
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
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
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
    private val requireCompleteRefresh: Boolean = false,
    /** The exact release source this run belongs to; its cycles are folded into that source's own rows (R04). */
    private val selection: ExtensionSelectionKey? = null,
    /** Per role name: when the ledger cooldown that denied that role in this run lapses. Empty: nothing was denied. */
    private val deferralProbe: suspend (generationId: String) -> Map<String, Instant> = { emptyMap() },
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
            AppLog.i("sync") { "refresh run work=${workId.take(8)} provider=${providerId.value} roles=${sourceRoles.map { it.name }} targets=${targets.size}" }
            val syncStartedAt = System.nanoTime()
            val result = coordinator.execute(ExtensionRunRequest(
                providerId = providerId,
                generationId = lease.executionGenerationId,
                sourceRoles = sourceRoles,
                targets = targets.map { it.target },
            ))
            AppLog.i("sync") {
                "host result ${result.javaClass.simpleName}" + ((result as? ExtensionHostResult.Failed)?.let { " code=${it.code}" } ?: "") +
                    " in ${(System.nanoTime() - syncStartedAt) / 1_000_000} ms"
            }
            when (result) {
                is ExtensionHostResult.Failed -> {
                    val now = clock.instant()
                    val recorded = commitGuard {
                        generations.abort(lease, result.code.name, now,
                            if (result.code == ExtensionHostFailureCode.BUSY || !currentSelection()) emptyList() else blockedHealth(result.code, now))
                        true
                    }
                    if (!recorded) generations.abort(lease, result.code.name, now)
                    ShadowRefreshOutcome.Failed(result.code.name, result.code in RETRYABLE)
                }
                is ExtensionHostResult.Completed -> {
                    check(result.receipt.generationId == lease.executionGenerationId)
                    val evidence = authority.project(result).filter { it.languageTrack?.name in enabledTracks }
                    AppLog.i("sync") {
                        "evidence projected=${evidence.size} tracks=${enabledTracks} roles ok=${succeededRoles(result).map { it.name }}"
                    }
                    val started = Instant.parse(result.receipt.startedAt)
                    val completed = Instant.parse(result.receipt.completedAt)
                    val succeeded = succeededRoles(result)
                    // A role whose requests the host ledger denied (a cooldown, not an answer of the source) was not
                    // asked: it is neither a failure nor judged absent, and it does not touch source health.
                    val deniedUntil = if (requireCompleteRefresh) deferralProbe(lease.executionGenerationId) else emptyMap()
                    val deniedRoles = deniedUntil.keys.mapNotNull { name -> SourceRole.entries.firstOrNull { it.name == name } }
                        .filterTo(mutableSetOf()) { role ->
                            val requestIds = result.requestRoles.filterValues { it == role }.keys
                            requestIds.isNotEmpty() && result.responseProvenance.none { it.requestId in requestIds }
                        }
                    val health = sourceHealth(result, completed).filterNot { entry ->
                        deniedRoles.any { ROLE_TYPES.getValue(it) == entry.sourceType }
                    }
                    val asked = result.requestRoles.values.toSet() - deniedRoles
                    if (requireCompleteRefresh && succeeded.isEmpty()) {
                        if (asked.isEmpty() && deniedRoles.isNotEmpty()) {
                            // Every request was denied by a cooldown: a typed deferral, not a failure. The generation
                            // closes without touching source health and the caller waits for the time.
                            val recorded = commitGuard {
                                generations.abort(lease, "extension-budget-deferred", completed, emptyList())
                                true
                            }
                            if (!recorded) generations.abort(lease, "stale-active-source", completed)
                            return@withLock ShadowRefreshOutcome.Skipped(REASON_DEFERRED, deniedUntil.values.max())
                        }
                        val recorded = commitGuard {
                            generations.abort(lease, "extension-refresh-partial", completed,
                                if (currentSelection()) health else emptyList())
                            true
                        }
                        if (!recorded) generations.abort(lease, "stale-active-source", completed)
                        return@withLock ShadowRefreshOutcome.Failed("extension-refresh-partial", retryable = true)
                    }
                    // A product run commits only roles that fully succeeded; a role that failed, was denied or was
                    // not requested is simply absent from the cycle and can never read as "the item disappeared".
                    val includedRoles = if (requireCompleteRefresh) succeeded else SOURCE_ROLES
                    val healthByType = health.associateBy { it.sourceType }
                    val sourceObservations = LIST_ROLES.filter { it in includedRoles }.sortedBy(SourceRole::ordinal).map { role ->
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
                    } + (if (SourceRole.DIRECT in includedRoles) targets else emptyList()).map { target ->
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
                    if (!commitGuard { generations.commit(lease, cycle, result.receipt, health, targets, selection,
                        sourceLabels(result.observations.filter { it.sourceRole in includedRoles })) }) {
                        generations.abort(lease, "stale-active-source", clock.instant())
                        ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false)
                    } else {
                        val successfulPresentationRoles = setOf(SourceRole.POSTPONEMENT).filterTo(mutableSetOf()) { role ->
                            val requestIds = result.requestRoles.filterValues { it == role }.keys
                            requestIds.isNotEmpty() && requestIds.all { requestId ->
                                result.responseProvenance.any {
                                    it.requestId == requestId && it.httpStatus in 200..299
                                } && result.reports.singleOrNull {
                                    it.requestId == requestId
                                }?.outcome == ExtensionReportOutcome.SUCCESS
                            }
                        }
                        ShadowRefreshOutcome.Committed(
                            generationId = lease.cycleId,
                            cycle = cycle,
                            presentationObservations = result.observations.filter {
                                it.sourceRole in successfulPresentationRoles
                            },
                            successfulPresentationRoles = successfulPresentationRoles,
                            refreshSucceeded = result.requestRoles.isNotEmpty() &&
                                health.isNotEmpty() && health.all { it.status == SourceHealthStatus.HEALTHY },
                            successfulRoles = succeeded,
                        )
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

    /** The titles each series was reported under in this run; the most frequent one is the label, others aliases. */
    private fun sourceLabels(observations: List<com.axiel7.anihyou.release.core.extension.ProviderObservationV1>):
        List<ObservedSourceLabel> = observations
        .filter { !it.providerSeriesKey.isNullOrBlank() }
        .groupBy { it.providerSeriesKey.orEmpty() }
        .mapNotNull { (slug, rows) ->
            val ranked = rows.map { it.rawTitle.trim() }.filter { it.isNotBlank() && it.length <= 512 }
                .groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ranked.firstOrNull()?.let { ObservedSourceLabel(slug, it.key, ranked.drop(1).map { e -> e.key }.take(8).toSet()) }
        }

    /** A role succeeded only if every one of its requests was a real 2xx response with a SUCCESS report. */
    private fun succeededRoles(result: ExtensionHostResult.Completed): Set<SourceRole> =
        result.requestRoles.values.toSet().filterTo(mutableSetOf()) { role ->
            val requestIds = result.requestRoles.filterValues { it == role }.keys
            requestIds.isNotEmpty() && requestIds.all { requestId ->
                result.responseProvenance.any { it.requestId == requestId && it.httpStatus in 200..299 } &&
                    result.reports.singleOrNull { it.requestId == requestId }?.outcome == ExtensionReportOutcome.SUCCESS
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
        const val REASON_DEFERRED = "extension-budget-deferred"
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

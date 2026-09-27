package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.AniWorldShadowPollStore
import com.axiel7.anihyou.release.core.api.AniWorldShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ReleaseEvidenceSource
import com.axiel7.anihyou.release.core.api.SourceFailureKind
import com.axiel7.anihyou.release.core.api.ShadowGenerationManifest
import com.axiel7.anihyou.release.core.api.ShadowGenerationToken
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.ShadowRunMetrics
import com.axiel7.anihyou.release.core.api.ShadowSourceSpec
import com.axiel7.anihyou.release.core.api.ShadowSourceRunMetric
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.ExpectedSourceInstance
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.core.model.TargetCoverage
import com.axiel7.anihyou.release.core.model.TargetPresence
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.data.aniworld.AniWorldCalendarEvidenceAdapter
import com.axiel7.anihyou.release.data.aniworld.AniWorldClient
import com.axiel7.anihyou.release.data.aniworld.AniWorldDirectVerificationEvidenceAdapter
import com.axiel7.anihyou.release.data.aniworld.AniWorldEvidenceIngestionCoordinator
import com.axiel7.anihyou.release.data.aniworld.AniWorldPostponementDiagnosticSink
import com.axiel7.anihyou.release.data.aniworld.AniWorldPostponementEvidenceAdapter
import com.axiel7.anihyou.release.data.aniworld.AniWorldRecentEpisodeEvidenceAdapter
import com.axiel7.anihyou.release.data.aniworld.BudgetedAniWorldHttpTransport
import com.axiel7.anihyou.release.data.aniworld.JdkAniWorldHttpTransport
import com.axiel7.anihyou.release.data.aniworld.PostponementParseDiagnostics
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Sequential, single-generation shadow run; never invokes R2 writes or user-facing consumers. */
class AniWorldShadowSyncOrchestrator(
    private val pollStore: AniWorldShadowPollStore,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val enabled: () -> Boolean = { false },
) : AniWorldShadowRefreshCoordinator {
    private val mutex = Mutex()

    override suspend fun refresh(): ShadowRefreshOutcome = mutex.withLock {
        if (!enabled()) return@withLock ShadowRefreshOutcome.Skipped("shadow-disabled")
        val runStartNanos = System.nanoTime()
        var manifest: ShadowGenerationManifest? = null
        var metrics = zeroMetrics()
        try {
            reconciliation.importBaseline()
            val planning = withTimeoutOrNull(PLANNING_TIMEOUT.toMillis()) {
                val now = clock.instant()
                val candidates = pollStore.eligibleDirectTargets(now)
                val candidateDigest = DirectTargetSelectionPolicy.snapshotDigest(candidates)
                val selected = DirectTargetSelectionPolicy.select(candidates, now)
                val token = ShadowGenerationToken(
                    generationId = "aw-shadow-v1:" + UUID.randomUUID(),
                    ownerToken = UUID.randomUUID().toString(),
                    processEpoch = PROCESS_EPOCH,
                )
                val started = clock.instant()
                val sources = buildSources(selected)
                val draft = ShadowGenerationManifest(token, 1, started, started.plus(GENERATION_TIMEOUT),
                    sources, AniWorldShadowManifestCodec.digest(sources))
                if (pollStore.beginGeneration(draft, candidateDigest)) {
                    PlanningResult(PlannedRun(draft, candidates, selected))
                } else PlanningResult(null)
            } ?: return@withLock ShadowRefreshOutcome.Skipped("planning-deadline")
            val plan = planning.run ?: return@withLock ShadowRefreshOutcome.Skipped("generation-cas-or-cooldown")
            manifest = plan.manifest

            var postponementDiagnostics: PostponementParseDiagnostics? = null
            val transport = BudgetedAniWorldHttpTransport(
                delegate = JdkAniWorldHttpTransport(),
                store = pollStore,
                manifest = plan.manifest,
                clock = clock,
            )
            val client = AniWorldClient(
                transport = transport,
                allowedHosts = AniWorldClient.DEFAULT_ALLOWED_HOSTS,
                timeoutMillis = 10_000,
                clock = clock,
            )
            // Preserve the existing adapters and ingestion collector; the only
            // additions are generation-local transport accounting and diagnostics.
            val sources = buildList<ReleaseEvidenceSource> {
                add(AniWorldCalendarEvidenceAdapter(client))
                add(AniWorldRecentEpisodeEvidenceAdapter(client))
                add(AniWorldPostponementEvidenceAdapter(client, diagnosticSink =
                    AniWorldPostponementDiagnosticSink { postponementDiagnostics = it }))
                plan.selected.flatMap { it.exactTargetKeys }.map { key ->
                    val physical = physicalRequestId(plan.selected.single { it.exactTargetKeys.contains(key) }.canonicalUrl)
                    plan.manifest.sources.single {
                        it.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE.name && it.targetKey == key
                    }.let { direct ->
                        AniWorldDirectVerificationEvidenceAdapter(client, direct.requestUrl, sourceInstanceId = physical)
                    }
                }.distinctBy { it.sourceInstanceId }.forEach(::add)
            }
            val collection = withTimeoutOrNull(GENERATION_TIMEOUT.toMillis()) {
                AniWorldEvidenceIngestionCoordinator(sources).collect()
            } ?: run {
                val timeoutMetrics = metricsFor(plan, null, postponementDiagnostics, runStartNanos, emptyList())
                withContext(NonCancellable) {
                    pollStore.abortGeneration(plan.manifest.token, "generation-deadline", clock.instant(), timeoutMetrics)
                }
                return@withLock ShadowRefreshOutcome.Failed("generation-deadline", retryable = true)
            }
            val completedAt = clock.instant()
            val instancesById = collection.sourceInstances.associateBy { it.instanceId }
            val physicalByUrl = plan.selected.associate { candidate ->
                candidate.canonicalUrl to physicalRequestId(candidate.canonicalUrl)
            }
            val observations = plan.manifest.sources.map { spec ->
                val physicalId = if (spec.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE.name)
                    physicalByUrl.getValue(spec.requestUrl) else spec.instanceId
                val collected = instancesById[physicalId]
                val result = collected?.result ?: com.axiel7.anihyou.release.core.api.SourceResult.Failure(
                    SourceFailureKind.UNKNOWN, "manifest source returned no result",
                )
                val allEvidence = when (result) {
                    is com.axiel7.anihyou.release.core.api.SourceResult.Success -> result.value
                    is com.axiel7.anihyou.release.core.api.SourceResult.PartialSuccess -> result.value
                    is com.axiel7.anihyou.release.core.api.SourceResult.Failure -> emptyList()
                }
                val evidence = if (spec.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE.name) {
                    allEvidence.filter { CanonicalReleaseIdentity.from(it)?.key == spec.targetKey &&
                        it.languageTrack?.name == spec.track }
                } else allEvidence
                val failure = result as? com.axiel7.anihyou.release.core.api.SourceResult.Failure
                val cycleResult = when {
                    failure?.kind == SourceFailureKind.BUDGET_OR_COOLDOWN -> CycleResult.INCOMPLETE
                    result is com.axiel7.anihyou.release.core.api.SourceResult.Failure -> CycleResult.FAILURE
                    result is com.axiel7.anihyou.release.core.api.SourceResult.PartialSuccess -> CycleResult.PARTIAL_SUCCESS
                    else -> CycleResult.SUCCESS
                }
                val health = when {
                    cycleResult == CycleResult.INCOMPLETE -> SourceHealthStatus.DEGRADED
                    result is com.axiel7.anihyou.release.core.api.SourceResult.Success ->
                        result.sourceHealth?.status ?: SourceHealthStatus.HEALTHY
                    result is com.axiel7.anihyou.release.core.api.SourceResult.PartialSuccess ->
                        result.sourceHealth?.status ?: SourceHealthStatus.DEGRADED
                    else -> failure?.sourceHealth?.status ?: SourceHealthStatus.UNAVAILABLE
                }
                val attempt = when (result) {
                    is com.axiel7.anihyou.release.core.api.SourceResult.Success -> result.sourceHealth?.lastAttemptAt
                    is com.axiel7.anihyou.release.core.api.SourceResult.PartialSuccess -> result.sourceHealth?.lastAttemptAt
                    is com.axiel7.anihyou.release.core.api.SourceResult.Failure -> result.sourceHealth?.lastAttemptAt
                } ?: evidence.maxOfOrNull { it.observedAt } ?: plan.manifest.startedAt
                CycleSourceObservation(
                    instanceId = spec.instanceId,
                    sourceType = ReleaseSourceType.valueOf(spec.sourceType),
                    targetKey = spec.targetKey,
                    track = spec.track?.let(LanguageTrack::valueOf),
                    result = cycleResult,
                    health = health,
                    coverage = TargetCoverage.UNKNOWN,
                    presence = TargetPresence.UNKNOWN,
                    observedAt = attempt.coerceAtLeast(plan.manifest.startedAt).coerceAtMost(completedAt),
                    evidence = if (cycleResult == CycleResult.FAILURE) emptyList() else evidence,
                    supersedesEvidenceIds = emptyList(),
                )
            }
            val cycle = CompletedObservationCycle(
                id = plan.manifest.token.generationId,
                scopeId = RoomAniWorldPollStore.SCOPE_ID,
                startedAt = plan.manifest.startedAt,
                completedAt = completedAt,
                policy = AbsencePolicySnapshot(),
                sources = observations,
                manifest = plan.manifest.sources.map { spec ->
                    ExpectedSourceInstance(spec.instanceId, ReleaseSourceType.valueOf(spec.sourceType),
                        spec.targetKey, spec.track?.let(LanguageTrack::valueOf), negativeRequired = false)
                },
            )
            val snapshot = pollStore.currentGeneration(plan.manifest.token)
                ?: return@withLock ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false)
            val sourceMetrics = plan.manifest.sources.map { spec ->
                val physicalId = if (spec.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE.name)
                    physicalByUrl.getValue(spec.requestUrl) else spec.instanceId
                val collected = instancesById[physicalId]
                val failure = collected?.result as? com.axiel7.anihyou.release.core.api.SourceResult.Failure
                ShadowSourceRunMetric(
                    instanceId = spec.instanceId,
                    sourceType = ReleaseSourceType.valueOf(spec.sourceType),
                    outcome = observations.single { it.instanceId == spec.instanceId }.result,
                    elapsedMillis = collected?.elapsedMillis ?: 0,
                    failureKind = failure?.kind,
                )
            }
            metrics = metricsFor(plan, snapshot, postponementDiagnostics, runStartNanos, observations, sourceMetrics)
            val committed = pollStore.commitGeneration(plan.manifest.token, cycle, metrics)
            if (!committed) {
                pollStore.abortGeneration(plan.manifest.token, "commit-fenced", clock.instant(), metrics)
                ShadowRefreshOutcome.Failed("commit-fenced", retryable = false)
            } else ShadowRefreshOutcome.Committed(plan.manifest.token.generationId, cycle)
        } catch (cancelled: CancellationException) {
            manifest?.let { current ->
                withContext(NonCancellable) {
                    pollStore.abortGeneration(current.token, "cancelled", clock.instant(), metrics)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            manifest?.let { current ->
                withContext(NonCancellable) {
                    runCatching { pollStore.abortGeneration(current.token,
                        failure::class.simpleName ?: "shadow-failure", clock.instant(), metrics) }
                }
            }
            ShadowRefreshOutcome.Failed(failure::class.simpleName ?: "shadow-failure", retryable = true)
        }
    }

    private fun buildSources(selected: List<DirectTargetCandidate>): List<ShadowSourceSpec> {
        val sources = mutableListOf(
            ShadowSourceSpec("aw:list:calendar:v1", ReleaseSourceType.ANIWORLD_CALENDAR.name,
                "aniworld:list:calendar", null, "https://aniworld.to/animekalender", true),
            ShadowSourceSpec("aw:list:recent:v1", ReleaseSourceType.ANIWORLD_RECENT.name,
                "aniworld:list:recent", null, "https://aniworld.to/neue-episoden", true),
            ShadowSourceSpec("aw:list:postponement:v1", ReleaseSourceType.ANIWORLD_POSTPONEMENT.name,
                "aniworld:list:postponement", null,
                "https://aniworld.to/support/frage/anime-verschiebungen", false),
        )
        selected.forEach { candidate ->
            val physicalId = physicalRequestId(candidate.canonicalUrl)
            candidate.exactTargetKeys.sorted().forEach { targetKey ->
                val identity = CanonicalReleaseIdentity.decode(targetKey)
                    ?: error("Direct candidate key is not canonical")
                val targetId = "aw:direct-target:v1:" + sha256(candidate.canonicalUrl + targetKey)
                sources += ShadowSourceSpec(
                    instanceId = targetId,
                    sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE.name,
                    targetKey = identity.key,
                    track = identity.track.name,
                    requestUrl = candidate.canonicalUrl,
                    requiredForRun = false,
                    physicalRequestId = physicalId,
                )
            }
        }
        return sources
    }

    private fun metricsFor(
        plan: PlannedRun,
        snapshot: com.axiel7.anihyou.release.core.api.ShadowGenerationSnapshot?,
        postponement: PostponementParseDiagnostics?,
        startedNanos: Long,
        observations: List<CycleSourceObservation>,
        sourceMetrics: List<ShadowSourceRunMetric> = emptyList(),
    ): ShadowRunMetrics {
        val incomplete = observations.count { it.result == CycleResult.INCOMPLETE }
        val failures = observations.count { it.result == CycleResult.FAILURE }
        val partial = observations.count { it.result == CycleResult.PARTIAL_SUCCESS }
        val success = observations.count { it.result == CycleResult.SUCCESS }
        val reasons = postponement?.reasonCounts.orEmpty().mapValues { it.value.coerceIn(0, 512) }.entries
            .sortedBy { it.key }.take(12).associate { it.key to it.value }
        val reserved = (snapshot?.directReserved ?: 0) + (snapshot?.listReserved ?: 0)
        return ShadowRunMetrics(
            planned = plan.manifest.sources.size,
            attempted = success + partial + failures,
            succeeded = success,
            partial = partial,
            failed = failures,
            skipped = incomplete,
            directEligibleUrls = plan.candidates.map { it.canonicalUrl }.distinct().size,
            directSelectedUrls = plan.selected.size,
            reservedWireCalls = reserved.coerceAtMost(22),
            redirectCalls = snapshot?.redirects?.coerceAtMost(22) ?: 0,
            postponementUnboundRows = postponement?.takeIf { it.structureValid }?.rowCount?.coerceIn(0, 512) ?: 0,
            comparableKeys = 0,
            disagreements = 0,
            elapsedMillis = Duration.ofNanos((System.nanoTime() - startedNanos).coerceAtLeast(0)).toMillis(),
            postponementRejectedRows = when {
                postponement?.overflow == true -> postponement.rowCount.coerceIn(0, 512)
                postponement?.structureValid == false -> 1
                else -> 0
            },
            postponementSnapshotHash = postponement?.sourceHash,
            postponementParserVersion = postponement?.parserVersion,
            postponementReasonCounts = reasons,
            sourceMetrics = sourceMetrics,
        )
    }

    private fun physicalRequestId(url: String): String = "aw:direct-url:v1:" + sha256(url)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun zeroMetrics() = ShadowRunMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private data class PlannedRun(
        val manifest: ShadowGenerationManifest,
        val candidates: List<DirectTargetCandidate>,
        val selected: List<DirectTargetCandidate>,
    )
    private data class PlanningResult(val run: PlannedRun?)

    companion object {
        private val PROCESS_EPOCH = UUID.randomUUID().toString()
        private val GENERATION_TIMEOUT = Duration.ofSeconds(240)
        private val PLANNING_TIMEOUT = Duration.ofSeconds(30)
    }
}

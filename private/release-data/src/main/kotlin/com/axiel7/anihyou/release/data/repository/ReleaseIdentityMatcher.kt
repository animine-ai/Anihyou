package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.api.TargetedLookupCursor
import com.axiel7.anihyou.release.core.matching.MatchDecision
import com.axiel7.anihyou.release.core.matching.MatchTier
import com.axiel7.anihyou.release.core.matching.ReleaseMatchRequest
import com.axiel7.anihyou.release.core.matching.ReleaseMatcher
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.sync.CandidatePoolRequest
import com.axiel7.anihyou.release.core.sync.CandidatePoolWindows
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.core.sync.ReleaseSyncPlanner
import com.axiel7.anihyou.release.core.sync.SyncBudget
import com.axiel7.anihyou.release.core.sync.SyncPlanRequest
import com.axiel7.anihyou.release.core.sync.TargetedLookupWork
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.ZoneId

/**
 * Resolves unmapped provider streams through the accepted matcher and keeps
 * lookup work globally bounded to four queries and two concurrent requests.
 */
class ReleaseIdentityMatcher(
    private val candidateSource: IdentityCandidateSource,
    private val matcher: ReleaseMatcher = ReleaseMatcher(),
    private val budget: SyncBudget = SyncBudget(
        maxTargetedQueries = MAX_TARGETED_QUERIES,
        maxConcurrentRequests = MAX_CONCURRENT_REQUESTS,
    ),
    private val clock: Clock = Clock.systemUTC(),
    private val sourceZone: ZoneId = ReleaseSourceTimePolicy.ANI_WORLD_ZONE,
) {
    suspend fun resolve(snapshots: List<ReleaseSnapshot>): Map<String, ReleaseMapping?> =
        resolveWithMetrics(snapshots).mappings

    suspend fun resolveWithMetrics(
        snapshots: List<ReleaseSnapshot>,
        generation: Long = 0L,
    ): IdentityResolutionResult {
        val startedAtMillis = clock.millis()
        val work = snapshots.mapNotNull { snapshot ->
            val source = sourceIdentity(snapshot) ?: return@mapNotNull null
            Resolvable(snapshot, source, requestFor(source))
        }
        if (work.isEmpty()) {
            return IdentityResolutionResult(
                mappings = emptyMap(),
                metrics = SyncPerformanceMetrics(
                    providerId = snapshots.firstOrNull()?.stream?.providerId?.value ?: "unknown",
                    generation = generation,
                    durationMillis = (clock.millis() - startedAtMillis).coerceAtLeast(0L),
                    currentSeasonPagesFetched = 0,
                    previousSeasonPagesFetched = 0,
                    targetedQueries = 0,
                    pooledCandidateCount = 0,
                    targetedCandidateCount = 0,
                    maxConcurrentRequests = budget.maxConcurrentRequests,
                ),
            )
        }

        val poolWindows = CandidatePoolWindows.currentAndPrevious(
            clock.instant().atZone(sourceZone).toLocalDate(),
        )
        val poolBatches = coroutineScope {
            val semaphore = Semaphore(budget.maxConcurrentRequests)
            poolWindows.mapIndexed { index, window ->
                async {
                    semaphore.withPermit {
                        safeSeasonPool(
                            CandidatePoolRequest(
                                window = window,
                                maxPages = if (index == 0) {
                                    budget.currentSeasonMaxPages
                                } else {
                                    budget.previousSeasonMaxPages
                                },
                            ),
                        )
                    }
                }
            }.awaitAll()
        }
        val pooledCandidates = poolBatches
            .flatMap { it.candidates }
            .distinctBy { it.mediaId }
        val local = safeLocalBySource(work.map { it.source }.toSet())
        val candidatesFor: (Resolvable) -> List<com.axiel7.anihyou.release.core.api.IdentityCandidate> = { item ->
            (local[item.source.stableKey]?.candidates.orEmpty() + pooledCandidates)
                .distinctBy { it.mediaId }
        }
        val needingTargeted = work.filter { item ->
            matcher.match(item.request, candidatesFor(item)) !is MatchDecision.Matched
        }
        val queries = needingTargeted.associate { item ->
            item.source.stableKey to targetedQuery(item.request)
        }
        val providerId = work.first().snapshot.stream.providerId.value
        val orderedQueries = queries.values
            .sortedWith(compareBy<TargetedIdentityQuery> { it.source.stableKey }.thenBy { it.signature })
        val durableCursor = safeReadTargetedLookupCursor(providerId)
        val selectedQueries = fairTargetedQueries(orderedQueries, durableCursor.offset)
        val plan = ReleaseSyncPlanner.plan(
            SyncPlanRequest(
                generation = generation,
                currentSeasonPagesAvailable = poolBatches.getOrNull(0)?.pagesFetched ?: 0,
                previousSeasonPagesAvailable = poolBatches.getOrNull(1)?.pagesFetched ?: 0,
                targetedWork = selectedQueries.map { query ->
                    TargetedLookupWork(
                        identity = query.source,
                        variants = listOf(query.query),
                        signature = query.signature,
                    )
                },
                budget = budget,
            ),
        )
        val targeted = coroutineScope {
            val semaphore = Semaphore(budget.maxConcurrentRequests)
            plan.targetedWork.map { lookup ->
                async {
                    val query = queries[lookup.identity.stableKey]
                        ?: return@async lookup.identity.stableKey to CandidateBatch(emptyList(), false)
                    semaphore.withPermit {
                        lookup.identity.stableKey to safeTargeted(query.copy(query = lookup.variants.single()))
                    }
                }
            }.awaitAll().toMap()
        }
        if (orderedQueries.isNotEmpty()) {
            val nextOffset = (durableCursor.offset + plan.targetedWork.size) % orderedQueries.size
            safeWriteTargetedLookupCursor(
                providerId = providerId,
                cursor = TargetedLookupCursor(
                    generation = maxOf(durableCursor.generation, generation),
                    offset = nextOffset,
                ),
            )
        }

        val mappings = work.associate { item ->
            val key = item.snapshot.stream.stableKey
            val candidates = (
                candidatesFor(item) +
                    targeted[item.source.stableKey]?.candidates.orEmpty()
                ).distinctBy { it.mediaId }
            key to mappingFor(matcher.match(item.request, candidates))
        }
        return IdentityResolutionResult(
            mappings = mappings,
            metrics = SyncPerformanceMetrics(
                providerId = work.first().snapshot.stream.providerId.value,
                generation = generation,
                durationMillis = (clock.millis() - startedAtMillis).coerceAtLeast(0L),
                currentSeasonPagesFetched = poolBatches.getOrNull(0)?.pagesFetched ?: 0,
                previousSeasonPagesFetched = poolBatches.getOrNull(1)?.pagesFetched ?: 0,
                targetedQueries = plan.targetedWork.size,
                pooledCandidateCount = pooledCandidates.size,
                targetedCandidateCount = targeted.values
                    .flatMap { it.candidates }
                    .distinctBy { it.mediaId }
                    .size,
                maxConcurrentRequests = budget.maxConcurrentRequests,
            ),
        )
    }

    private suspend fun safeLocalBySource(
        sources: Set<SourceIdentity>,
    ): Map<String, CandidateBatch> =
        runCatching { candidateSource.localCandidatesBySource(sources) }
            .getOrElse { emptyMap() }

    private suspend fun safeSeasonPool(request: CandidatePoolRequest): CandidateBatch =
        runCatching { candidateSource.boundedSeasonPool(request) }
            .getOrElse { CandidateBatch(emptyList(), complete = false) }

    private suspend fun safeTargeted(query: TargetedIdentityQuery): CandidateBatch =
        runCatching { candidateSource.targetedSearch(query) }
            .getOrElse { CandidateBatch(emptyList(), complete = false) }

    private suspend fun safeReadTargetedLookupCursor(providerId: String): TargetedLookupCursor =
        runCatching { candidateSource.readTargetedLookupCursor(providerId) }
            .getOrElse { TargetedLookupCursor() }

    private suspend fun safeWriteTargetedLookupCursor(
        providerId: String,
        cursor: TargetedLookupCursor,
    ) {
        runCatching { candidateSource.writeTargetedLookupCursor(providerId, cursor) }
    }

    private fun fairTargetedQueries(
        orderedQueries: List<TargetedIdentityQuery>,
        offset: Int,
    ): List<TargetedIdentityQuery> {
        if (orderedQueries.isEmpty()) return emptyList()
        val start = offset % orderedQueries.size
        val count = minOf(budget.maxTargetedQueries, orderedQueries.size)
        return (0 until count).map { index ->
            orderedQueries[(start + index) % orderedQueries.size]
        }
    }

    private fun requestFor(source: SourceIdentity): ReleaseMatchRequest {
        val title = source.stream.stableSeriesKey.value
            .substringAfterLast('/')
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "anime" }
        return ReleaseMatchRequest(
            source = source,
            title = title,
            format = source.stream.releaseKind.matcherFormat(),
            season = source.stream.sourceSeason.takeIf { it in 1..99 },
        )
    }

    private fun targetedQuery(request: ReleaseMatchRequest): TargetedIdentityQuery =
        TargetedIdentityQuery(
            source = request.source,
            query = request.title,
            formatIn = setOfNotNull(request.format),
            signature = listOf(
                "aniworld-matcher-v2",
                request.source.stream.stableKey,
                request.title,
                request.format.orEmpty(),
                request.season?.toString().orEmpty(),
            ).joinToString("|"),
        )

    private fun sourceIdentity(snapshot: ReleaseSnapshot): SourceIdentity? =
        (snapshot.confirmations.map { it.identity } + snapshot.forecasts.map { it.identity })
            .minByOrNull { it.stableKey }

    private fun mappingFor(decision: MatchDecision): ReleaseMapping? = when (decision) {
        is MatchDecision.Matched -> ReleaseMapping(
            mediaId = decision.mediaId,
            confidence = decision.tier.confidence(),
            score = decision.score,
            runnerUpMargin = decision.runnerUpMargin,
            evidence = decision.evidence,
            matcherVersion = matcher.matcherVersion,
            origin = MappingOrigin.AUTO,
        )
        is MatchDecision.Ambiguous -> decision.candidates.firstOrNull()?.let { candidate ->
            ReleaseMapping(
                mediaId = candidate.candidate.mediaId,
                confidence = MappingConfidence.AMBIGUOUS,
                score = candidate.score,
                runnerUpMargin = decision.candidates.getOrNull(1)?.let { candidate.score - it.score },
                evidence = "ambiguous:${decision.reason}",
                matcherVersion = matcher.matcherVersion,
                origin = MappingOrigin.AUTO,
            )
        }
        is MatchDecision.Unmapped -> null
    }

    private fun MatchTier.confidence(): MappingConfidence = when (this) {
        MatchTier.MANUAL,
        MatchTier.HISTORICAL,
        MatchTier.EXACT_NORMALIZED,
        -> MappingConfidence.EXACT
        MatchTier.EXACT_BASE_SEASON,
        MatchTier.ALIAS,
        MatchTier.FUZZY,
        -> MappingConfidence.HIGH
    }

    private fun ReleaseKind.matcherFormat(): String = when (this) {
        ReleaseKind.EPISODE -> "TV"
        ReleaseKind.MOVIE -> "MOVIE"
        ReleaseKind.SPECIAL -> "SPECIAL"
        ReleaseKind.OVA -> "OVA"
        ReleaseKind.ONA -> "ONA"
    }

    private data class Resolvable(
        val snapshot: ReleaseSnapshot,
        val source: SourceIdentity,
        val request: ReleaseMatchRequest,
    )

    private companion object {
        const val MAX_TARGETED_QUERIES = 4
        const val MAX_CONCURRENT_REQUESTS = 2
    }
}

package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.extension.ProviderObservationV1
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.core.state.ShadowComparison
import java.time.Duration
import java.time.Instant

enum class ShadowGenerationState { RUNNING, COMMITTED, ABORTED }
data class ShadowGenerationToken(val generationId: String, val ownerToken: String, val processEpoch: String)

data class ShadowSourceSpec(
    val instanceId: String,
    val sourceType: String,
    val targetKey: String,
    val track: String?,
    val requestUrl: String,
    val requiredForRun: Boolean,
    val negativeRequired: Boolean = false,
    val physicalRequestId: String? = null,
) {
    init {
        require(instanceId.length in 1..256 && sourceType.length in 1..64)
        require(targetKey.length in 1..2048 && requestUrl.length in 1..2048)
        require(!negativeRequired && (physicalRequestId == null || physicalRequestId.length <= 256))
    }
}

data class ShadowGenerationManifest(
    val token: ShadowGenerationToken,
    val policyVersion: Int,
    val startedAt: Instant,
    val deadlineAt: Instant,
    val sources: List<ShadowSourceSpec>,
    val digest: String,
) {
    init {
        require(policyVersion == 1 && token.generationId.startsWith("aw-shadow-v1:") && token.generationId.length <= 64)
        require(token.ownerToken.length in 1..128 && token.processEpoch.length in 1..128)
        require(Duration.between(startedAt, deadlineAt).toMillis() in 1..240_000)
        require(sources.size in 3..11 && sources.map { it.instanceId }.distinct().size == sources.size)
        require(sources.none { it.negativeRequired } && digest.matches(Regex("[0-9a-f]{64}")))
        val direct = sources.filter { it.sourceType == "ANIWORLD_DIRECT_PAGE" }
        require(direct.size <= 8 && direct.map { it.requestUrl }.distinct().size <= 4)
        require(direct.all { it.physicalRequestId != null })
        require(sources.count { it.sourceType == "ANIWORLD_CALENDAR" } == 1)
        require(sources.count { it.sourceType == "ANIWORLD_RECENT" } == 1)
        require(sources.count { it.sourceType == "ANIWORLD_POSTPONEMENT" } == 1)
    }
}

data class ShadowGenerationSnapshot(
    val state: ShadowGenerationState,
    val manifest: ShadowGenerationManifest,
    val directReserved: Int,
    val listReserved: Int,
    val redirects: Int = 0,
)

data class ShadowRequestReservation(
    val token: ShadowGenerationToken,
    val ordinal: Int,
    val role: String,
    val rootUrl: String,
    val requestUrl: String,
    val reservedAt: Instant,
) {
    init { require(ordinal in 0..21 && role.length <= 32 && rootUrl.length <= 2048 && requestUrl.length <= 2048) }
}

data class ShadowRequestOutcome(
    val status: String,
    val completedAt: Instant,
    val elapsedMillis: Long,
    val retryAfterSeconds: Long? = null,
) {
    init { require(status.length <= 40 && elapsedMillis >= 0 && (retryAfterSeconds == null || retryAfterSeconds in 0..21_600)) }
}

data class ShadowSourceRunMetric(
    val instanceId: String,
    val sourceType: ReleaseSourceType,
    val outcome: CycleResult,
    val elapsedMillis: Long,
    val failureKind: SourceFailureKind? = null,
) {
    init { require(instanceId.length in 1..256 && elapsedMillis >= 0) }
}

data class ShadowRunMetrics(
    val planned: Int,
    val attempted: Int,
    val succeeded: Int,
    val partial: Int,
    val failed: Int,
    val skipped: Int,
    val directEligibleUrls: Int,
    val directSelectedUrls: Int,
    val reservedWireCalls: Int,
    val redirectCalls: Int,
    val postponementUnboundRows: Int,
    val comparableKeys: Int,
    val disagreements: Int,
    val elapsedMillis: Long,
    val r2OnlyKeys: Int = 0,
    val v3OnlyKeys: Int = 0,
    val staleKeys: Int = 0,
    val uncomparableKeys: Int = 0,
    val postponementAmbiguousRows: Int = 0,
    val postponementRejectedRows: Int = 0,
    val postponementSnapshotHash: String? = null,
    val postponementParserVersion: String? = null,
    val postponementReasonCounts: Map<String, Int> = emptyMap(),
    val completedWireCalls: Int = 0,
    val uncompletedReservedCalls: Int = 0,
    val sourceMetrics: List<ShadowSourceRunMetric> = emptyList(),
) {
    init {
        val values = listOf(planned, attempted, succeeded, partial, failed, skipped, directEligibleUrls,
            directSelectedUrls, reservedWireCalls, redirectCalls, postponementUnboundRows, comparableKeys,
            disagreements, r2OnlyKeys, v3OnlyKeys, staleKeys, uncomparableKeys,
            postponementAmbiguousRows, postponementRejectedRows, completedWireCalls, uncompletedReservedCalls)
        require(values.all { it >= 0 } && elapsedMillis >= 0 && planned <= 11 && directSelectedUrls <= 4 && reservedWireCalls <= 22)
        require(postponementUnboundRows <= 512 && postponementAmbiguousRows <= 512 && postponementRejectedRows <= 512)
        require(postponementSnapshotHash == null || postponementSnapshotHash.matches(Regex("[0-9a-f]{64}")))
        require(postponementParserVersion == null || postponementParserVersion.length <= 64)
        require(postponementReasonCounts.size <= 12 && postponementReasonCounts.all { (k, v) -> k.length in 1..64 && v in 0..512 })
        require(completedWireCalls <= reservedWireCalls && uncompletedReservedCalls <= reservedWireCalls)
        require(completedWireCalls + uncompletedReservedCalls <= reservedWireCalls)
        require(sourceMetrics.size <= 11 && sourceMetrics.map { it.instanceId }.distinct().size == sourceMetrics.size)
    }
}

sealed interface ShadowRefreshOutcome {
    data class Committed(
        val generationId: String,
        val cycle: CompletedObservationCycle,
        val presentationObservations: List<ProviderObservationV1> = emptyList(),
        val successfulPresentationRoles: Set<SourceRole> = emptySet(),
        val refreshSucceeded: Boolean = false,
    ) : ShadowRefreshOutcome
    data class Skipped(val reason: String) : ShadowRefreshOutcome
    data class Failed(val reason: String, val retryable: Boolean) : ShadowRefreshOutcome
}

interface AniWorldShadowPollStore {
    suspend fun eligibleDirectTargets(now: Instant): List<DirectTargetCandidate>
    /** Extension ingress selects mapped coordinates and never invokes legacy route construction. */
    suspend fun eligibleMappedDirectTargets(now: Instant): List<DirectTargetCandidate> = emptyList()
    suspend fun shadowComparison(now: Instant): ShadowComparison
    suspend fun beginGeneration(manifest: ShadowGenerationManifest, candidateSnapshotDigest: String): Boolean
    suspend fun currentGeneration(token: ShadowGenerationToken): ShadowGenerationSnapshot?
    suspend fun reserveRequest(token: ShadowGenerationToken, role: String, rootUrl: String,
        requestUrl: String, now: Instant): ShadowRequestReservation?
    suspend fun completeRequest(reservation: ShadowRequestReservation, outcome: ShadowRequestOutcome)
    suspend fun commitGeneration(token: ShadowGenerationToken, cycle: CompletedObservationCycle,
        metrics: ShadowRunMetrics): Boolean
    suspend fun abortGeneration(token: ShadowGenerationToken, reason: String, now: Instant, metrics: ShadowRunMetrics)
}

fun interface AniWorldShadowRefreshCoordinator { suspend fun refresh(): ShadowRefreshOutcome }
/** WorkManager passes its stable work ID so a retry cannot mint a second Room cycle. */
interface WorkScopedShadowRefreshCoordinator : AniWorldShadowRefreshCoordinator {
    suspend fun refreshForWork(workId: String): ShadowRefreshOutcome
}
fun interface AniWorldShadowScheduler { fun scheduleCanaryNow() }

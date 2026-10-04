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

interface ShadowPollStore {
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

fun interface ShadowRefreshCoordinator { suspend fun refresh(): ShadowRefreshOutcome }
/** WorkManager passes its stable work ID so a retry cannot mint a second Room cycle. */
interface WorkScopedShadowRefreshCoordinator : ShadowRefreshCoordinator {
    suspend fun refreshForWork(workId: String): ShadowRefreshOutcome
}
fun interface ShadowCanaryScheduler { fun scheduleCanaryNow() }

package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.data.extension.*
import java.io.File
import java.time.Clock
import java.util.UUID
import java.security.MessageDigest

/** Test seam for transport construction; callers still receive the real host coordinator. */
fun interface ReleaseExtensionHostCoordinatorFactory {
    fun create(
        repository: VerifiedExtensionRepository,
        runtime: ExtensionRuntime,
        networkDirectory: File,
        observationPolicy: ExtensionObservationPolicy,
    ): ExtensionHostCoordinator
}

/** Product entry point: workers resolve selection at execution; navigation installation grants no ingestion. */
class SingleSourceShadowRefreshCoordinator(
    private val policy: ExtensionProductPolicyRepository,
    private val installed: InstalledExtensionAccess,
    private val runtime: ExtensionRuntime,
    private val networkDirectory: File,
    private val authority: ExtensionEvidenceAuthorityAdapter,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val generations: RoomExtensionShadowGenerationStore,
    private val targetSource: ExtensionTargetSource,
    private val clock: Clock,
    private val navigationStore: FileProviderNavigationStateStore,
    private val releaseHostFactory: ReleaseExtensionHostCoordinatorFactory = ReleaseExtensionHostCoordinatorFactory {
            repository, extensionRuntime, directory, observationPolicy ->
        ProductionExtensionDispatches.create(repository, extensionRuntime, directory,
            observationPolicy, mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L)).release
    },
    private val postponementStore: FileExtensionPostponementStore? = null,
) : WorkScopedShadowRefreshCoordinator {
    override suspend fun refresh() = refreshForWork(UUID.randomUUID().toString())

    override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome = refresh(workId, false)

    suspend fun refreshForProductWork(workId: String): ShadowRefreshOutcome = refresh(workId, true)

    private suspend fun refresh(workId: String, requireCompleteRefresh: Boolean): ShadowRefreshOutcome {
        val snapshot = policy.policy.value
        val selected = snapshot.activeReleaseSource ?: return ShadowRefreshOutcome.Skipped("no-active-release-source")
        val pinned = installed.loadInstalled(selected) ?: return ShadowRefreshOutcome.Skipped("active-release-source-unavailable")
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? {
                if (providerId.value != selected.providerId || !current(snapshot)) return null
                return installed.loadInstalled(selected)?.takeIf {
                    it.packageDigest == pinned.packageDigest && it.packageGeneration == pinned.packageGeneration
                }
            }
        }
        val dispatch = releaseHostFactory.create(repository, runtime, networkDirectory, authority.observationPolicy())
        val effectiveTracks = snapshot.preferencesFor(selected).enabledTracks.intersect(
            ObservationTrack.entries.filter { it != ObservationTrack.UNKNOWN }.map { it.name }.toSet())
        // Historical mapping adapters are explicitly bound to their provider, never reused for another source.
        val targets = ExtensionTargetSource {
            if (selected.providerId == "aniworld") targetSource.targets().filter { it.target.track.name in effectiveTracks }
            else emptyList()
        }
        val scopedWorkId = MessageDigest.getInstance("SHA-256").digest(
            "$workId/${snapshot.releaseGeneration}/${pinned.packageDigest}/${pinned.packageGeneration}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val outcome = ExtensionShadowSyncOrchestrator(dispatch, authority, reconciliation, generations, targets, clock,
            providerId = pinned.providerId,
            sourceRoles = pinned.grantedRoles,
            enabledTracks = effectiveTracks,
            requireCompleteRefresh = requireCompleteRefresh,
            commitGuard = { commit -> policy.withCurrentSelection(snapshot) {
                installed.withCurrentGeneration(selected, pinned.packageDigest, pinned.packageGeneration, commit) ?: false
            } ?: false },
            // This callback is invoked while commitGuard already holds policy then package locks.
            currentSelection = { current(snapshot) && installed.loadInstalled(selected)?.let {
                it.packageDigest == pinned.packageDigest && it.packageGeneration == pinned.packageGeneration
            } == true },
        ).refreshForWork(scopedWorkId)
        if (outcome is ShadowRefreshOutcome.Committed) policy.withCurrentSelection(snapshot) {
            installed.withCurrentGeneration(selected, pinned.packageDigest, pinned.packageGeneration) {
                if (SourceRole.POSTPONEMENT in outcome.successfulPresentationRoles) {
                    // A failed durable presentation write cannot grant a fresh-data receipt.
                    // Cancellation also propagates to the bounded worker retry boundary.
                    postponementStore?.record(
                        source = selected,
                        releaseGeneration = snapshot.releaseGeneration,
                        packageDigest = pinned.packageDigest,
                        packageGeneration = pinned.packageGeneration,
                        observedAt = outcome.cycle.completedAt,
                        observations = outcome.presentationObservations.filter {
                            it.track == ObservationTrack.UNKNOWN || it.track.name in effectiveTracks
                        },
                    )
                }
                val reducer = AniWorldReleaseAuthorityReducer()
                val accepted = outcome.cycle.sources.flatMap { it.evidence }.mapNotNull { evidence ->
                    if (evidence.evidenceType.name !in setOf("CONFIRMATION", "VERIFICATION")) return@mapNotNull null
                    val identity = com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity.from(evidence) ?: return@mapNotNull null
                    val currentEvidenceDecision = reducer.reduce(null, evidence)
                    if (currentEvidenceDecision.phase != ReleasePhase.RELEASED ||
                        currentEvidenceDecision.authority != ReleaseAuthority.ANIWORLD) return@mapNotNull null
                    val state = reconciliation.get(identity.key) ?: return@mapNotNull null
                    // Receipt eligibility follows the persisted release fact. The effective
                    // presentation phase may include conflict policy and is not the authority source.
                    if (state.underlyingPhase != ReleasePhase.RELEASED ||
                        state.authority == ReleaseAuthority.NONE) return@mapNotNull null
                    val episode = identity.installment as? com.axiel7.anihyou.release.core.model.Installment.Episode ?: return@mapNotNull null
                    AcceptedProviderInstallment(identity.key, evidence.siteIdentifier?.slug ?: return@mapNotNull null,
                        evidence.navigationSeason ?: return@mapNotNull null,
                        episode.number.toString() + (episode.fraction?.let { ".$it" } ?: ""), identity.track.name)
                }
                val previous = navigationStore.state.value.takeIf {
                    it.source == selected && it.releaseGeneration == snapshot.releaseGeneration &&
                        it.packageDigest == pinned.packageDigest && it.packageGeneration == pinned.packageGeneration
                }?.syncStatistics.orEmpty()
                val statistics = buildMap {
                    // Sparse/empty successful listings remain partial Evidence for absence policy.
                    // Freshness instead follows authenticated transport and complete parser reports.
                    val successful = outcome.refreshSucceeded
                    put("Last successful sync", if (successful) outcome.cycle.completedAt.toString() else previous["Last successful sync"].orEmpty())
                    put("Role health", outcome.cycle.sources.groupBy { it.sourceType }.entries.joinToString("; ") {
                        it.key.name + ": " + it.value.map { row -> row.health.name + "/" + row.result.name }.distinct().joinToString()
                    })
                    put("Last parse status", if (successful) "SUCCESS" else "PARTIAL_OR_FAILED")
                    put("Last sync outcome", if (successful) "COMMITTED" else "PARTIAL")
                    put("Last sync failure", if (successful) "NONE" else "ROLE_HEALTH_NOT_SUCCESSFUL")
                    put("Cancellation", "NOT_CANCELLED")
                    put("Transport status", "COORDINATOR_COMPLETED")
                    put("Sync duration", java.time.Duration.between(outcome.cycle.startedAt, outcome.cycle.completedAt).toMillis().toString() + " ms")
                }
                navigationStore.record(selected, snapshot.releaseGeneration, pinned.packageDigest, accepted, statistics,
                    pinned.packageGeneration)
            }
        }
        if (outcome is ShadowRefreshOutcome.Failed) policy.withCurrentSelection(snapshot) {
            installed.withCurrentGeneration(selected, pinned.packageDigest, pinned.packageGeneration) {
                val previous = navigationStore.state.value.takeIf {
                    it.source == selected && it.releaseGeneration == snapshot.releaseGeneration &&
                        it.packageDigest == pinned.packageDigest && it.packageGeneration == pinned.packageGeneration
                }?.syncStatistics.orEmpty()
                // Only a host-owned status code, never an exception message or transport URL.
                val reason = outcome.reason.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: "FAILED"
                navigationStore.record(selected, snapshot.releaseGeneration, pinned.packageDigest, emptyList(),
                    previous + mapOf("Last sync outcome" to "FAILED", "Last sync failure" to reason,
                        "Last parse status" to "NO_COMMITTED_PARSE", "Transport status" to "COORDINATOR_ABORTED",
                        "Role health" to pinned.grantedRoles.sortedBy { it.name }.joinToString("; ") { it.name + ": ABORTED" }),
                    pinned.packageGeneration)
            }
        }
        return outcome
    }
    private fun current(snapshot: ExtensionProductPolicy): Boolean = policy.policy.value.let {
        it.activeReleaseSource == snapshot.activeReleaseSource && it.releaseGeneration == snapshot.releaseGeneration
    }
}

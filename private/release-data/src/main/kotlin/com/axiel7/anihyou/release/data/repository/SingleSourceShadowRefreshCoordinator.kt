package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.data.extension.*
import java.io.File
import java.time.Clock
import java.util.UUID
import java.security.MessageDigest

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
) : WorkScopedShadowRefreshCoordinator {
    override suspend fun refresh() = refreshForWork(UUID.randomUUID().toString())

    override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome {
        val snapshot = policy.policy.value
        val selected = snapshot.activeReleaseSource ?: return ShadowRefreshOutcome.Skipped("no-active-release-source")
        val pinned = installed.loadInstalled(selected) ?: return ShadowRefreshOutcome.Skipped("active-release-source-unavailable")
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? {
                if (providerId.value != selected.providerId || !current(snapshot)) return null
                return installed.loadInstalled(selected)?.takeIf { it.packageDigest == pinned.packageDigest }
            }
        }
        val dispatch = ProductionExtensionDispatches.create(repository, runtime, networkDirectory,
            authority.observationPolicy(), mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L))
        val effectiveTracks = snapshot.preferencesFor(selected).orderedTracks(
            ObservationTrack.entries.filter { it != ObservationTrack.UNKNOWN }.map { it.name }.toSet()).toSet()
        // Historical mapping adapters are explicitly bound to their provider, never reused for another source.
        val targets = ExtensionTargetSource {
            if (selected.providerId == "aniworld") targetSource.targets().filter { it.target.track.name in effectiveTracks }
            else emptyList()
        }
        val scopedWorkId = MessageDigest.getInstance("SHA-256").digest(
            "$workId/${snapshot.releaseGeneration}/${pinned.packageDigest}".toByteArray()).joinToString("") { "%02x".format(it) }
        val outcome = ExtensionShadowSyncOrchestrator(dispatch.release, authority, reconciliation, generations, targets, clock,
            providerId = pinned.providerId,
            sourceRoles = pinned.grantedRoles,
            enabledTracks = effectiveTracks,
            commitGuard = { commit -> policy.withCurrentSelection(snapshot) {
                if (installed.loadInstalled(selected)?.packageDigest != pinned.packageDigest) false else commit()
            } ?: false },
            currentSelection = { current(snapshot) && installed.loadInstalled(selected)?.packageDigest == pinned.packageDigest },
        ).refreshForWork(scopedWorkId)
        if (outcome is ShadowRefreshOutcome.Committed) policy.withCurrentSelection(snapshot) {
            if (installed.loadInstalled(selected)?.packageDigest == pinned.packageDigest) {
                val accepted = outcome.cycle.sources.flatMap { it.evidence }.mapNotNull { evidence ->
                    if (evidence.evidenceType.name !in setOf("CONFIRMATION", "VERIFICATION")) return@mapNotNull null
                    val identity = com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity.from(evidence) ?: return@mapNotNull null
                    val state = reconciliation.get(identity.key) ?: return@mapNotNull null
                    if (state.phase.name != "RELEASED" || state.authority.name == "NONE") return@mapNotNull null
                    val episode = identity.installment as? com.axiel7.anihyou.release.core.model.Installment.Episode ?: return@mapNotNull null
                    AcceptedProviderInstallment(identity.key, evidence.siteIdentifier?.slug ?: return@mapNotNull null,
                        evidence.navigationSeason ?: return@mapNotNull null,
                        episode.number.toString() + (episode.fraction?.let { ".$it" } ?: ""), identity.track.name)
                }
                navigationStore.record(selected, snapshot.releaseGeneration, pinned.packageDigest, accepted)
            }
        }
        return outcome
    }
    private fun current(snapshot: ExtensionProductPolicy): Boolean = policy.policy.value.let {
        it.activeReleaseSource == snapshot.activeReleaseSource && it.releaseGeneration == snapshot.releaseGeneration
    }
}

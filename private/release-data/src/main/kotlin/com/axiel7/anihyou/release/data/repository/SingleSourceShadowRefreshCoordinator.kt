package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.core.sync.ExtensionRefreshPlan
import com.axiel7.anihyou.release.core.sync.ExtensionRefreshPlanner
import com.axiel7.anihyou.release.core.sync.ExtensionSuccessLookup
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
            observationPolicy, mapOf(ExtensionId.parse("de.aniworld") to 100_000_000L)).release
    },
    private val postponementStore: FileExtensionPostponementStore? = null,
    /** Shared durable ledger state: soft success freshness and denial times. Hard limits stay in the transport. */
    private val freshness: ExtensionFreshnessLedger = FileExtensionNetworkLedger(networkDirectory),
    private val freshnessPolicy: ExtensionFreshnessPolicy = ExtensionFreshnessPolicy(),
) : WorkScopedShadowRefreshCoordinator, TriggeredShadowRefreshCoordinator {
    override suspend fun refresh() = refreshForWork(UUID.randomUUID().toString())

    override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome = refresh(workId, false, null)

    /** The product entry: trigger scope, soft freshness and typed deferral. */
    override suspend fun refreshForTrigger(workId: String, trigger: ExtensionRefreshTrigger): ShadowRefreshOutcome =
        refresh(workId, true, trigger)

    suspend fun refreshForProductWork(workId: String): ShadowRefreshOutcome =
        refresh(workId, true, ExtensionRefreshTrigger.SCHEDULED_SLOT)

    private suspend fun refresh(workId: String, requireCompleteRefresh: Boolean,
                                trigger: ExtensionRefreshTrigger?): ShadowRefreshOutcome {
        val snapshot = policy.policy.value
        val selected = snapshot.activeReleaseSource ?: return ShadowRefreshOutcome.Skipped("no-active-release-source")
        val pinned = installed.loadInstalled(selected)
        // The selected source may change while its package is being loaded. Do not return a stale
        // fresh-data skip (or start work) for that old selection; let the worker retry the new one.
        if (!current(snapshot)) return ShadowRefreshOutcome.Failed("stale-generation-token", retryable = true)
        if (pinned == null) return ShadowRefreshOutcome.Skipped("active-release-source-unavailable")
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? {
                if (providerId.value != selected.providerId || !current(snapshot)) return null
                return installed.loadInstalled(selected)?.takeIf {
                    it.packageDigest == pinned.packageDigest && it.packageGeneration == pinned.packageGeneration
                }
            }
        }
        val effectiveTracks = snapshot.preferencesFor(selected).enabledTracks.intersect(
            ObservationTrack.entries.filter { it != ObservationTrack.UNKNOWN }.map { it.name }.toSet())
        // Historical mapping adapters are explicitly bound to their provider, never reused for another source.
        val targets = ExtensionTargetSource {
            if (selected.providerId == "aniworld") targetSource.targets().filter { it.target.track.name in effectiveTracks }
            else emptyList()
        }
        // Trigger scope and soft freshness, decided before any request: only the due roles are asked, and
        // roles that were not asked are neither refreshed nor judged absent.
        val sourceKey = ExtensionFreshnessKeys.source(selected)
        val provider = pinned.providerId.value
        var runRoles: Set<SourceRole> = pinned.grantedRoles
        var runTargets: ExtensionTargetSource = targets
        var dueDirect: Set<String> = emptySet()
        var markAutomaticAttempt = false
        if (trigger != null) {
            val now = clock.instant()
            val candidates = if (SourceRole.DIRECT in pinned.grantedRoles && !trigger.automatic) targets.targets() else emptyList()
            val directKeys = candidates.map { it.canonicalKey }.toSet()
            val wanted = SourceRole.entries.filter { it != SourceRole.DIRECT }.map(ExtensionFreshnessKeys::role) +
                directKeys.map(ExtensionFreshnessKeys::target) + ExtensionFreshnessKeys.AUTOMATIC_ATTEMPT
            val seen = freshness.lastSuccesses(sourceKey, provider, wanted)
            // The receipt names the source and selection generation whose rows it describes. If it does not
            // describe this source (a switch, or a preference change that bumped the generation), nothing of it
            // may be called fresh and the first fill runs; a package change of the same source keeps it.
            val old = navigationStore.state.value
            val receiptBound = old.source == selected && old.releaseGeneration == snapshot.releaseGeneration
            when (val decision = ExtensionRefreshPlanner.plan(
                trigger = trigger, granted = pinned.grantedRoles, directTargets = directKeys,
                hasCommittedData = receiptBound && reconciliation.hasCommittedCycles(selected),
                lastAutomaticAttempt = seen[ExtensionFreshnessKeys.AUTOMATIC_ATTEMPT],
                lastSuccess = ExtensionSuccessLookup { seen[ExtensionFreshnessKeys.of(it)] },
                now = now, policy = freshnessPolicy,
            )) {
                is ExtensionRefreshPlan.Skip -> {
                    if (receiptBound && (old.packageDigest != pinned.packageDigest ||
                            old.packageGeneration != pinned.packageGeneration)) {
                        // An update or rollback of the same source does not reset freshness, so no request
                        // follows. The receipt follows the newly verified package with the same accepted data and
                        // statistics; nothing is recorded as fetched.
                        policy.withCurrentSelection(snapshot) {
                            installed.withCurrentGeneration(selected, pinned.packageDigest, pinned.packageGeneration) {
                                navigationStore.record(selected, snapshot.releaseGeneration, pinned.packageDigest,
                                    old.installments, old.syncStatistics, pinned.packageGeneration)
                            }
                        }
                    }
                    return ShadowRefreshOutcome.Skipped(decision.reason, decision.nextEligibleAt)
                }
                is ExtensionRefreshPlan.Run -> {
                    runRoles = decision.roles
                    dueDirect = decision.targetKeys
                    runTargets = ExtensionTargetSource { candidates.filter { it.canonicalKey in decision.targetKeys } }
                    markAutomaticAttempt = decision.countsAsAutomaticAttempt
                }
            }
        }
        val scopedWorkId = MessageDigest.getInstance("SHA-256").digest(
            "$workId/${snapshot.releaseGeneration}/${pinned.packageDigest}/${pinned.packageGeneration}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        // The host exists only for a run that really asks the source: a typed skip never builds one.
        val dispatch = releaseHostFactory.create(repository, runtime, networkDirectory, authority.observationPolicy())
        val outcome = ExtensionShadowSyncOrchestrator(dispatch, authority, reconciliation, generations, runTargets, clock,
            providerId = pinned.providerId,
            sourceRoles = runRoles,
            enabledTracks = effectiveTracks,
            requireCompleteRefresh = requireCompleteRefresh,
            selection = selected,
            deferralProbe = { generation -> freshness.deniedUntil(provider, generation) },
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
                if (trigger != null) {
                    // Only a real network success of a whole role makes it fresh; cache, skip and error never do.
                    val keys = outcome.successfulRoles.filter { it != SourceRole.DIRECT }.map(ExtensionFreshnessKeys::role) +
                        (if (SourceRole.DIRECT in outcome.successfulRoles) dueDirect.map(ExtensionFreshnessKeys::target) else emptyList())
                    freshness.markFresh(sourceKey, provider, keys, outcome.cycle.completedAt)
                }
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
                    val state = reconciliation.getForSource(selected, identity.key) ?: return@mapNotNull null
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
                    // The rows of every usable role are committed in both cases; PARTIAL means a role dropped rows it could
                    // not read or another role was not usable, so the run does not count as a fully successful sync.
                    put("Last committed sync", outcome.cycle.completedAt.toString())
                    put("Last parse status", if (successful) "SUCCESS" else "PARTIAL")
                    put("Last sync outcome", if (successful) "COMMITTED" else "COMMITTED_PARTIAL")
                    put("Last sync failure", if (successful) "NONE" else "ROLE_HEALTH_NOT_SUCCESSFUL")
                    if (outcome.roleReports.isNotEmpty()) put("Role reports", outcome.roleReports)
                    put("Cancellation", "NOT_CANCELLED")
                    put("Transport status", "COORDINATOR_COMPLETED")
                    put("Sync duration", java.time.Duration.between(outcome.cycle.startedAt, outcome.cycle.completedAt).toMillis().toString() + " ms")
                }
                // Only a committed refresh makes this source the owner of the persisted rows.
                navigationStore.record(selected, snapshot.releaseGeneration, pinned.packageDigest, accepted, statistics,
                    pinned.packageGeneration, rowsCommitted = true)
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
                        "Role health" to pinned.grantedRoles.sortedBy { it.name }.joinToString("; ") { it.name + ": ABORTED" }) +
                        (if (outcome.roleReports.isNotEmpty()) mapOf("Role reports" to outcome.roleReports) else emptyMap()),
                    pinned.packageGeneration)
            }
        }
        // An automatic run that really asked the source counts for the hourly window; a deferral asked nothing.
        if (markAutomaticAttempt && outcome !is ShadowRefreshOutcome.Skipped) {
            freshness.markFresh(sourceKey, provider, listOf(ExtensionFreshnessKeys.AUTOMATIC_ATTEMPT), clock.instant())
        }
        // A selection or package change while the run was in flight is retried against the new selection,
        // never reported as a failure of the source the user has already left.
        if (trigger != null && outcome is ShadowRefreshOutcome.Failed && !current(snapshot)) {
            return ShadowRefreshOutcome.Failed("stale-generation-token", retryable = true)
        }
        return outcome
    }
    private fun current(snapshot: ExtensionProductPolicy): Boolean = policy.policy.value.let {
        it.activeReleaseSource == snapshot.activeReleaseSource && it.releaseGeneration == snapshot.releaseGeneration
    }
}

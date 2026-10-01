package de.kiyori.ep02

import android.content.Context
import androidx.room.Room
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.extension.*
import com.axiel7.anihyou.release.data.repository.*
import java.io.File
import java.time.Clock
import org.json.JSONArray
import org.json.JSONObject

/** Hermetic TEST-ONLY trust and DNS/CA, actual host HTTPS, guest, Authority and Room. */
internal object Ep05CanaryProof {
    suspend fun run(context: Context, runtime: AndroidIsolatedExtensionRuntime): JSONObject {
        val chain = File(context.cacheDir, "ep05-test-chain").apply { mkdirs() }
        for (name in listOf("test-pin.json", "root.json", "index.json", "aniworld-test.arex")) {
            context.assets.open("aniworld-chain/$name").use { input ->
                File(chain, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        val verified = Ep05TestTrustBridge.verify(chain)
        val clock = Clock.systemUTC()
        val time = clock.instant()
        // Independently configured PUBLIC TEST-ONLY host policy; installation cannot create it.
        val authorityTuple = ApprovedExtensionAuthorityTuple("aniworld.test.publisher",
            "091a1b9f029e775daae3c69d04a648c07b032776e666de6608b320d49e8b833a",
            "de.aniworld", "aniworld", SourceRole.entries.toSet())
        val authority = ExtensionEvidenceAuthorityAdapter(setOf(authorityTuple))
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId) = verified.takeIf { it.providerId == providerId }
        }
        fun coordinator(name: String): ExtensionHostCoordinator {
            val ledger = File(context.cacheDir, "ep05-$name-network").apply { deleteRecursively() }
            return ExtensionHostCoordinator(repository, runtime, ProductionExtensionTransportFactory.create(ledger),
                authority.observationPolicy(), clock, { true }, mapOf(verified.extensionId to 25_000_000L))
        }
        val input = context.assets.open("aniworld-inputs/release-plan-input.json").use { it.readBytes() }
        val targets = ExtensionWireCodec.decodePlanInput(input).context.targets
        val completed = coordinator("canary").execute(ExtensionRunRequest(verified.providerId,
            "ep05-canary-provenance", SourceRole.entries.toSet(), targets)) as? ExtensionHostResult.Completed
            ?: error("real guest HTTPS canary did not complete")
        check(completed.observations.size == 7)
        check(completed.responseProvenance.size == 4)
        check(completed.reports.all { it.outcome == ExtensionReportOutcome.SUCCESS })
        check(completed.responseProvenance.all { it.httpStatus == 200 && it.destinationAddress == "8.8.8.8" })
        check(completed.receipt.packageDigest == verified.packageDigest && completed.receipt.moduleDigest == verified.moduleDigest)
        val calendar = completed.observations.filter { it.sourceRole == SourceRole.CALENDAR }
        check(calendar.all { it.claimKind == ObservationClaimKind.FORECAST && it.approximate &&
            it.sourceDateText != null && it.sourceTimeText != null && it.parsedTimestamp == null })
        val evidence = authority.project(completed)
        check(evidence.size == 5 && evidence.map { it.id }.distinct().size == 5)
        check(evidence.filter { it.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR }
            .all { it.evidenceType == ReleaseEvidenceType.FORECAST })
        check(evidence.none { it.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT })
        check(ExtensionEvidenceAuthorityAdapter(emptySet()).project(completed).isEmpty())
        check(ExtensionEvidenceAuthorityAdapter(setOf(authorityTuple.copy(signingKeyId = "wrong-test-key")))
            .project(completed).isEmpty())
        check(authority.project(completed.copy(observations = completed.observations.map {
            it.copy(track = ObservationTrack.UNKNOWN) })).isEmpty())
        val direct = evidence.single { it.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE }
        val canonicalKey = requireNotNull(CanonicalReleaseIdentity.from(direct)).key
        val navigation = JSONArray()
        for (kind in listOf(NavigationTargetKind.OVERVIEW, NavigationTargetKind.EPISODE)) {
            val selected = targets.single()
            val mapped = NavigationContextV1(1, verified.extensionId, verified.providerId, time.toString(),
                kind, selected.targetToken, selected.providerSeriesKey, null,
                if (kind == NavigationTargetKind.EPISODE) selected.navigationSeason else null,
                if (kind == NavigationTargetKind.EPISODE) selected.installment.number else null,
                if (kind == NavigationTargetKind.EPISODE) selected.track else null)
            val network = File(context.cacheDir, "ep05-nav-${kind.name}").apply { deleteRecursively() }
            val dispatcher = ProductionNavigationDispatcher(repository, runtime,
                ProductionExtensionTransportFactory.create(network), clock)
            val target = requireNotNull(dispatcher.navigate(mapped, "ep05-navigation-${kind.name}"))
            check(target.providerSeriesKey == selected.providerSeriesKey && target.targetKind == kind)
            if (kind == NavigationTargetKind.EPISODE) check(target.providerEpisode == selected.installment.number &&
                target.track == selected.track)
            navigation.put(JSONObject().put("kind", kind.name).put("url", target.url).put("sourceHash", target.sourceHash))
        }
        val databaseName = "ep05-real-guest-shadow.db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName).build()
        try {
            val reconciliation = RoomReleaseReconciliationRepository(database)
            val generations = RoomExtensionShadowGenerationStore(database, reconciliation, clock, "ep05-android-process")
            val orchestrator = ExtensionShadowSyncOrchestrator(coordinator("shadow"), authority, reconciliation,
                generations, ExtensionTargetSource { targets.map { ExtensionAcquisitionTarget(it, canonicalKey) } }, clock)
            val outcome = orchestrator.refreshForWork("ep05-real-guest-canary") as? ShadowRefreshOutcome.Committed
                ?: error("actual Room Shadow commit failed")
            val row = requireNotNull(database.aniworldPollDao().committedCycleGeneration(
                RoomExtensionShadowGenerationStore.SCOPE_ID, outcome.cycle.id))
            check(row.state == "COMMITTED" && row.manifestPayload.contains(verified.packageDigest))
            check(row.manifestPayload.contains(verified.moduleDigest))
            check(outcome.cycle.sources.flatMap { it.evidence }.size == 5)
            val decision = requireNotNull(database.reconciliationDao().projection(canonicalKey))
            check(decision.underlyingPhase == "RELEASED" && decision.authority == "ANIWORLD")
            val health = JSONArray()
            for (type in listOf(ReleaseSourceType.ANIWORLD_CALENDAR, ReleaseSourceType.ANIWORLD_RECENT,
                    ReleaseSourceType.ANIWORLD_POSTPONEMENT, ReleaseSourceType.ANIWORLD_DIRECT_PAGE)) {
                val stored = requireNotNull(database.aniworldPollDao().health(type.name))
                check(stored.status == "HEALTHY")
                health.put(JSONObject().put("sourceType", type.name).put("status", stored.status))
            }
            val retry = orchestrator.refreshForWork("ep05-real-guest-canary")
            check(retry is ShadowRefreshOutcome.Skipped && retry.reason == "generation-already-committed")
            return JSONObject().put("testTrustOnly", true).put("productionPublication", false)
                .put("packageDigest", verified.packageDigest).put("moduleDigest", verified.moduleDigest)
                .put("extensionVersion", "1.0.0-test.1").put("releaseSequence", verified.releaseSequence)
                .put("generationId", row.generationId).put("cycleId", outcome.cycle.id)
                .put("realProductionHttpsTransport", true).put("dnsBoundTlsSocket", true)
                .put("isolatedRealGuest", true).put("observationCount", completed.observations.size)
                .put("evidenceCount", evidence.size).put("authorityRequiresExactHostTuple", true)
                .put("navigation", navigation)
                .put("calendarForecastOnly", true).put("calendarWallTimeWithoutInventedTimezone", true)
                .put("unknownTrackNoAuthority", true).put("unboundPostponementNoAuthority", true)
                .put("roomShadowCommitted", true).put("idempotentWorkRetry", true).put("sourceHealth", health)
                .put("authorityDecision", JSONObject().put("canonicalKey", canonicalKey)
                    .put("phase", decision.phase).put("underlyingPhase", decision.underlyingPhase)
                    .put("authority", decision.authority))
                .put("provenance", JSONArray(completed.responseProvenance.map { p -> JSONObject()
                    .put("requestId", p.requestId).put("finalUrl", p.finalUrl).put("sourceHash", p.sourceHash)
                    .put("httpStatus", p.httpStatus).put("bodyBytes", p.bodyBytes)
                    .put("destination", p.destinationAddress).put("redirects", p.redirectCount) }))
                .put("evidence", JSONArray(evidence.map { e -> JSONObject().put("id", e.id)
                    .put("sourceType", e.sourceType.name).put("evidenceType", e.evidenceType.name) }))
        } finally { database.close(); context.deleteDatabase(databaseName) }
    }
}

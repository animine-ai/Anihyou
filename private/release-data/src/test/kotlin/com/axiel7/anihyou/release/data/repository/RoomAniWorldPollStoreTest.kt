package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionReceipt
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.api.ShadowRunMetrics
import com.axiel7.anihyou.release.core.api.ShadowGenerationManifest
import com.axiel7.anihyou.release.core.api.ShadowGenerationToken
import com.axiel7.anihyou.release.core.api.ShadowSourceSpec
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.ExpectedSourceInstance
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.data.aniworld.AniWorldExtensionTargetSource
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.extension.ExtensionAcquisitionTarget
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomAniWorldPollStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "aniworld-poll-store-test.db"
    private val startedAt = Instant.parse("2026-09-26T07:00:00Z")
    private val clock = Clock.fixed(startedAt, ZoneOffset.UTC)

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun aggregateListBudgetReservesAtMostEighteenAcrossAllThreeSources() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomAniWorldPollStore(database, RoomReleaseReconciliationRepository(database), clock)
            val manifest = manifest("budget")
            assertTrue(store.beginGeneration(manifest, DirectTargetSelectionPolicy.snapshotDigest(emptyList())))

            val roots = listOf(
                "CALENDAR" to "https://aniworld.to/animekalender",
                "RECENT" to "https://aniworld.to/neue-episoden",
                "POSTPONEMENT" to "https://aniworld.to/support/frage/anime-verschiebungen",
            )
            roots.forEach { (role, root) -> repeat(6) { offset ->
                assertNotNull(store.reserveRequest(manifest.token, role, root, root,
                    startedAt.plusSeconds(offset + 1L)))
            } }

            assertNull(store.reserveRequest(manifest.token, "CALENDAR", roots.first().second,
                roots.first().second, startedAt.plusSeconds(30)))
            val snapshot = store.currentGeneration(manifest.token)
            assertNotNull(snapshot)
            assertEquals(18, snapshot!!.listReserved)
            assertEquals(0, snapshot.directReserved)
            val nextEligible = database.openHelper.writableDatabase.query(
                "SELECT nextEligibleAt FROM v3_request_state WHERE scopeKey=?",
                arrayOf("url:https://aniworld.to/animekalender"),
            ).use { assertTrue(it.moveToFirst()); it.getString(0) }
            assertTrue(nextEligible.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z")))
        } finally {
            database.close()
        }
    }

    @Test
    fun reservationPersistsAcrossStoreReopenAndOldProcessEpochIsFenced() = runBlocking {
        val first = openDatabase()
        val oldManifest = manifest("restart-old", epoch = "process-old")
        try {
            val store = RoomAniWorldPollStore(first, RoomReleaseReconciliationRepository(first), clock)
            assertTrue(store.beginGeneration(oldManifest, DirectTargetSelectionPolicy.snapshotDigest(emptyList())))
            assertNotNull(store.reserveRequest(oldManifest.token, "RECENT",
                "https://aniworld.to/neue-episoden", "https://aniworld.to/neue-episoden",
                startedAt.plusSeconds(1)))
        } finally {
            first.close()
        }

        val reopened = openDatabase()
        try {
            val store = RoomAniWorldPollStore(reopened, RoomReleaseReconciliationRepository(reopened),
                Clock.fixed(startedAt.plus(Duration.ofMinutes(31)), ZoneOffset.UTC))
            val oldSnapshot = store.currentGeneration(oldManifest.token)
            assertNotNull(oldSnapshot)
            assertEquals(1, oldSnapshot!!.listReserved)

            val newManifest = manifest("restart-new", epoch = "process-new",
                started = startedAt.plus(Duration.ofMinutes(31)))
            assertTrue(store.beginGeneration(newManifest,
                DirectTargetSelectionPolicy.snapshotDigest(emptyList())))
            assertNull(store.currentGeneration(oldManifest.token))
            assertNotNull(store.currentGeneration(newManifest.token))
            assertNull(store.reserveRequest(oldManifest.token, "RECENT",
                "https://aniworld.to/neue-episoden", "https://aniworld.to/neue-episoden",
                startedAt.plus(Duration.ofMinutes(32))))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun t4FailureRollsBackCycleHealthProjectionAndMetricButKeepsT2Reservation() = runBlocking {
        val database = openDatabase()
        val at = startedAt.plusSeconds(15)
        val repository = RoomReleaseReconciliationRepository(database)
        try {
            repository.importBaseline()
            val store = RoomAniWorldPollStore(database, repository, Clock.fixed(at, ZoneOffset.UTC))
            val manifest = manifest("rollback")
            assertTrue(store.beginGeneration(manifest, DirectTargetSelectionPolicy.snapshotDigest(emptyList())))
            assertNotNull(store.reserveRequest(manifest.token, "RECENT",
                "https://aniworld.to/neue-episoden", "https://aniworld.to/neue-episoden", startedAt.plusSeconds(1)))

            val evidence = evidence(at)
            val observations = manifest.sources.mapIndexed { index, source ->
                val type = ReleaseSourceType.valueOf(source.sourceType)
                CycleSourceObservation(
                    instanceId = source.instanceId,
                    sourceType = type,
                    targetKey = source.targetKey,
                    track = null,
                    result = if (index == 2) CycleResult.PARTIAL_SUCCESS else CycleResult.SUCCESS,
                    health = if (index == 2) SourceHealthStatus.DEGRADED else SourceHealthStatus.HEALTHY,
                    observedAt = at,
                    evidence = if (index == 1) listOf(evidence) else emptyList(),
                )
            }
            val cycle = CompletedObservationCycle(
                id = manifest.token.generationId,
                scopeId = RoomAniWorldPollStore.SCOPE_ID,
                startedAt = manifest.startedAt,
                completedAt = at,
                policy = AbsencePolicySnapshot(),
                sources = observations,
                manifest = manifest.sources.map { source -> ExpectedSourceInstance(
                    source.instanceId, ReleaseSourceType.valueOf(source.sourceType), source.targetKey,
                    source.track?.let(LanguageTrack::valueOf), negativeRequired = false,
                ) },
            )
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_shadow_metric BEFORE INSERT ON v3_shadow_metric " +
                    "BEGIN SELECT RAISE(ABORT, 'injected T4 failure'); END",
            )

            assertTrue(runCatching { store.commitGeneration(manifest.token, cycle,
                ShadowRunMetrics(3, 3, 2, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 10)) }.isFailure)

            val sql = database.openHelper.writableDatabase
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_observation_cycle"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_cycle_source_observation"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_cycle_evidence_receipt"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_release_evidence"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_canonical_release_projection"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_source_health"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_reconciliation_event"))
            assertEquals(0L, scalar(sql, "SELECT count(*) FROM v3_shadow_metric"))
            assertEquals(1L, scalar(sql, "SELECT count(*) FROM v3_http_attempt WHERE outcome='RESERVED'"))
            assertEquals("RUNNING", sql.query("SELECT state FROM v3_poll_generation").use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
        } finally {
            database.close()
        }
    }

    @Test
    fun hundredsOfTrackDecisionsShareFourOpaqueTargetsAndLegacyIngressStaysRetired() = runBlocking {
        val database = openDatabase()
        try {
            val reconciliation = RoomReleaseReconciliationRepository(database)
            reconciliation.importBaseline()
            val evidence = (1..100).flatMap { episode ->
                LanguageTrack.entries.map { track -> evidence(episode, track) }
            }
            val seedCycle = CompletedObservationCycle(
                id = "wp04b-fanout-seed",
                scopeId = "wp04b-fanout-seed",
                startedAt = startedAt.minusSeconds(10),
                completedAt = startedAt,
                policy = AbsencePolicySnapshot(),
                sources = listOf(CycleSourceObservation(
                    instanceId = "wp04b-calendar-seed",
                    sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                    targetKey = "wp04b-fanout-fixture",
                    track = null,
                    result = CycleResult.SUCCESS,
                    health = SourceHealthStatus.HEALTHY,
                    observedAt = startedAt,
                    evidence = evidence,
                )),
            )
            reconciliation.persistCompletedCycle(seedCycle)

            val store = RoomAniWorldPollStore(database, reconciliation, clock)
            val mapped = store.eligibleMappedDirectTargets(startedAt)
            assertEquals(200, mapped.sumOf { it.exactTargetKeys.size })
            assertTrue(mapped.all { it.canonicalUrl.startsWith("mapped-direct-v1:") &&
                !it.canonicalUrl.contains("https://") && it.providerSeriesKey != null && it.navigationSeason != null })
            val mappedSelected = DirectTargetSelectionPolicy.select(mapped, startedAt)
            assertEquals(4, mappedSelected.size)
            assertTrue(mappedSelected.all { it.exactTargetKeys.size == 2 })
            // The retired built-in path must never recreate a website URL from these projections.
            assertTrue(store.eligibleDirectTargets(startedAt).isEmpty())
            val legacyManifest = manifest("retired-direct")
            assertTrue(store.beginGeneration(legacyManifest,
                DirectTargetSelectionPolicy.snapshotDigest(emptyList())))
            val snapshot = store.currentGeneration(legacyManifest.token)
            assertNotNull(snapshot)
            assertEquals(0, snapshot!!.directReserved)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedExtensionCommitRotatesMappedCoordinatesAndFencesStaleTargetAttempts() = runBlocking {
        val firstDatabase = openDatabase()
        val firstCompletedAt = startedAt.plus(Duration.ofMinutes(5))
        var firstCoordinateKeys: Set<String> = emptySet()
        try {
            val reconciliation = RoomReleaseReconciliationRepository(firstDatabase)
            reconciliation.importBaseline()
            val seedEvidence = (1..100).flatMap { episode ->
                LanguageTrack.entries.map { track -> evidence(episode, track) }
            }
            reconciliation.persistCompletedCycle(CompletedObservationCycle(
                id = "wp04b-extension-fairness-seed",
                scopeId = "wp04b-extension-fairness-seed",
                startedAt = startedAt.minusSeconds(10),
                completedAt = startedAt,
                policy = AbsencePolicySnapshot(),
                sources = listOf(CycleSourceObservation(
                    instanceId = "wp04b-extension-fairness-calendar",
                    sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                    targetKey = "wp04b-extension-fairness-fixture",
                    track = null,
                    result = CycleResult.SUCCESS,
                    health = SourceHealthStatus.HEALTHY,
                    observedAt = startedAt,
                    evidence = seedEvidence,
                )),
            ))

            val pollStore = RoomAniWorldPollStore(firstDatabase, reconciliation, clock)
            val mapped = pollStore.eligibleMappedDirectTargets(startedAt)
            assertEquals(200, mapped.sumOf { it.exactTargetKeys.size })
            val selected = DirectTargetSelectionPolicy.select(mapped, startedAt)
            assertEquals(4, selected.size)
            assertTrue(selected.all { it.exactTargetKeys.size == 2 })

            val selectedTargets = AniWorldExtensionTargetSource(pollStore, clock).targets()
            assertEquals(8, selectedTargets.size)
            assertEquals(selected.flatMap { it.exactTargetKeys }.toSet(), selectedTargets.map { it.canonicalKey }.toSet())
            val targetsByCoordinate = selectedTargets.groupBy(::mappedCoordinateKey)
            assertEquals(4, targetsByCoordinate.size)
            targetsByCoordinate.values.forEach { trackTargets ->
                assertEquals(2, trackTargets.map { it.canonicalKey }.toSet().size)
                assertEquals(setOf(ObservationTrack.DE_SUB, ObservationTrack.DE_DUB),
                    trackTargets.map { it.target.track }.toSet())
            }
            firstCoordinateKeys = targetsByCoordinate.keys

            val generations = RoomExtensionShadowGenerationStore(
                firstDatabase, reconciliation, clock, processEpoch = "fairness-process-first")
            val token = (generations.claim("mapped-fairness-first", startedAt)
                as ExtensionShadowGenerationClaim.Acquired).token
            val cycle = incompleteDirectCycle(token, firstCompletedAt, selectedTargets)
            assertTrue(cycle.sources.isNotEmpty())
            assertTrue(cycle.sources.all {
                it.result == CycleResult.INCOMPLETE && it.evidence.isEmpty()
            })
            assertTrue(generations.commit(
                token, cycle, extensionReceipt(token, firstCompletedAt), emptyList(), targets = selectedTargets))

            firstCoordinateKeys.forEach { coordinateKey ->
                val state = targetAttemptTimes(firstDatabase, coordinateKey)
                assertEquals(firstCompletedAt, state?.lastAttemptAt)
            }
        } finally {
            firstDatabase.close()
        }

        // Reopen both stores as a fresh process. The attempt times from the prior extension
        // generation must remain visible to the selector, so least-recent-attempt ordering
        // advances its next four slots to new physical episode coordinates. The independent
        // six-hour physical-URL ledger does not synthesize logical target cooldown state here.
        val secondSelectionAt = firstCompletedAt.plus(Duration.ofMinutes(1))
        val reopenedDatabase = openDatabase()
        try {
            val reconciliation = RoomReleaseReconciliationRepository(reopenedDatabase)
            val reopenedPollStore = RoomAniWorldPollStore(
                reopenedDatabase, reconciliation, Clock.fixed(secondSelectionAt, ZoneOffset.UTC))
            firstCoordinateKeys.forEach { coordinateKey ->
                assertEquals(firstCompletedAt, targetAttemptTimes(reopenedDatabase, coordinateKey)?.lastAttemptAt)
            }

            val nextCandidates = reopenedPollStore.eligibleMappedDirectTargets(secondSelectionAt)
            val nextSelection = DirectTargetSelectionPolicy.select(nextCandidates, secondSelectionAt)
            assertEquals(4, nextSelection.size)
            val nextTargets = AniWorldExtensionTargetSource(
                reopenedPollStore, Clock.fixed(secondSelectionAt, ZoneOffset.UTC)).targets()
            assertEquals(8, nextTargets.size)
            val nextByCoordinate = nextTargets.groupBy(::mappedCoordinateKey)
            val nextCoordinateKeys = nextByCoordinate.keys
            assertEquals(4, nextCoordinateKeys.size)
            assertTrue(firstCoordinateKeys.intersect(nextCoordinateKeys).isEmpty())
            assertEquals(nextSelection.flatMap { it.exactTargetKeys }.toSet(), nextTargets.map { it.canonicalKey }.toSet())
            nextByCoordinate.values.forEach { trackTargets ->
                assertEquals(setOf(ObservationTrack.DE_SUB, ObservationTrack.DE_DUB),
                    trackTargets.map { it.target.track }.toSet())
            }
            nextCoordinateKeys.forEach { coordinateKey ->
                assertNull(targetAttemptTimes(reopenedDatabase, coordinateKey)?.lastAttemptAt)
            }

            val staleStore = RoomExtensionShadowGenerationStore(
                reopenedDatabase, reconciliation, processEpoch = "fairness-process-stale")
            val staleToken = (staleStore.claim("mapped-fairness-stale", secondSelectionAt)
                as ExtensionShadowGenerationClaim.Acquired).token
            val restartedStore = RoomExtensionShadowGenerationStore(
                reopenedDatabase, reconciliation, processEpoch = "fairness-process-restarted")
            val currentToken = (restartedStore.claim("mapped-fairness-stale", secondSelectionAt.plusSeconds(1))
                as ExtensionShadowGenerationClaim.Acquired).token
            assertEquals(staleToken.cycleId, currentToken.cycleId)
            assertTrue(staleToken.executionGenerationId != currentToken.executionGenerationId)

            val staleCompletedAt = secondSelectionAt.plus(Duration.ofMinutes(2))
            val staleCycle = incompleteDirectCycle(staleToken, staleCompletedAt, nextTargets)
            assertFalse(staleStore.commit(
                staleToken, staleCycle, extensionReceipt(staleToken, staleCompletedAt),
                emptyList(), targets = nextTargets))
            nextCoordinateKeys.forEach { coordinateKey ->
                assertNull("stale generation advanced $coordinateKey",
                    targetAttemptTimes(reopenedDatabase, coordinateKey)?.lastAttemptAt)
            }

            val currentCompletedAt = secondSelectionAt.plus(Duration.ofMinutes(3))
            val currentCycle = incompleteDirectCycle(currentToken, currentCompletedAt, nextTargets)
            assertTrue(restartedStore.commit(
                currentToken, currentCycle, extensionReceipt(currentToken, currentCompletedAt),
                emptyList(), targets = nextTargets))
            nextCoordinateKeys.forEach { coordinateKey ->
                assertEquals(currentCompletedAt, targetAttemptTimes(reopenedDatabase, coordinateKey)?.lastAttemptAt)
            }
        } finally {
            reopenedDatabase.close()
        }
    }

    private fun openDatabase(): ReleaseDatabase = Room.databaseBuilder(
        context, ReleaseDatabase::class.java, databaseName,
    ).allowMainThreadQueries().build()

    private fun manifest(
        suffix: String,
        epoch: String = "process-$suffix",
        started: Instant = startedAt,
    ): ShadowGenerationManifest {
        val token = ShadowGenerationToken("aw-shadow-v1:$suffix", "owner-$suffix", epoch)
        val sources = fixedListSources()
        return ShadowGenerationManifest(token, 1, started, started.plus(Duration.ofMinutes(4)), sources,
            AniWorldShadowManifestCodec.digest(sources))
    }

    private fun fixedListSources() = listOf(
            ShadowSourceSpec("aw:list:calendar:v1", "ANIWORLD_CALENDAR", "aniworld:list:calendar", null,
                "https://aniworld.to/animekalender", true),
            ShadowSourceSpec("aw:list:recent:v1", "ANIWORLD_RECENT", "aniworld:list:recent", null,
                "https://aniworld.to/neue-episoden", true),
            ShadowSourceSpec("aw:list:postponement:v1", "ANIWORLD_POSTPONEMENT", "aniworld:list:postponement", null,
                "https://aniworld.to/support/frage/anime-verschiebungen", false),
        )

    private fun evidence(episode: Int, track: LanguageTrack): ReleaseEvidence {
        val forecast = startedAt.plus(Duration.ofHours(6))
        val item = ReleaseEvidence(
            id = "temporary-$episode-$track",
            sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
            sourceUrl = "https://aniworld.to/animekalender",
            sourceHash = "seed-$episode-$track",
            parserVersion = "wp04b-seed",
            observedAt = startedAt,
            sourceReportedAt = forecast,
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier("wp04b-fanout-series"),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(episode),
            languageTrack = track,
            evidenceType = ReleaseEvidenceType.FORECAST,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        return item.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(item))
    }

    private fun evidence(at: Instant): ReleaseEvidence {
        val item = ReleaseEvidence(
            id = "temporary-id",
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceUrl = "https://aniworld.to/neue-episoden",
            sourceHash = "fixture-hash",
            parserVersion = "wp04b-test",
            observedAt = at,
            sourceReportedAt = null,
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier("wp04b-rollback-series"),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.CONFIRMATION,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        return item.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(item))
    }

    private fun mappedCoordinateKey(target: ExtensionAcquisitionTarget): String {
        val identity = requireNotNull(CanonicalReleaseIdentity.decode(target.canonicalKey))
        val episode = identity.installment as Installment.Episode
        return requireNotNull(DirectTargetSelectionPolicy.mappedCoordinateKey(
            requireNotNull(target.target.providerSeriesKey), requireNotNull(target.target.navigationSeason), episode.number))
    }

    private fun incompleteDirectCycle(
        token: ExtensionShadowGenerationToken,
        completedAt: Instant,
        targets: List<ExtensionAcquisitionTarget>,
    ): CompletedObservationCycle {
        val sources = targets.mapIndexed { index, target ->
            val track = when (target.target.track) {
                ObservationTrack.DE_SUB -> LanguageTrack.DE_SUB
                ObservationTrack.DE_DUB -> LanguageTrack.DE_DUB
                ObservationTrack.UNKNOWN -> error("fixture must have a selected German track")
            }
            CycleSourceObservation(
                instanceId = "aw:extension:direct:v1:${index + 1}",
                sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
                targetKey = target.canonicalKey,
                track = track,
                result = CycleResult.INCOMPLETE,
                health = SourceHealthStatus.UNAVAILABLE,
                observedAt = completedAt,
                evidence = emptyList(),
            )
        }
        return CompletedObservationCycle(
            id = token.cycleId,
            scopeId = RoomExtensionShadowGenerationStore.SCOPE_ID,
            startedAt = token.startedAt,
            completedAt = completedAt,
            policy = AbsencePolicySnapshot(),
            sources = sources,
            manifest = sources.map {
                ExpectedSourceInstance(it.instanceId, it.sourceType, it.targetKey, it.track, negativeRequired = false)
            },
        )
    }

    private fun extensionReceipt(
        token: ExtensionShadowGenerationToken,
        completedAt: Instant,
    ) = ExtensionExecutionReceipt(
        receiptId = "fixture-${token.workId}",
        generationId = token.executionGenerationId,
        extensionId = ExtensionId.parse("de.aniworld"),
        providerId = ProviderId.parse("aniworld"),
        publisherId = "fixture-publisher",
        signingKeyId = "fixture-signing-key",
        trustRootVersion = 1,
        packageDigest = "a".repeat(64),
        manifestDigest = "b".repeat(64),
        moduleDigest = "c".repeat(64),
        releaseSequence = 1,
        abiVersion = 1,
        policyVersion = 1,
        runtimeVersion = "fixture-runtime",
        startedAt = token.startedAt.toString(),
        completedAt = completedAt.toString(),
    )

    private data class TargetAttemptTimes(
        val lastAttemptAt: Instant?,
    )

    private fun targetAttemptTimes(
        database: ReleaseDatabase,
        coordinateKey: String,
    ): TargetAttemptTimes? = database.openHelper.writableDatabase.query(
        "SELECT lastAttemptAt FROM v3_request_state WHERE scopeKey=?",
        arrayOf("url:$coordinateKey"),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        TargetAttemptTimes(
            lastAttemptAt = if (cursor.isNull(0)) null else Instant.parse(cursor.getString(0)),
        )
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String): Long =
        db.query(query).use { assertTrue(it.moveToFirst()); it.getLong(0) }
}

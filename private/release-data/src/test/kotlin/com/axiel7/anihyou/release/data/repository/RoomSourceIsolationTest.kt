package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.SourceReleaseProjectionEntity
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R04 positive isolation: every extension release source folds its own cycles into its own rows
 * (Room v14). The cases mirror the planner's must-cases: different rows, the same canonical key with
 * different values, A to B to A with a restart, failed and interrupted commits, replay safety.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSourceIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "source-isolation-test.db"
    private val scope = RoomExtensionShadowGenerationStore.SCOPE_ID
    private val t0 = Instant.parse("2026-09-26T10:00:00Z")
    private val keyA = ExtensionSelectionKey("source-a", "de.aniworld", "publisher.a", "aniworld")
    private val keyB = ExtensionSelectionKey("source-b", "de.aniworld", "publisher.b", "aniworld")

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun open() = Room.databaseBuilder(context, ReleaseDatabase::class.java, name)
        .allowMainThreadQueries().build()

    private fun forecast(series: String, reportedAt: Instant, observedAt: Instant): ReleaseEvidence {
        val item = ReleaseEvidence(
            "forecast-$series-$reportedAt-$observedAt", ReleaseSourceType.ANIWORLD_CALENDAR,
            "https://aniworld.to/anime/stream/$series", "hash-$series-$reportedAt-$observedAt", "fixture",
            observedAt, reportedAt, false, AniWorldSiteIdentifier(series), 2, 4,
            Installment.Episode(1), LanguageTrack.DE_SUB, ReleaseEvidenceType.FORECAST,
            ScheduleCondition.UNKNOWN, ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        return item.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(item))
    }

    private fun keyOf(series: String) = CanonicalReleaseIdentity.from(
        forecast(series, t0, t0))!!.key

    private fun cycle(id: String, series: String, reportedAt: Instant, completedAt: Instant): CompletedObservationCycle {
        val item = forecast(series, reportedAt, completedAt)
        return CompletedObservationCycle(
            id, scope, completedAt.minusSeconds(30), completedAt, AbsencePolicySnapshot(),
            listOf(CycleSourceObservation(
                "$id:source", item.sourceType, CanonicalReleaseIdentity.from(item)?.key ?: "scope",
                item.languageTrack, CycleResult.SUCCESS, SourceHealthStatus.HEALTHY,
                observedAt = completedAt, evidence = listOf(item),
            )),
        )
    }

    private suspend fun commit(
        db: ReleaseDatabase, source: ExtensionSelectionKey?, id: String, series: String,
        reportedAt: Instant, completedAt: Instant,
    ) {
        val reconciliation = RoomReleaseReconciliationRepository(db)
        reconciliation.importBaseline()
        reconciliation.persistCompletedCycle(cycle(id, series, reportedAt, completedAt), source)
    }

    private suspend fun rows(db: ReleaseDatabase, key: ExtensionSelectionKey): List<SourceReleaseProjectionEntity> =
        db.reconciliationDao().sourceProjectionPage(key.sourceId, key.extensionId, key.publisherId, key.providerId, 256, 0)

    private suspend fun row(db: ReleaseDatabase, key: ExtensionSelectionKey, projectionKey: String) =
        db.reconciliationDao().sourceProjection(key.sourceId, key.extensionId, key.publisherId, key.providerId, projectionKey)

    @Test fun differentRowsOfSourcesAAndBStayInTheirOwnSource() = runBlocking {
        val db = open()
        try {
            commit(db, keyA, "c-a", "a-only", t0, t0.plusSeconds(60))
            commit(db, keyB, "c-b", "b-only", t0, t0.plusSeconds(120))
            assertEquals(listOf(keyOf("a-only")), rows(db, keyA).map { it.projectionKey })
            assertEquals(listOf(keyOf("b-only")), rows(db, keyB).map { it.projectionKey })
            assertEquals("the provider-wide history keeps both", 2, db.reconciliationDao().projectionPage(256, 0).size)
            assertEquals(keyA.sourceId, db.reconciliationDao().provenance("c-a")?.sourceId)
            assertEquals(keyB.sourceId, db.reconciliationDao().provenance("c-b")?.sourceId)
        } finally { db.close() }
    }

    @Test fun theSameCanonicalKeyWithDifferentValuesIsFoldedPerSource() = runBlocking {
        val db = open()
        try {
            val x = Instant.parse("2026-09-26T10:00:00Z")
            val y = Instant.parse("2026-09-28T10:00:00Z")
            commit(db, keyA, "c-a", "shared", x, t0.plusSeconds(60))
            commit(db, keyB, "c-b", "shared", y, t0.plusSeconds(120))
            val key = keyOf("shared")
            assertEquals(x.toString(), row(db, keyA, key)?.forecastAt)
            assertEquals(y.toString(), row(db, keyB, key)?.forecastAt)
            val reconciliation = RoomReleaseReconciliationRepository(db)
            assertEquals(x, reconciliation.getForSource(keyA, key)?.forecastAt)
            assertEquals(y, reconciliation.getForSource(keyB, key)?.forecastAt)
            assertNotNull("the provider-wide row still exists", reconciliation.get(key))
        } finally { db.close() }
    }

    @Test fun aThenBThenAKeepsEachSourcesOwnRowsAcrossARestart() = runBlocking {
        var db = open()
        try {
            val x = Instant.parse("2026-09-26T10:00:00Z")
            val y = Instant.parse("2026-09-28T10:00:00Z")
            val z = Instant.parse("2026-09-30T10:00:00Z")
            val key = keyOf("shared")
            commit(db, keyA, "c-1", "shared", x, t0.plusSeconds(60))
            val firstA = requireNotNull(row(db, keyA, key))
            commit(db, keyB, "c-2", "shared", y, t0.plusSeconds(120))
            val firstB = requireNotNull(row(db, keyB, key))
            db.close()

            db = open()
            assertEquals("A's row survived B's commit and the restart", firstA, row(db, keyA, key))
            assertEquals("B's row survived the restart", firstB, row(db, keyB, key))
            commit(db, keyA, "c-3", "shared", z, t0.plusSeconds(180))
            val secondA = requireNotNull(row(db, keyA, key))
            assertEquals(z.toString(), secondA.forecastAt)
            assertTrue("A built on its own previous revision", secondA.revision > firstA.revision)
            assertEquals("B is untouched by A's second commit", firstB, row(db, keyB, key))
            assertEquals(3, db.reconciliationDao().provenancePage(10, 0).size)
        } finally { db.close() }
    }

    @Test fun aFailedCommitOfBLeavesNothingOfBAndKeepsAIntact() = runBlocking {
        val db = open()
        try {
            commit(db, keyA, "c-a", "a-only", t0, t0.plusSeconds(60))
            val before = rows(db, keyA)
            val globalBefore = db.reconciliationDao().projectionPage(256, 0)
            val good = cycle("c-b", "b-only", t0, t0.plusSeconds(120))
            // The second instance is invalid (a direct page without a canonical target), so the whole commit must fail.
            val invalid = good.copy(sources = good.sources + CycleSourceObservation(
                "c-b:bad", ReleaseSourceType.ANIWORLD_DIRECT_PAGE, "not-a-canonical-key", LanguageTrack.DE_SUB,
                CycleResult.SUCCESS, SourceHealthStatus.HEALTHY, observedAt = good.completedAt, evidence = emptyList(),
            ))
            try {
                RoomReleaseReconciliationRepository(db).persistCompletedCycle(invalid, keyB)
                fail("an invalid cycle must not commit")
            } catch (expected: IllegalStateException) { }
            assertTrue(rows(db, keyB).isEmpty())
            assertNull(db.reconciliationDao().provenance("c-b"))
            assertNull(db.reconciliationDao().cycle("c-b"))
            assertEquals(globalBefore, db.reconciliationDao().projectionPage(256, 0))
            assertEquals(before, rows(db, keyA))
        } finally { db.close() }
    }

    @Test fun aCrashAfterWritingTheRowsButBeforeTheTransactionEndsRollsEverythingBack() = runBlocking {
        var db = open()
        try {
            commit(db, keyA, "c-a", "a-only", t0, t0.plusSeconds(60))
            val reconciliation = RoomReleaseReconciliationRepository(db)
            try {
                db.withTransaction {
                    reconciliation.persistCompletedCycle(cycle("c-b", "b-only", t0, t0.plusSeconds(120)), keyB)
                    // The rows and the provenance are visible inside the unfinished transaction ...
                    assertEquals(1, rows(db, keyB).size)
                    throw IllegalStateException("simulated process death before the commit")
                }
            } catch (expected: IllegalStateException) { }
            // ... and gone afterwards, also after a reopen.
            db.close()
            db = open()
            assertTrue(rows(db, keyB).isEmpty())
            assertNull(db.reconciliationDao().provenance("c-b"))
            assertNull(db.reconciliationDao().cycle("c-b"))
            assertEquals(1, rows(db, keyA).size)
        } finally { db.close() }
    }

    @Test fun replayingACycleIsIdempotentAndNeverMovesItToAnotherSource() = runBlocking {
        val db = open()
        try {
            commit(db, keyA, "c-a", "a-only", t0, t0.plusSeconds(60))
            val before = rows(db, keyA)
            commit(db, keyA, "c-a", "a-only", t0, t0.plusSeconds(60))
            assertEquals(before, rows(db, keyA))
            assertEquals(1, db.reconciliationDao().provenancePage(10, 0).size)
            try {
                commit(db, keyB, "c-a", "a-only", t0, t0.plusSeconds(60))
                fail("a cycle committed by A must not be replayable as B's")
            } catch (expected: IllegalStateException) { }
            assertTrue(rows(db, keyB).isEmpty())
        } finally { db.close() }
    }

    @Test fun theLegacyRouteWithoutASelectionWritesNoProvenanceAndNoSourceRows() = runBlocking {
        val db = open()
        try {
            commit(db, null, "c-legacy", "legacy-series", t0, t0.plusSeconds(60))
            assertEquals(1, db.reconciliationDao().projectionPage(256, 0).size)
            assertTrue(rows(db, keyA).isEmpty() && rows(db, keyB).isEmpty())
            assertNull(db.reconciliationDao().provenance("c-legacy"))
            assertNotNull(db.reconciliationDao().cycle("c-legacy"))
        } finally { db.close() }
    }
}

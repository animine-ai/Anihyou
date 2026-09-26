package com.axiel7.anihyou.release.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.repository.RoomReleaseDecisionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseEvidenceRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseReconciliationRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseReconciliationRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "v12-reconciliation-test.db"
    private val time = Instant.parse("2026-09-26T10:00:00Z")
    @After fun cleanup() { context.deleteDatabase(name) }

    private fun open() = Room.databaseBuilder(context, ReleaseDatabase::class.java, name)
        .allowMainThreadQueries().build()

    private fun evidence(label: String, source: ReleaseSourceType,
                         type: ReleaseEvidenceType, season: Int? = 2,
                         forecastAt: Instant? = time): ReleaseEvidence {
        val item = ReleaseEvidence(label, source, "https://aniworld.to/anime/stream/v12-test",
            "hash-$label", "fixture", time, forecastAt, false,
            AniWorldSiteIdentifier("v12-test"), season, 4, Installment.Episode(0),
            LanguageTrack.DE_SUB, type, ScheduleCondition.UNKNOWN,
            ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0))
        return item.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(item))
    }

    private fun cycle(id: String, at: Instant, item: ReleaseEvidence): CompletedObservationCycle =
        CompletedObservationCycle(id, "v12-test", at.minusSeconds(60), at,
            AbsencePolicySnapshot(), listOf(CycleSourceObservation(
                "$id:source", item.sourceType, CanonicalReleaseIdentity.from(item)?.key ?: "scope",
                item.languageTrack, CycleResult.SUCCESS, SourceHealthStatus.HEALTHY,
                observedAt = at, evidence = listOf(item))))

    @Test fun baselinePreservesLegacyDecisionAndReplaysCycleReceipts() = runBlocking {
        var db = open()
        val forecast = evidence("a", ReleaseSourceType.ANIWORLD_CALENDAR,
            ReleaseEvidenceType.FORECAST)
        val next = evidence("b", ReleaseSourceType.ANIWORLD_CALENDAR,
            ReleaseEvidenceType.FORECAST, forecastAt = time.plusSeconds(3600))
        val exactKey = CanonicalReleaseIdentity.from(forecast)!!.key
        try {
            assertTrue(RoomReleaseEvidenceRepository(db).append(forecast))
            val legacy = AniWorldReleaseAuthorityReducer().reduce(null, forecast)
            assertTrue(RoomReleaseDecisionRepository(db).put(legacy))
            val original = db.releaseDao().getReleaseDecision(forecast.identityKey)
            val repository = RoomReleaseReconciliationRepository(db)
            assertEquals(1, repository.importBaseline())
            assertEquals(0, repository.importBaseline())
            assertEquals(original, db.releaseDao().getReleaseDecision(forecast.identityKey))
            assertEquals(forecast.id, repository.get(exactKey)?.forecastEvidenceId)
            repository.persistCompletedCycle(cycle("one", time.plusSeconds(60), next))
            repository.persistCompletedCycle(cycle("two", time.plusSeconds(120), forecast))
            assertEquals(forecast.id, repository.get(exactKey)?.forecastEvidenceId)
            assertTrue(repository.history(exactKey, 10, 0).size >= 3)
            val revision = repository.get(exactKey)?.revision
            repository.persistCompletedCycle(cycle("two", time.plusSeconds(120), forecast))
            assertEquals(revision, repository.get(exactKey)?.revision)
            assertEquals(1, repository.rebuildProjections())
            db.close()
            db = open()
            assertEquals(revision, RoomReleaseReconciliationRepository(db).get(exactKey)?.revision)
            assertEquals(original, db.releaseDao().getReleaseDecision(forecast.identityKey))
        } finally { db.close() }
    }

    @Test fun alteredDuplicateCycleRollsBackWithoutNewEvidence() = runBlocking {
        val db = open()
        try {
            val repository = RoomReleaseReconciliationRepository(db)
            repository.importBaseline()
            val first = evidence("first", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION)
            val other = evidence("other", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION)
            repository.persistCompletedCycle(cycle("same", time, first))
            assertTrue(runCatching { repository.persistCompletedCycle(cycle("same", time, other)) }
                .isFailure)
            assertNull(db.releaseDao().getReleaseEvidence(other.id))
            assertEquals(1L, db.openHelper.writableDatabase.query(
                "SELECT count(*) FROM v3_observation_cycle").use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            })
        } finally { db.close() }
    }

    @Test fun legacyPartialReleasedIsQuarantinedWithoutExactAuthority() = runBlocking {
        val db = open()
        try {
            val item = evidence("partial", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, season = null)
            assertTrue(RoomReleaseEvidenceRepository(db).append(item))
            val old = AniWorldReleaseAuthorityReducer().reduce(null, item)
            // The v11 model permits old partial RELEASED rows; insert a historical
            // fixture directly while the current reducer correctly rejects new ones.
            db.releaseDao().upsertReleaseDecision(old.copy(
                phase = ReleasePhase.RELEASED, authority = ReleaseAuthority.ANIWORLD,
                authoritativeEvidenceIds = listOf(item.id)).toEntityOrNull()!!)
            val repository = RoomReleaseReconciliationRepository(db)
            repository.importBaseline()
            val state = repository.get("partial-v1:${item.id}")!!
            assertEquals(ReleaseAuthority.NONE, state.authority)
            assertEquals(ReleasePhase.CONFLICT, state.phase)
            assertEquals(ReleasePhase.RELEASED,
                db.releaseDao().getReleaseDecision(item.identityKey)?.toDomainOrNull()?.phase)
        } finally { db.close() }
    }
}

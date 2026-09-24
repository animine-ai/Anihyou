package com.axiel7.anihyou.release.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.core.api.SourceFailureKind
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import com.axiel7.anihyou.release.data.repository.RoomReleaseDecisionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseEvidenceRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseIntelligencePersistence
import com.axiel7.anihyou.release.data.repository.RoomSourceHealthRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseIntelligencePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "release-intelligence-persistence-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun calendarForecastEvidenceAndRevisionsAreAppendOnly() = runBlocking {
        val database = openDatabase()
        try {
            val first = evidence(
                id = "forecast-1",
                sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                evidenceType = ReleaseEvidenceType.FORECAST,
                observedAt = Instant.parse("2026-09-22T18:00:00Z"),
                sourceReportedAt = Instant.parse("2026-09-22T20:10:00Z"),
                approximate = true,
            )
            val second = first.copy(
                id = "forecast-2",
                observedAt = Instant.parse("2026-09-22T18:05:00Z"),
                sourceReportedAt = Instant.parse("2026-09-22T20:20:00Z"),
            )
            val repository = RoomReleaseEvidenceRepository(database)

            assertTrue(repository.append(first))
            assertTrue(repository.append(second))
            assertFalse(repository.append(first))
            assertEquals(2, repository.observeFor(first.identityKey).first().size)
            assertEquals(2, repository.observeForecastFor(first.identityKey).first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun releasedDecisionCannotRegressThroughRepository() = runBlocking {
        val database = openDatabase()
        try {
            val release = evidence(
                id = "release-1",
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
                observedAt = Instant.parse("2026-09-22T18:00:00Z"),
                sourceReportedAt = Instant.parse("2026-09-22T17:59:00Z"),
                approximate = false,
            )
            val released = AniWorldReleaseAuthorityReducer().reduce(null, release)
            assertEquals(ReleasePhase.RELEASED, released.phase)
            assertEquals(ReleaseAuthority.ANIWORLD, released.authority)

            val repository = RoomReleaseDecisionRepository(database)
            assertTrue(repository.put(released))
            val regressed = released.copy(
                phase = ReleasePhase.UNKNOWN,
                authority = ReleaseAuthority.NONE,
                authoritativeEvidenceIds = emptyList(),
                revision = released.revision + 1L,
            )
            assertFalse(repository.put(regressed))
            assertEquals(released, repository.get(released.identityKey))
        } finally {
            database.close()
        }
    }

    @Test
    fun failedSourceRetainsLastSuccessfulHealthAndDecisionsStayAtomic() = runBlocking {
        val database = openDatabase()
        try {
            val first = evidence(
                id = "atomic-1",
                sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                evidenceType = ReleaseEvidenceType.FORECAST,
                observedAt = Instant.parse("2026-09-22T18:00:00Z"),
                sourceReportedAt = Instant.parse("2026-09-22T20:10:00Z"),
                approximate = true,
            )
            val successAt = Instant.parse("2026-09-22T18:00:00Z")
            val persistence = RoomReleaseIntelligencePersistence(
                database = database,
                reducer = AniWorldReleaseAuthorityReducer(),
            )
            persistence.persist(
                listOf(
                    SourceResult.Success(
                        value = listOf(first),
                        sourceHealth = SourceHealth(
                            sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                            status = SourceHealthStatus.HEALTHY,
                            lastAttemptAt = successAt,
                            lastSuccessAt = successAt,
                            consecutiveFailures = 0,
                            parserVersion = "test",
                            sourceHash = "hash-1",
                        ),
                    ),
                ),
            )

            val failureAt = successAt.plusSeconds(60)
            val decisions = persistence.persist(
                listOf(
                    SourceResult.Failure(
                        kind = SourceFailureKind.NETWORK,
                        diagnostic = "offline",
                        sourceHealth = SourceHealth(
                            sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                            status = SourceHealthStatus.UNAVAILABLE,
                            lastAttemptAt = failureAt,
                            lastSuccessAt = null,
                            consecutiveFailures = 1,
                            parserVersion = "test",
                            sourceHash = null,
                            diagnostic = "offline",
                        ),
                    ),
                ),
            )
            assertTrue(decisions.isEmpty())
            val health = RoomSourceHealthRepository(database)
                .get(ReleaseSourceType.ANIWORLD_CALENDAR)
            assertEquals(successAt, health?.lastSuccessAt)
            assertEquals(failureAt, health?.lastAttemptAt)
            assertEquals(1, health?.consecutiveFailures)
            assertEquals(first.identityKey, RoomReleaseDecisionRepository(database)
                .get(first.identityKey)?.identityKey)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): ReleaseDatabase =
        Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

    private fun evidence(
        id: String,
        sourceType: ReleaseSourceType,
        evidenceType: ReleaseEvidenceType,
        observedAt: Instant,
        sourceReportedAt: Instant?,
        approximate: Boolean,
    ): ReleaseEvidence {
        val site = AniWorldSiteIdentifier(
            slug = "wp04a-series",
            sourceType = AniWorldIdentitySourceType.CALENDAR,
            firstSeenAt = observedAt,
            lastValidatedAt = observedAt,
        )
        return ReleaseEvidence(
            id = id,
            sourceType = sourceType,
            sourceUrl = "https://aniworld.to/anime/stream/wp04a-series",
            sourceHash = "hash-$id",
            parserVersion = "wp04a-test",
            observedAt = observedAt,
            sourceReportedAt = sourceReportedAt,
            approximateTime = approximate,
            siteIdentifier = site,
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = evidenceType,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(
                source = 1.0,
                identity = 1.0,
                installment = 1.0,
                languageTrack = 1.0,
                timing = 1.0,
            ),
        )
    }
}

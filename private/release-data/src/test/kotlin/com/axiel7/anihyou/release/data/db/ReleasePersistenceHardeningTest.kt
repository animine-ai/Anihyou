package com.axiel7.anihyou.release.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.SourceFailureKind
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseForecastRevision
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.repository.RoomReleaseDecisionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseEvidenceRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseIntelligencePersistence
import com.axiel7.anihyou.release.data.repository.RoomSourceHealthRepository
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleasePersistenceHardeningTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "release-persistence-hardening-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase("$databaseName-batch.db")
    }

    @Test
    fun forecastRevisionRequiresPersistedMatchingCalendarEvidence() = runBlocking {
        val database = openDatabase()
        try {
            val repository = RoomReleaseEvidenceRepository(database)
            val forecast = evidence(
                id = "forecast-evidence",
                sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                evidenceType = ReleaseEvidenceType.FORECAST,
                sourceReportedAt = Instant.parse("2026-09-22T20:10:00Z"),
            )
            val revision = ReleaseForecastRevision.fromEvidence(forecast)!!
            assertFalse(repository.append(revision))

            assertTrue(database.releaseDao().insertReleaseEvidence(forecast.toEntity()) > 0)
            assertTrue(repository.append(revision))
            assertTrue(repository.append(revision))

            assertFalse(
                repository.append(
                    revision.copy(forecastAt = revision.forecastAt.plusSeconds(60)),
                ),
            )
            assertFalse(
                repository.append(
                    revision.copy(identityKey = "different-identity"),
                ),
            )

            val confirmation = forecast.copy(
                id = "confirmation-evidence",
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
            )
            assertTrue(database.releaseDao().insertReleaseEvidence(confirmation.toEntity()) > 0)
            assertFalse(
                repository.append(
                    revision.copy(evidenceId = confirmation.id, id = confirmation.id),
                ),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun oversizeRecordDoesNotAbortValidIndependentSourceEvidence() = runBlocking {
        val database = openDatabase()
        try {
            val valid = evidence(
                id = "valid-calendar",
                sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                evidenceType = ReleaseEvidenceType.FORECAST,
                sourceReportedAt = Instant.parse("2026-09-22T20:10:00Z"),
            )
            val oversize = evidence(
                id = "oversize-recent",
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
                sourceReportedAt = Instant.parse("2026-09-22T20:09:00Z"),
            ).copy(sourceHash = "x".repeat(257))
            val persistence = RoomReleaseIntelligencePersistence(
                database = database,
                reducer = AniWorldReleaseAuthorityReducer(),
            )

            persistence.persist(
                listOf(
                    SourceResult.Success(value = listOf(valid)),
                    SourceResult.Success(value = listOf(oversize)),
                ),
            )

            val repository = RoomReleaseEvidenceRepository(database)
            assertNotNull(repository.findById(valid.id))
            assertEquals(null, repository.findById(oversize.id))
            assertNotNull(RoomReleaseDecisionRepository(database).get(valid.identityKey))
        } finally {
            database.close()
        }
    }

    @Test
    fun directAndBatchSourceHealthWritesUseTheSameMergePolicy() = runBlocking {
        val successAt = Instant.parse("2026-09-22T18:00:00Z")
        val failureAt = successAt.plusSeconds(60)
        val success = SourceHealth(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            status = SourceHealthStatus.HEALTHY,
            lastAttemptAt = successAt,
            lastSuccessAt = successAt,
            consecutiveFailures = 0,
            parserVersion = "parser-1",
            sourceHash = "hash-1",
        )
        val failure = SourceHealth(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            status = SourceHealthStatus.UNAVAILABLE,
            lastAttemptAt = failureAt,
            lastSuccessAt = null,
            consecutiveFailures = 1,
            parserVersion = null,
            sourceHash = null,
            diagnostic = "offline",
        )

        val directDatabase = openDatabase()
        val directHealth = try {
            val repository = RoomSourceHealthRepository(directDatabase)
            repository.put(success)
            repository.put(failure)
            repository.get(ReleaseSourceType.ANIWORLD_RECENT)
        } finally {
            directDatabase.close()
        }

        val batchDatabase = openDatabase("$databaseName-batch.db")
        val batchHealth = try {
            val persistence = RoomReleaseIntelligencePersistence(
                database = batchDatabase,
                reducer = AniWorldReleaseAuthorityReducer(),
            )
            persistence.persist(
                listOf(
                    SourceResult.Success(value = emptyList(), sourceHealth = success),
                    SourceResult.Failure(
                        kind = SourceFailureKind.NETWORK,
                        diagnostic = "offline",
                        sourceHealth = failure,
                    ),
                ),
            )
            RoomSourceHealthRepository(batchDatabase)
                .get(ReleaseSourceType.ANIWORLD_RECENT)
        } finally {
            batchDatabase.close()
        }

        assertEquals(directHealth, batchHealth)
    }

    @Test
    fun equalRevisionIsIdempotentAndDifferentContentIsRejectedByRoomRepository() =
        runBlocking {
            val database = openDatabase()
            try {
                val confirmation = evidence(
                    id = "equal-revision",
                    sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                    evidenceType = ReleaseEvidenceType.CONFIRMATION,
                    sourceReportedAt = Instant.parse("2026-09-22T18:00:00Z"),
                )
                val decision = AniWorldReleaseAuthorityReducer().reduce(null, confirmation)
                val repository = RoomReleaseDecisionRepository(database)
                assertTrue(repository.put(decision))
                assertTrue(repository.put(decision))
                assertFalse(
                    repository.put(
                        decision.copy(
                            releaseAt = decision.lastObservedAt?.plusSeconds(1),
                        ),
                    ),
                )
                assertEquals(decision, repository.get(decision.identityKey))
            } finally {
                database.close()
            }
        }

    private fun openDatabase(name: String = databaseName): ReleaseDatabase =
        Room.databaseBuilder(context, ReleaseDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun evidence(
        id: String,
        sourceType: ReleaseSourceType,
        evidenceType: ReleaseEvidenceType,
        sourceReportedAt: Instant,
    ): ReleaseEvidence {
        val observedAt = Instant.parse("2026-09-22T18:00:00Z")
        val site = AniWorldSiteIdentifier(
            slug = "wp04a-hardening",
            sourceType = AniWorldIdentitySourceType.CALENDAR,
            firstSeenAt = observedAt,
            lastValidatedAt = observedAt,
        )
        return ReleaseEvidence(
            id = id,
            sourceType = sourceType,
            sourceUrl = "https://aniworld.to/anime/stream/wp04a-hardening",
            sourceHash = "hash-$id",
            parserVersion = "wp04a-hardening",
            observedAt = observedAt,
            sourceReportedAt = sourceReportedAt,
            approximateTime = sourceType == ReleaseSourceType.ANIWORLD_CALENDAR,
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

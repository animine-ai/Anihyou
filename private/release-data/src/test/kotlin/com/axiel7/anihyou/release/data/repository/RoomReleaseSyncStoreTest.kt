package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_1_2
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_2_3
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_3_4
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_4_5
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_5_6
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_6_7
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
class RoomReleaseSyncStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "release-sync-generation-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun staleGenerationCannotOverwriteSnapshots() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomReleaseSyncStore(database)
            val snapshot = sampleSnapshot("first")
            assertTrue(store.persistSnapshotsIfCurrent("account", 0L, listOf(snapshot)))
            assertEquals(1L, store.advanceGeneration("account"))
            assertFalse(store.persistSnapshotsIfCurrent("account", 0L, listOf(sampleSnapshot("stale"))))
            assertEquals("first", database.releaseDao()
                .getProviderSnapshot(snapshot.stream.stableKey)?.sourceHash)
            assertTrue(store.persistSnapshotsIfCurrent("account", 1L, listOf(sampleSnapshot("second"))))
            assertEquals("second", database.releaseDao()
                .getProviderSnapshot(snapshot.stream.stableKey)?.sourceHash)
        } finally {
            database.close()
        }
    }

    @Test
    fun generationsAreIndependentAndMonotonic() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomReleaseSyncStore(database)
            assertEquals(0L, store.currentGeneration("one"))
            assertEquals(1L, store.advanceGeneration("one"))
            assertEquals(2L, store.advanceGeneration("one"))
            assertEquals(1L, store.advanceGeneration("two"))
            assertEquals(2L, store.currentGeneration("one"))
            assertEquals(1L, store.currentGeneration("two"))
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): ReleaseDatabase = Room.databaseBuilder(
        context,
        ReleaseDatabase::class.java,
        databaseName,
    ).addMigrations(RELEASE_MIGRATION_1_2, RELEASE_MIGRATION_2_3, RELEASE_MIGRATION_3_4, RELEASE_MIGRATION_4_5, RELEASE_MIGRATION_5_6, RELEASE_MIGRATION_6_7)
        .allowMainThreadQueries()
        .build()



    @Test
    fun emptyForecastStreamCancelsExistingRecheckWork() = runBlocking {
        val database = openDatabase()
        try {
            val observedAt = OBSERVED_AT
            val stream = sampleSnapshot("forecast").stream
            val forecast = Forecast(
                identity = SourceIdentity(stream, Installment.Episode(10)),
                forecastAt = observedAt.plusSeconds(5 * 86_400L),
                sourceDate = LocalDate.of(2026, 9, 16),
                sourceTime = "20:15",
                sourceZone = ZoneOffset.UTC,
                approximate = false,
                observedAt = observedAt,
            )
            val first = sampleSnapshot("forecast").copy(
                stream = stream,
                forecasts = listOf(forecast),
                observedAt = observedAt,
            )
            val store = RoomReleaseSyncStore(database)
            assertTrue(
                store.persistSnapshotsIfCurrent(
                    generationKey = "forecast-empty",
                    expectedGeneration = 0L,
                    snapshots = listOf(first),
                    observedAt = observedAt,
                ),
            )
            assertEquals(1, database.releaseDao().getForecastRecheckWork(stream.stableKey).size)

            val removed = first.copy(
                forecasts = emptyList(),
                observedAt = observedAt.plusSeconds(1),
            )
            assertTrue(
                store.persistSnapshotsIfCurrent(
                    generationKey = "forecast-empty",
                    expectedGeneration = 0L,
                    snapshots = listOf(removed),
                    observedAt = removed.observedAt,
                ),
            )
            assertTrue(database.releaseDao().getForecastRecheckWork(stream.stableKey).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun currentReconciliationWritesProjectionAndNotificationTogether() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomReleaseSyncStore(database)
            val item = candidate(7)
            val projection = MediaReleaseProjection(
                mediaId = item.mediaId,
                stream = item.identity.stream,
                authority = AuthorityStatus.VALID,
                confirmedThroughEpisode = 7,
                confirmedInstallments = listOf(Installment.Episode(7)),
                nextForecast = null,
                pendingCount = 7,
                freshness = Freshness(
                    status = FreshnessStatus.FRESH,
                    lastAttemptAt = OBSERVED_AT,
                    lastSuccessAt = OBSERVED_AT,
                    observedAt = OBSERVED_AT,
                    parserVersion = "wp06-test",
                    sourceHash = "hash",
                ),
                mapping = null,
                sourceRoot = "https://aniworld.to",
                revision = 1L,
            )

            assertTrue(
                store.persistReconciliationIfCurrent(
                    generationKey = "account-7",
                    expectedGeneration = 0L,
                    accountId = 7L,
                    snapshots = emptyList(),
                    mediaProjections = listOf(projection),
                    notificationCandidates = listOf(item),
                    observedAt = OBSERVED_AT,
                ),
            )
            assertEquals(1, database.releaseDao().mediaProjectionCount())
            assertEquals(1, database.releaseDao().notificationOutboxCount())
        } finally {
            database.close()
        }
    }

    @Test
    fun staleReconciliationWritesNeitherProjectionNorNotification() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomReleaseSyncStore(database)
            assertEquals(1L, store.advanceGeneration("account-7"))
            assertFalse(
                store.persistReconciliationIfCurrent(
                    generationKey = "account-7",
                    expectedGeneration = 0L,
                    accountId = 7L,
                    snapshots = emptyList(),
                    mediaProjections = listOf(
                        MediaReleaseProjection(
                            mediaId = 42,
                            stream = sampleSnapshot("stale").stream,
                            authority = AuthorityStatus.VALID,
                            confirmedThroughEpisode = 1,
                            confirmedInstallments = listOf(Installment.Episode(1)),
                            nextForecast = null,
                            pendingCount = 0,
                            freshness = sampleSnapshot("stale").freshness,
                            mapping = null,
                            sourceRoot = "https://aniworld.to",
                            revision = 1L,
                        ),
                    ),
                    notificationCandidates = listOf(candidate(1)),
                    observedAt = OBSERVED_AT,
                ),
            )
            assertEquals(0, database.releaseDao().mediaProjectionCount())
            assertEquals(0, database.releaseDao().notificationOutboxCount())
        } finally {
            database.close()
        }
    }

    private fun candidate(episode: Int): ReleaseNotificationCandidate {
        val stream = sampleSnapshot("candidate").stream
        return ReleaseNotificationCandidate(
            accountId = 7L,
            mediaId = 42,
            identity = SourceIdentity(stream, Installment.Episode(episode)),
            evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
            observedAt = OBSERVED_AT,
        )
    }

    private fun sampleSnapshot(hash: String) = ReleaseSnapshot(
        stream = ReleaseStreamKey(
            providerId = ProviderId("provider"),
            stableSeriesKey = SourceSeriesKey("series"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        ),
        confirmations = emptyList(),
        forecasts = emptyList(),
        freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = OBSERVED_AT,
            lastSuccessAt = OBSERVED_AT,
            observedAt = OBSERVED_AT,
            parserVersion = "wp05-test",
            sourceHash = hash,
        ),
        mapping = null,
        observedAt = OBSERVED_AT,
    )

    private companion object {
        val OBSERVED_AT: Instant = Instant.parse("2026-09-11T12:00:00Z")
    }
}

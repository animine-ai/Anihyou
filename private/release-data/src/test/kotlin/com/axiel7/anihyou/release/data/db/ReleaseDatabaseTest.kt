package com.axiel7.anihyou.release.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.api.RefreshOutcome
import com.axiel7.anihyou.release.core.api.RefreshReason
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.data.repository.RoomReleaseProjectionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseSyncStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "release-persistence-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun currentDatabasePreservesR2SnapshotAndReopens() = runBlocking {
        val database = openDatabase()
        val snapshot = sampleSnapshot()
        try {
            database.releaseDao().upsertProviderSnapshots(listOf(snapshot.toEntity()))
        } finally {
            database.close()
        }

        val reopened = openDatabase()
        try {
            assertEquals(snapshot, reopened.releaseDao().getProviderSnapshot(snapshot.stream.stableKey)?.toDomainOrNull())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun forecastRecheckWorkIsReplacedWithoutCreatingNotificationWork() = runBlocking {
        val database = openDatabase()
        try {
            val stream = sampleStream("recheck")
            val firstObservedAt = Instant.parse("2026-09-11T12:00:00Z")
            val first = sampleSnapshot().copy(
                stream = stream,
                forecasts = listOf(
                    Forecast(
                        identity = SourceIdentity(stream, Installment.Episode(10)),
                        forecastAt = firstObservedAt.plusSeconds(5 * 86_400L),
                        sourceDate = LocalDate.of(2026, 9, 16),
                        sourceTime = "20:15",
                        sourceZone = ZoneOffset.UTC,
                        approximate = false,
                        observedAt = firstObservedAt,
                    ),
                ),
                observedAt = firstObservedAt,
            )
            val store = RoomReleaseSyncStore(database)
            assertTrue(
                store.persistSnapshotsIfCurrent(
                    generationKey = "recheck",
                    expectedGeneration = 0L,
                    snapshots = listOf(first),
                    observedAt = firstObservedAt,
                ),
            )
            val initialWork = database.releaseDao().getForecastRecheckWork(stream.stableKey).single()
            assertEquals(first.forecasts.single().forecastAt.toString(), initialWork.forecastAt)
            assertEquals(0, database.releaseDao().notificationOutboxCount())

            val movedObservedAt = firstObservedAt.plusSeconds(1)
            val moved = first.copy(
                forecasts = listOf(
                    first.forecasts.single().copy(
                        forecastAt = firstObservedAt.plusSeconds(6 * 86_400L),
                        observedAt = movedObservedAt,
                    ),
                ),
                observedAt = movedObservedAt,
            )
            assertTrue(
                store.persistSnapshotsIfCurrent(
                    generationKey = "recheck",
                    expectedGeneration = 0L,
                    snapshots = listOf(moved),
                    observedAt = movedObservedAt,
                ),
            )
            val movedWork = database.releaseDao().getForecastRecheckWork(stream.stableKey).single()
            assertNotEquals(initialWork.forecastAt, movedWork.forecastAt)
            assertEquals(moved.forecasts.single().forecastAt.toString(), movedWork.forecastAt)
            assertEquals(0, database.releaseDao().notificationOutboxCount())
        } finally {
            database.close()
        }
    }

    @Test
    fun compositeAccountAndStreamKeysRemainIndependent() = runBlocking {
        val database = openDatabase()
        try {
            val repository = RoomReleaseProjectionRepository(database)
            repository.replaceLocalProjections(
                accountId = 42L,
                mediaRows = listOf(
                    sampleProjection(mediaId = 101, series = "alpha"),
                    sampleProjection(mediaId = 202, series = "beta"),
                ),
                calendarRows = emptyList(),
            )

            val rows = repository.observeForMedia(42L, setOf(101, 202)).first()
            assertEquals(setOf(101, 202), rows.keys)
            assertEquals("alpha", rows.getValue(101).single().stream.stableSeriesKey.value)
            assertEquals("beta", rows.getValue(202).single().stream.stableSeriesKey.value)
            assertEquals(2, database.releaseDao().mediaProjectionCount())
        } finally {
            database.close()
        }
    }

    @Test
    fun corruptProjectionRowsAreIsolatedFromValidRows() = runBlocking {
        val database = openDatabase()
        try {
            val valid = sampleProjection(mediaId = 303, series = "valid").toEntity(7L)
            val corrupt = valid.copy(
                projectionKey = "7::corrupt",
                mediaId = 404,
                streamPayload = "7:broken",
            )
            database.releaseDao().upsertMediaProjections(listOf(valid, corrupt))

            val rows = RoomReleaseProjectionRepository(database)
                .observeForMedia(7L, setOf(303, 404))
                .first()

            assertEquals(setOf(303), rows.keys)
            assertEquals("valid", rows.getValue(303).single().stream.stableSeriesKey.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun refreshPlaceholderIsExplicitlySkippedWithoutNetworkAccess() = runBlocking {
        val database = openDatabase()
        try {
            val outcome = RoomReleaseProjectionRepository(database).refresh(RefreshReason.MANUAL)
            assertTrue(outcome is RefreshOutcome.Skipped)
        } finally {
            database.close()
        }
    }

    @Test
    fun mediaProjectionQueryUsesAccountMediaIndex() = runBlocking {
        val database = openDatabase()
        try {
            database.releaseDao().upsertMediaProjections(
                listOf(sampleProjection(505, "indexed").toEntity(17L)),
            )
            val cursor = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT projectionKey FROM media_release_projection " +
                    "INDEXED BY idx_media_projection_account_media " +
                    "WHERE accountId = 17 AND mediaId = 505",
            )
            val details = buildString {
                cursor.use {
                    val detailColumn = it.getColumnIndexOrThrow("detail")
                    while (it.moveToNext()) append(it.getString(detailColumn))
                }
            }
            assertTrue(details.contains("idx_media_projection_account_media"))
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingAndCountdownQueriesUseDedicatedIndexes() = runBlocking {
        val database = openDatabase()
        try {
            database.releaseDao().upsertMediaProjections(
                listOf(sampleProjection(606, "indexed-shapes").toEntity(17L)),
            )
            val pendingCursor = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT projectionKey FROM media_release_projection " +
                    "INDEXED BY idx_media_projection_account_authority_pending " +
                    "WHERE accountId = 17 AND authority = 'VALID' AND pendingCount > 0",
            )
            val pendingDetails = buildString {
                pendingCursor.use {
                    val detailColumn = it.getColumnIndexOrThrow("detail")
                    while (it.moveToNext()) append(it.getString(detailColumn))
                }
            }
            assertTrue(pendingDetails.contains("idx_media_projection_account_authority_pending"))

            val countdownCursor = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT projectionKey FROM media_release_projection " +
                    "INDEXED BY idx_media_projection_account_forecast " +
                    "WHERE accountId = 17 AND nextForecastAt >= '2026-09-11T12:00:00Z'",
            )
            val countdownDetails = buildString {
                countdownCursor.use {
                    val detailColumn = it.getColumnIndexOrThrow("detail")
                    while (it.moveToNext()) append(it.getString(detailColumn))
                }
            }
            assertTrue(countdownDetails.contains("idx_media_projection_account_forecast"))
        } finally {
            database.close()
        }
    }

    private fun openDatabase(withMigration: Boolean = false): ReleaseDatabase =
        Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName)
            .apply {
                if (withMigration) addMigrations(RELEASE_MIGRATION_1_2, RELEASE_MIGRATION_2_3, RELEASE_MIGRATION_3_4, RELEASE_MIGRATION_4_5, RELEASE_MIGRATION_5_6, RELEASE_MIGRATION_6_7, RELEASE_MIGRATION_7_8, RELEASE_MIGRATION_8_9)
            }
            .allowMainThreadQueries()
            .build()

    private fun sampleStream(series: String): ReleaseStreamKey = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey(series),
        releaseKind = ReleaseKind.EPISODE,
        sourceSeason = 1,
        languageTrack = LanguageTrack.DE_DUB,
    )

    private fun sampleSnapshot(): ReleaseSnapshot {
        val observedAt = Instant.parse("2026-09-11T12:00:00Z")
        return ReleaseSnapshot(
            stream = sampleStream("snapshot"),
            confirmations = emptyList(),
            forecasts = emptyList(),
            freshness = Freshness(
                status = FreshnessStatus.FRESH,
                lastAttemptAt = observedAt,
                lastSuccessAt = observedAt,
                observedAt = observedAt,
                parserVersion = "wp03-test",
                sourceHash = "hash",
            ),
            mapping = null,
            sourceRoot = "https://aniworld.to",
            observedAt = observedAt,
        )
    }

    private fun sampleProjection(mediaId: Int, series: String, revision: Long = 1L) =
        MediaReleaseProjection(
            mediaId = mediaId,
            stream = sampleStream(series),
            authority = AuthorityStatus.VALID,
            confirmedThroughEpisode = 1,
            confirmedInstallments = listOf(Installment.Episode(1)),
            nextForecast = null,
            pendingCount = 0,
            freshness = Freshness(
                status = FreshnessStatus.FRESH,
                lastAttemptAt = Instant.parse("2026-09-11T12:00:00Z"),
                lastSuccessAt = Instant.parse("2026-09-11T12:00:00Z"),
                observedAt = Instant.parse("2026-09-11T12:00:00Z"),
                parserVersion = "wp03-test",
                sourceHash = "hash",
            ),
            mapping = null,
            sourceRoot = "https://aniworld.to",
            revision = revision,
            diagnostics = listOf("local"),
        )
}

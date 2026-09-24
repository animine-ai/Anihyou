package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.api.TargetedLookupCursor
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.data.db.IdentityCandidateEntity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_1_2
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_2_3
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_3_4
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_4_5
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_5_6
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_6_7
import com.axiel7.anihyou.release.data.db.lookupCacheKey
import com.axiel7.anihyou.release.data.repository.LookupCacheDisposition
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IdentityCandidateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "identity-candidate-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun candidatesRoundTripWithExpiryAndMalformedRowsAreIsolated() = runBlocking {
        val database = openDatabase()
        try {
            val source = source()
            val fetchedAt = Instant.parse("2026-09-11T12:00:00Z")
            val expiresAt = fetchedAt.plusSeconds(30 * DAY_SECONDS)
            val store = RoomIdentityCandidateStore(database)
            store.replaceCandidates(
                source = source,
                candidates = listOf(candidate(11, "A title"), candidate(22, "Another title")),
                fetchedAt = fetchedAt,
                expiresAt = expiresAt,
            )

            val validRows = store.observeCandidates(source, fetchedAt.plusSeconds(DAY_SECONDS)).first()
            assertEquals(listOf(11, 22), validRows.map { it.mediaId })

            database.releaseDao().upsertIdentityCandidates(
                listOf(
                    IdentityCandidateEntity(
                        sourceKey = source.stableKey,
                        mediaId = 33,
                        titlesPayload = "4:bad",
                        format = "TV",
                        startDate = null,
                        fetchedAt = fetchedAt.toString(),
                        expiresAt = expiresAt.toString(),
                    ),
                ),
            )
            assertEquals(listOf(11, 22), store.observeCandidates(source, fetchedAt).first().map { it.mediaId })
            assertEquals(0, store.observeCandidates(source, expiresAt).first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun lookupCachePreservesCursorAndExpiresAtBoundary() = runBlocking {
        val database = openDatabase()
        try {
            val source = source()
            val fetchedAt = Instant.parse("2026-09-11T12:00:00Z")
            val query = TargetedIdentityQuery(
                source = source,
                query = "A title",
                formatIn = setOf("TV"),
                signature = "sig-1",
            )
            RoomIdentityCandidateStore(database).saveLookup(
                source = source,
                query = query,
                batch = CandidateBatch(
                    candidates = listOf(candidate(44, "A title")),
                    complete = false,
                    nextCursor = "cursor-2",
                    pagesFetched = 2,
                ),
                fetchedAt = fetchedAt,
                disposition = LookupCacheDisposition.POSITIVE,
            )

            val cached = RoomIdentityCandidateStore(database).readLookup(source, "sig-1", fetchedAt)
            assertEquals("cursor-2", cached?.nextCursor)
            assertEquals(2, cached?.pagesFetched)
            assertEquals(44, cached?.candidates?.single()?.mediaId)
            assertTrue(
                RoomIdentityCandidateStore(database).readLookup(
                    source,
                    "sig-1",
                    fetchedAt.plusSeconds(DAY_SECONDS),
                ) != null,
            )
            assertNull(
                RoomIdentityCandidateStore(database).readLookup(
                    source,
                    "sig-1",
                    fetchedAt.plusSeconds(30 * DAY_SECONDS),
                ),
            )
            val negativeQuery = query.copy(signature = "sig-negative")
            RoomIdentityCandidateStore(database).saveLookup(
                source = source,
                query = negativeQuery,
                batch = CandidateBatch(candidates = emptyList(), complete = false),
                fetchedAt = fetchedAt,
                disposition = LookupCacheDisposition.NEGATIVE_OR_AMBIGUOUS,
            )
            assertEquals(
                false,
                RoomIdentityCandidateStore(database).readLookup(
                    source,
                    "sig-negative",
                    fetchedAt.plusSeconds(DAY_SECONDS / 2),
                )?.complete,
            )
            assertNull(
                RoomIdentityCandidateStore(database).readLookup(
                    source,
                    "sig-negative",
                    fetchedAt.plusSeconds(DAY_SECONDS),
                ),
            )
            assertEquals(
                source.stableKey + "::sig-1",
                lookupCacheKey(source, "sig-1"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun seasonCoverageAndTargetedCursorMetadataAreDurableAndGenerationSafe() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomIdentityCandidateStore(database)
            val fetchedAt = Instant.parse("2026-09-11T12:00:00Z")
            val expiresAt = fetchedAt.plusSeconds(7 * DAY_SECONDS)
            store.writeSeasonPoolMetadata(
                poolKey = "season-pool:fall:2026",
                metadata = SeasonPoolCacheMetadata(
                    complete = false,
                    nextCursor = "3",
                    fetchedAt = fetchedAt,
                    expiresAt = expiresAt,
                    pagesFetched = 2,
                ),
            )
            val partial = store.readSeasonPoolMetadata(
                "season-pool:fall:2026",
                fetchedAt.plusSeconds(DAY_SECONDS),
            )
            assertEquals(false, partial?.complete)
            assertEquals("3", partial?.nextCursor)
            assertEquals(2, partial?.pagesFetched)
            assertNull(
                store.readSeasonPoolMetadata(
                    "season-pool:fall:2026",
                    expiresAt,
                ),
            )

            store.writeTargetedLookupCursor(
                "aniworld",
                TargetedLookupCursor(generation = 7L, offset = 4),
            )
            store.writeTargetedLookupCursor(
                "aniworld",
                TargetedLookupCursor(generation = 6L, offset = 1),
            )
            assertEquals(
                TargetedLookupCursor(generation = 7L, offset = 4),
                store.readTargetedLookupCursor("aniworld"),
            )
            store.writeTargetedLookupCursor(
                "aniworld",
                TargetedLookupCursor(generation = 8L, offset = 0),
            )
            assertEquals(
                TargetedLookupCursor(generation = 8L, offset = 0),
                store.readTargetedLookupCursor("aniworld"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun candidateAndCacheQueriesUseExpiryIndexes() = runBlocking {
        val database = openDatabase()
        try {
            val candidateCursor = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT mediaId FROM identity_candidate " +
                    "INDEXED BY idx_identity_candidate_source_expiry " +
                    "WHERE sourceKey = 'source' AND expiresAt > '2026-09-11T00:00:00Z'",
            )
            val candidateDetails = buildString {
                candidateCursor.use {
                    val detailColumn = it.getColumnIndexOrThrow("detail")
                    while (it.moveToNext()) append(it.getString(detailColumn))
                }
            }
            assertTrue(candidateDetails.contains("idx_identity_candidate_source_expiry"))

            val cacheCursor = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT cacheKey FROM lookup_cache " +
                    "INDEXED BY idx_lookup_cache_source_expiry " +
                    "WHERE sourceKey = 'source' AND expiresAt > '2026-09-11T00:00:00Z'",
            )
            val cacheDetails = buildString {
                cacheCursor.use {
                    val detailColumn = it.getColumnIndexOrThrow("detail")
                    while (it.moveToNext()) append(it.getString(detailColumn))
                }
            }
            assertTrue(cacheDetails.contains("idx_lookup_cache_source_expiry"))
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

    private fun source() = SourceIdentity(
        stream = ReleaseStreamKey(
            providerId = ProviderId("aniworld"),
            stableSeriesKey = SourceSeriesKey("series"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        ),
        installment = Installment.Episode(1),
    )

    private fun candidate(mediaId: Int, title: String) = IdentityCandidate(
        mediaId = mediaId,
        titles = setOf(title),
        format = "TV",
        startDate = LocalDate.of(2026, 1, 1),
    )

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}

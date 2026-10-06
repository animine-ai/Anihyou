package com.axiel7.anihyou.release.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingAttempt
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingAttemptResultKind
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.data.repository.RoomExternalMappingRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalMappingRoomTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "v3-external-mapping-test.db"
    private val now = Instant.parse("2026-09-22T12:00:00Z")

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun subjectsAreTheDurableIsolationKeyAndSurviveReopen() = runBlocking {
        val site = AniWorldSiteIdentifier(
            slug = "alpha",
            normalizedTitle = "alpha",
            sourceType = AniWorldIdentitySourceType.PAGE,
            firstSeenAt = now,
            lastValidatedAt = now,
        )
        val seasonOne = AniWorldMappingSubject.Season(site, 1)
        val seasonTwo = AniWorldMappingSubject.Season(site, 2)
        val filmOne = AniWorldMappingSubject.Film(site, 1)
        val database = openDatabase()
        try {
            val repository = RoomExternalMappingRepository(database)
            assertTrue(repository.put(mapping(seasonOne, "season-1")))
            assertTrue(repository.put(mapping(seasonTwo, "season-2")))
            assertTrue(repository.put(mapping(filmOne, "film-1")))

            assertEquals("season-1", repository.find(seasonOne, ExternalProvider.MAL)?.externalId)
            assertEquals("season-2", repository.find(seasonTwo, ExternalProvider.MAL)?.externalId)
            assertEquals("film-1", repository.find(filmOne, ExternalProvider.MAL)?.externalId)
            assertNull(repository.find(AniWorldMappingSubject.Film(site, 2), ExternalProvider.MAL))
        } finally {
            database.close()
        }

        val reopened = openDatabase()
        try {
            val repository = RoomExternalMappingRepository(reopened)
            assertEquals("season-1", repository.find(seasonOne, ExternalProvider.MAL)?.externalId)
            assertEquals("season-2", repository.find(seasonTwo, ExternalProvider.MAL)?.externalId)
            assertEquals("film-1", repository.find(filmOne, ExternalProvider.MAL)?.externalId)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun attemptsAtTheSameInstantRemainAppendOnlyAndLatestIsDeterministic() = runBlocking {
        val site = AniWorldSiteIdentifier(slug = "attempts", sourceType = AniWorldIdentitySourceType.PAGE)
        val subject = AniWorldMappingSubject.Season(site, 1)
        val database = openDatabase()
        try {
            val repository = RoomExternalMappingRepository(database)
            val first = ExternalMappingAttempt(
                subject = subject,
                externalProvider = ExternalProvider.MAL,
                source = MappingSource.MALSYNC,
                attemptedAt = now,
                resultKind = MappingAttemptResultKind.AMBIGUOUS,
                diagnostic = "first",
            )
            val second = first.copy(
                resultKind = MappingAttemptResultKind.MALFORMED_RESPONSE,
                diagnostic = "second",
            )

            assertTrue(repository.append(first))
            assertTrue(repository.append(second))
            assertEquals(2, database.releaseDao().countMappingAttempts(subject.stableKey, ExternalProvider.MAL.value))
            assertEquals(MappingAttemptResultKind.MALFORMED_RESPONSE, repository.latest(subject, ExternalProvider.MAL)?.resultKind)
        } finally {
            database.close()
        }
    }

    @Test
    fun manualMappingCannotBeReplacedByMALSync() = runBlocking {
        val site = AniWorldSiteIdentifier(slug = "manual", sourceType = AniWorldIdentitySourceType.PAGE)
        val subject = AniWorldMappingSubject.Season(site, 1)
        val database = openDatabase()
        try {
            val repository = RoomExternalMappingRepository(database)
            assertTrue(repository.put(mapping(subject, "manual", MappingSource.MANUAL)))
            assertFalse(repository.put(mapping(subject, "malsync", MappingSource.MALSYNC)))
            assertEquals("manual", repository.find(subject, ExternalProvider.MAL)?.externalId)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): ReleaseDatabase =
        Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()

    private fun mapping(
        subject: AniWorldMappingSubject,
        id: String,
        source: MappingSource = MappingSource.PERSISTED,
    ) = ExternalMapping(
        subject = subject,
        externalProvider = ExternalProvider.MAL,
        externalId = id,
        mappingSource = source,
        confidence = MappingConfidence.EXACT,
        createdAt = now,
        validatedAt = now,
        status = MappingStatus.ACTIVE,
    )
}

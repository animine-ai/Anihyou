package com.axiel7.anihyou.release.data.extension

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.*
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileExtensionPostponementStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: ReleaseDatabase
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val key = ExtensionSelectionKey("source", "de.aniworld", "publisher", "aniworld")

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ReleaseDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { database.close() }

    @Test fun restartKeepsNoticesButRevalidatesTheCurrentMapping() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileExtensionPostponementStore(directory, database)
        val mapping = mapping()
        database.releaseDao().upsertExternalMapping(mapping)
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation()))
        assertEquals(7, store.presentation.first().notices.single().mediaId)
        val restarted = FileExtensionPostponementStore(directory, database)
        database.releaseDao().upsertExternalMapping(mapping.copy(mappingStatus = "REVOKED"))
        assertNull(restarted.presentation.first().notices.single().mediaId)
        assertEquals("Example", restarted.snapshot.value.notices.single().title)
    }

    @Test fun invalidatedMappingRemovesALinkFromAnAlreadyObservedScreen() = runBlocking {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        val mapping = mapping()
        database.releaseDao().upsertExternalMapping(mapping)
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation()))
        assertEquals(7, store.presentation.first().notices.single().mediaId)
        database.releaseDao().upsertExternalMapping(mapping.copy(staleAt = now.toString()))
        assertNull(withTimeout(5_000) { store.presentation.first { it.notices.single().mediaId == null } }
            .notices.single().mediaId)
    }

    @Test fun noMappingAuthorityCrossesProvidersOrUnvalidatedRows() = runBlocking {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        database.releaseDao().upsertExternalMapping(mapping().copy(validatedAt = null))
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation()))
        assertNull(store.presentation.first().notices.single().mediaId)
        database.releaseDao().upsertExternalMapping(mapping())
        store.record(key.copy(providerId = "other"), 1, "a".repeat(64), 1, now, listOf(observation()))
        assertNull(store.presentation.first().notices.single().mediaId)
    }

    @Test fun updateAndPartialPagesRetainHistoryWhileCorrectionsKeepTheSameRow() = runBlocking {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation()))
        val originalKey = store.snapshot.value.notices.single().presentationKey
        store.record(key, 1, "b".repeat(64), 2, now.plusSeconds(60), emptyList())
        assertEquals(1, store.snapshot.value.notices.size)
        store.record(key, 1, "b".repeat(64), 2, now.plusSeconds(120),
            listOf(observation().copy(scheduleMarker = ObservationScheduleMarker.RESCHEDULED, sourceRawText = "new date")))
        assertEquals(originalKey, store.snapshot.value.notices.single().presentationKey)
        assertEquals(ObservationScheduleMarker.RESCHEDULED, store.snapshot.value.notices.single().marker)
        store.record(key.copy(sourceId = "another"), 2, "b".repeat(64), 3, now.plusSeconds(180), emptyList())
        assertTrue(store.snapshot.value.notices.isEmpty())
    }

    private fun mapping() = ExternalMapping(
        AniWorldMappingSubject.Season(AniWorldSiteIdentifier("example-series"), 2),
        ExternalProvider.ANILIST, "7", MappingSource.MANUAL, MappingConfidence.EXACT,
        now, now, MappingStatus.ACTIVE,
    ).toEntity()

    private fun observation() = ProviderObservationV1(
        1, ExtensionId.parse("de.aniworld"), ProviderId.parse("aniworld"), "postponements",
        SourceRole.POSTPONEMENT, "example-series", "Example", 2, 2,
        InstallmentV1(ObservationInstallmentKind.EPISODE, "12"), ObservationTrack.DE_SUB,
        ObservationClaimKind.CORRECTION, null, null, null, now.toString(),
        false, ObservationScheduleMarker.POSTPONED, "new date", "https://aniworld.to/verschiebungen",
        "d".repeat(64), emptyList(),
    )
}

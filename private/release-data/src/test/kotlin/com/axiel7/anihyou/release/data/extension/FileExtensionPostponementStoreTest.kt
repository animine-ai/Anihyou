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
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.serialization.json.*
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
        val awaitingInvalidation = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { store.presentation.first { it.notices.single().mediaId == null } }
        }
        database.releaseDao().upsertExternalMapping(mapping.copy(staleAt = now.toString()))
        assertNull(awaitingInvalidation.await()
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

    @Test fun filmRequiresItsOwnMappingAndCannotBorrowASeasonBinding() = runBlocking {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        database.releaseDao().upsertExternalMapping(mapping())
        val episode = observation()
        val film = episode.copy(installment = InstallmentV1(ObservationInstallmentKind.FILM, "12"))
        store.record(key, 1, "a".repeat(64), 1, now, listOf(episode, film))
        val first = store.presentation.first().notices
        assertEquals(2, first.map { it.presentationKey }.toSet().size)
        assertEquals(7, first.single { it.installmentKind == ObservationInstallmentKind.EPISODE }.mediaId)
        assertNull(first.single { it.installmentKind == ObservationInstallmentKind.FILM }.mediaId)
        database.releaseDao().upsertExternalMapping(ExternalMapping(
            AniWorldMappingSubject.Film(AniWorldSiteIdentifier("example-series"), 12),
            ExternalProvider.ANILIST, "8", MappingSource.MANUAL, MappingConfidence.EXACT,
            now, now, MappingStatus.ACTIVE,
        ).toEntity())
        assertEquals(8, store.presentation.first().notices.single {
            it.installmentKind == ObservationInstallmentKind.FILM
        }.mediaId)
    }

    @Test fun legacyNoticeRemainsVisibleButCannotInferAnInstallmentKindForMapping() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileExtensionPostponementStore(directory, database)
        database.releaseDao().upsertExternalMapping(mapping())
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation()))
        val file = java.io.File(directory, "postponements-v1.json")
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(JsonObject(root + mapOf(
            "schemaVersion" to JsonPrimitive(1),
            "notices" to JsonArray(root.getValue("notices").jsonArray.map { JsonObject(it.jsonObject - "installmentKind") }),
        )).toString())
        val reopened = FileExtensionPostponementStore(directory, database)
        assertEquals("Example", reopened.snapshot.value.notices.single().title)
        assertEquals(ObservationInstallmentKind.UNKNOWN, reopened.snapshot.value.notices.single().installmentKind)
        assertNull(reopened.presentation.first().notices.single().mediaId)
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

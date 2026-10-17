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

    /** A notice of the real page carries a short title and no series key; the series the source listed elsewhere gives the media. */
    @Test fun aShortTitleOfThePageFindsTheSeriesWithTheLongTitleThatHasABindingForThatSeason() = runBlocking {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        val subject = AniWorldMappingSubject.Season(AniWorldSiteIdentifier("rezero-starting-life"), 2)
        val e = ExternalMapping(subject, ExternalProvider.ANILIST, "189046", MappingSource.PERSISTED,
            MappingConfidence.EXACT, now, now, MappingStatus.ACTIVE).toEntity()
        database.matchingDao().upsertSourceMapping(SourceMappingEntity(key.sourceId, key.extensionId, key.publisherId,
            key.providerId, e.mappingSubjectKey, e.externalProvider, e.seriesStableKey, e.siteSlug, e.subjectType,
            e.navigationSeason, e.filmNumber, e.externalId, e.mappingSource, e.mappingStatus, e.confidence, e.createdAt,
            e.validatedAt, e.staleAt, e.provenance, e.parserVersion, 1L, now.toString()))
        database.matchingDao().upsertLabel(SourceSeriesLabelEntity(key.sourceId, key.extensionId, key.publisherId,
            key.providerId, "rezero-starting-life", "Re:ZERO - Starting Life in Another World",
            com.axiel7.anihyou.release.core.matching.SearchTitleFolding.fold("Re:ZERO - Starting Life in Another World"),
            "", now.toString(), now.toString()))
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation().copy(providerSeriesKey = null, rawTitle = "Re:Zero")))
        assertEquals(189046, store.presentation.first().notices.single().mediaId)
    }

    @Test fun twoDifferentEntriesBehindOneShortTitleAreAmbiguousAndGiveNoLink() = runBlocking {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        listOf("alpha-one" to 1, "alpha-two" to 2).forEach { (slug, media) ->
            val e = ExternalMapping(AniWorldMappingSubject.Season(AniWorldSiteIdentifier(slug), 2), ExternalProvider.ANILIST,
                media.toString(), MappingSource.PERSISTED, MappingConfidence.EXACT, now, now, MappingStatus.ACTIVE).toEntity()
            database.matchingDao().upsertSourceMapping(SourceMappingEntity(key.sourceId, key.extensionId, key.publisherId,
                key.providerId, e.mappingSubjectKey, e.externalProvider, e.seriesStableKey, e.siteSlug, e.subjectType,
                e.navigationSeason, e.filmNumber, e.externalId, e.mappingSource, e.mappingStatus, e.confidence, e.createdAt,
                e.validatedAt, e.staleAt, e.provenance, e.parserVersion, 1L, now.toString()))
            val title = "Alpha ${if (media == 1) "One" else "Two"}"
            database.matchingDao().upsertLabel(SourceSeriesLabelEntity(key.sourceId, key.extensionId, key.publisherId,
                key.providerId, slug, title, com.axiel7.anihyou.release.core.matching.SearchTitleFolding.fold(title),
                "", now.toString(), now.toString()))
        }
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation().copy(providerSeriesKey = null, rawTitle = "Alpha")))
        assertNull(store.presentation.first().notices.single().mediaId)
    }

    private suspend fun bindSeason(slug: String, season: Int, media: Int) {
        val e = ExternalMapping(AniWorldMappingSubject.Season(AniWorldSiteIdentifier(slug), season), ExternalProvider.ANILIST,
            media.toString(), MappingSource.PERSISTED, MappingConfidence.EXACT, now, now, MappingStatus.ACTIVE).toEntity()
        database.matchingDao().upsertSourceMapping(SourceMappingEntity(key.sourceId, key.extensionId, key.publisherId,
            key.providerId, e.mappingSubjectKey, e.externalProvider, e.seriesStableKey, e.siteSlug, e.subjectType,
            e.navigationSeason, e.filmNumber, e.externalId, e.mappingSource, e.mappingStatus, e.confidence, e.createdAt,
            e.validatedAt, e.staleAt, e.provenance, e.parserVersion, 1L, now.toString()))
    }

    private suspend fun listSeries(slug: String, title: String, aliases: List<String> = emptyList()) {
        val fold = com.axiel7.anihyou.release.core.matching.SearchTitleFolding::fold
        database.matchingDao().upsertLabel(SourceSeriesLabelEntity(key.sourceId, key.extensionId, key.publisherId,
            key.providerId, slug, title, fold(title), aliases.joinToString("\n") { fold(it) }, now.toString(), now.toString()))
    }

    private suspend fun mediaOfNotice(title: String, season: Int): Int? {
        val store = FileExtensionPostponementStore(temporary.newFolder(), database)
        store.record(key, 1, "a".repeat(64), 1, now, listOf(observation().copy(
            providerSeriesKey = null, rawTitle = title, sourceSeason = season, navigationSeason = season)))
        return store.presentation.first().notices.single().mediaId
    }

    /** The page is typed by hand: "Ascendance os a Bookworm", and the season it names is not the one the calendar bound. */
    @Test fun aTypoInTheTitleAndASeasonNextToTheBoundOneStillFindTheMedia() = runBlocking {
        listSeries("ascendance-of-a-bookworm", "Ascendance of a Bookworm", listOf("Honzuki no Gekokujou"))
        bindSeason("ascendance-of-a-bookworm", 3, 171110)
        assertEquals("the season the notice names is the one bound", 171110, mediaOfNotice("Ascendance os a Bookworm", 3))
        assertEquals("a season next to the only bound one stands in for it", 171110, mediaOfNotice("Ascendance os a Bookworm", 4))
        assertNull("a season further away does not", mediaOfNotice("Ascendance os a Bookworm", 6))
    }

    @Test fun anotherNameOfTheSeriesFindsItToo() = runBlocking {
        listSeries("ascendance-of-a-bookworm", "Ascendance of a Bookworm", listOf("Honzuki no Gekokujou"))
        bindSeason("ascendance-of-a-bookworm", 3, 171110)
        assertEquals(171110, mediaOfNotice("Honzuki no Gekokujou", 3))
        assertEquals(171110, mediaOfNotice("Bookworm Ascendance", 3))
    }

    @Test fun twoBoundSeasonsNextToTheNamedOneAreAmbiguousAndGiveNoLink() = runBlocking {
        listSeries("long-runner", "Long Runner Chronicles")
        bindSeason("long-runner", 3, 31)
        bindSeason("long-runner", 5, 51)
        assertNull(mediaOfNotice("Long Runner Chronicles", 4))
        assertEquals(51, mediaOfNotice("Long Runner Chronicles", 5))
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

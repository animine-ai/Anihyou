package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.ManagedMapping
import com.axiel7.anihyou.release.core.api.MappingMutationResult
import com.axiel7.anihyou.release.core.api.MappingQuery
import com.axiel7.anihyou.release.core.api.MappingRematchOutcome
import com.axiel7.anihyou.release.core.api.MappingScope
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.matching.SearchTitleFolding
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.ReleaseMappingEntity
import com.axiel7.anihyou.release.data.db.SourceMappingEntity
import com.axiel7.anihyou.release.data.db.SourceSeriesLabelEntity
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persistent matching management against a real Room database: what the list shows (source-bound, unknown
 * origin and manual segments), exact scopes, atomic reset with writer fences, correction with revisions, the bindings
 * in force per source, the first resolution when Anime Details opens and the explicit rematch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMatchingManagementRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "matching-management-test.db"
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")
    private val keyA = ExtensionSelectionKey("source-a", "de.aniworld", "publisher.a", "aniworld")
    private val keyB = ExtensionSelectionKey("source-b", "de.aniworld", "publisher.b", "aniworld")
    private val directories = mutableListOf<File>()
    private val openDatabases = mutableListOf<ReleaseDatabase>()

    @After fun cleanup() {
        openDatabases.forEach { runCatching { it.close() } }
        context.deleteDatabase(name)
        directories.forEach { it.deleteRecursively() }
    }

    private class TestClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private class Policy(initial: ExtensionProductPolicy) : ExtensionProductPolicyRepository {
        override val policy = MutableStateFlow(initial)
        override suspend fun selectActiveSource(key: ExtensionSelectionKey?) {
            policy.value = policy.value.copy(activeReleaseSource = key)
        }
        override suspend fun selectNavigationProvider(key: ExtensionSelectionKey?) = Unit
        override suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) = Unit
        override suspend fun invalidateSource(sourceId: String) = Unit
        override suspend fun <T> withCurrentSelection(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
            if (policy.value == snapshot) block() else null
    }

    private class Sources(initial: List<ExtensionSource>) : ExtensionSourceRepository {
        override val sources = MutableStateFlow(initial)
        override suspend fun add(url: String): AddExtensionSourceResult = error("unused")
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = error("unused")
        override suspend fun remove(sourceId: String) = error("unused")
        override suspend fun refresh(sourceId: String) = error("unused")
        override suspend fun refreshEnabled(): Boolean = error("unused")
        override suspend fun activate(sourceId: String, extensionId: String) = error("unused")
    }

    private class Candidates : IdentityCandidateSource {
        var local: List<IdentityCandidate> = emptyList()
        var targeted: List<IdentityCandidate> = emptyList()
        var localCalls = 0
        var targetedCalls = 0
        override suspend fun localCandidates(keys: Set<SourceIdentity>): CandidateBatch {
            localCalls++
            return CandidateBatch(local, true)
        }
        override suspend fun targetedSearch(query: TargetedIdentityQuery): CandidateBatch {
            targetedCalls++
            return CandidateBatch(targeted, true)
        }
    }

    private fun source(key: ExtensionSelectionKey, displayName: String) = ExtensionSource(
        id = key.sourceId, url = "https://${key.sourceId}.example.test/repository",
        origin = "https://${key.sourceId}.example.test", enabled = true, status = ExtensionSourceStatus.CURRENT,
        extensions = listOf(SourceExtension(extensionId = key.extensionId, displayName = displayName, version = "1",
            digest = "d".repeat(64), releaseSequence = 1, capabilities = emptyList(),
            providerId = key.providerId, publisherId = key.publisherId)),
    )

    private inner class Rig {
        val clock = TestClock(t0)
        val database: ReleaseDatabase = Room.databaseBuilder(context, ReleaseDatabase::class.java, name)
            .allowMainThreadQueries().build().also { openDatabases += it }
        val directory: File = Files.createTempDirectory("matching-nav").toFile().also { directories += it }
        val navigation = FileProviderNavigationStateStore(directory)
        val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
        val sources = Sources(listOf(source(keyA, "Source A"), source(keyB, "Source B")))
        val candidates = Candidates()
        val fence = MappingWriterFence(database, clock)
        val service = SourceSeriesMatchingService(database, policy, navigation, candidates, fence, clock = clock)
        val repository = RoomMatchingManagementRepository(database, navigation, sources, service, fence, clock)
        val dao = database.matchingDao()
        val releaseDao = database.releaseDao()

        suspend fun page(query: MappingQuery = MappingQuery()) = repository.observePage(query).first()
        suspend fun entry(page: List<ManagedMapping>, media: Int) = page.single { it.mediaId == media }
        suspend fun effective(key: ExtensionSelectionKey) = dao.observeEffectiveAniListMappings(key.sourceId,
            key.extensionId, key.publisherId, key.providerId, MappingEntryIds.sourceKey(key)).first().map { it.externalId }
    }

    private fun subject(slug: String, season: Int) = AniWorldMappingSubject.Season(AniWorldSiteIdentifier(slug), season)

    private fun domain(slug: String, season: Int, media: Int, source: MappingSource = MappingSource.PERSISTED,
                       createdAt: Instant = t0) =
        ExternalMapping(subject(slug, season), ExternalProvider.ANILIST, media.toString(), source,
            MappingConfidence.EXACT, createdAt, createdAt, MappingStatus.ACTIVE)

    private fun sourceRow(key: ExtensionSelectionKey, slug: String, season: Int, media: Int,
                          source: MappingSource = MappingSource.PERSISTED): SourceMappingEntity {
        val e = domain(slug, season, media, source).toEntity()
        return SourceMappingEntity(key.sourceId, key.extensionId, key.publisherId, key.providerId, e.mappingSubjectKey,
            e.externalProvider, e.seriesStableKey, e.siteSlug, e.subjectType, e.navigationSeason, e.filmNumber,
            e.externalId, e.mappingSource, e.mappingStatus, e.confidence, e.createdAt, e.validatedAt, e.staleAt,
            e.provenance, e.parserVersion, 1L, t0.toString())
    }

    private fun label(key: ExtensionSelectionKey, slug: String, title: String, aliases: String = "") =
        SourceSeriesLabelEntity(key.sourceId, key.extensionId, key.publisherId, key.providerId, slug, title,
            SearchTitleFolding.fold(title), aliases, t0.toString(), t0.toString())

    private fun r2(streamKey: String, media: Int) = ReleaseMappingEntity(streamKey, media, "EXACT", 1.0, null,
        "fixture", "wp04-matcher-v1", "AUTO", t0.toString())

    private fun segment(key: ExtensionSelectionKey, media: Int, series: String, season: Int = 3) =
        ProviderEpisodeSegment(key, media, series, season, 1, 13, 12)

    private suspend fun Rig.seedAll() {
        dao.upsertSourceMapping(sourceRow(keyA, "alpha", 1, 11))
        dao.upsertSourceMapping(sourceRow(keyA, "beta", 2, 12))
        dao.upsertSourceMapping(sourceRow(keyB, "alpha", 1, 21))
        dao.upsertLabel(label(keyA, "alpha", "Alpha Anime"))
        dao.upsertLabel(label(keyA, "beta", "Beta Anime"))
        releaseDao.upsertExternalMapping(domain("gamma", 1, 31, MappingSource.MALSYNC).toEntity())
        releaseDao.upsertMappings(listOf(r2("aniworld/delta/EPISODE/1/DE_SUB", 41)))
        navigation.upsertSegment(segment(keyA, 51, "alpha"))
    }

    @Test fun listShowsSourceBoundUnknownOriginAndManualSegmentsWithTheirOwnLabels() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val page = rig.page()
        assertEquals(6, page.total)
        assertEquals(listOf(21, 11, 41, 12, 31, 51), page.entries.map { it.mediaId })
        val a = rig.entry(page.entries, 11)
        assertEquals("Alpha Anime", a.sourceTitle)
        assertEquals("Source A", a.sourceLabel)
        assertEquals(keyA, a.source)
        assertEquals("alpha", a.sourceIdentity)
        assertEquals("S1", a.partLabel)
        assertFalse(a.manual)
        val b = rig.entry(page.entries, 21)
        assertNull("another source's series has no title of its own yet", b.sourceTitle)
        assertEquals("Source B", b.sourceLabel)
        val legacy = rig.entry(page.entries, 31)
        assertNull("old rows have no source and never get one", legacy.source)
        assertEquals("", legacy.sourceLabel)
        assertNull(legacy.sourceTitle)
        assertEquals("gamma", legacy.sourceIdentity)
        val older = rig.entry(page.entries, 41)
        assertNull(older.source)
        assertEquals("delta", older.sourceIdentity)
        assertEquals("S1 · DE_SUB", older.partLabel)
        val manual = rig.entry(page.entries, 51)
        assertTrue(manual.manual)
        assertEquals(keyA, manual.source)
        assertTrue(manual.partLabel.contains("E1-12"))
        assertEquals(listOf(keyA to 3, keyB to 1), page.sources.map { it.key to it.count })
        assertEquals(listOf("Source A", "Source B"), page.sources.map { it.label })
        assertEquals("ids are unique", page.entries.size, page.entries.map { it.id }.distinct().size)
    }

    @Test fun sourceFilterExcludesOtherSourcesAndUnknownOrigin() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val page = rig.page(MappingQuery(source = keyA))
        assertEquals(3, page.total)
        assertEquals(listOf(11, 12, 51), page.entries.map { it.mediaId })
        assertEquals("the facets ignore the filter", 2, page.sources.size)
    }

    @Test fun searchFoldsTitlesAliasesAndMatchesTheTargetId() = runBlocking {
        val rig = Rig()
        rig.dao.upsertSourceMapping(sourceRow(keyA, "snk", 1, 61))
        rig.dao.upsertSourceMapping(sourceRow(keyA, "other", 1, 62))
        rig.dao.upsertLabel(label(keyA, "snk", "Shingeki no Kyojin", SearchTitleFolding.fold("Attack on Titan")))
        rig.dao.upsertLabel(label(keyA, "other", "Something Else"))
        assertEquals(listOf(61), rig.page(MappingQuery(text = "ATTACK")).entries.map { it.mediaId })
        assertEquals(listOf(61), rig.page(MappingQuery(text = "Shíngeki")).entries.map { it.mediaId })
        assertEquals(listOf(62), rig.page(MappingQuery(text = "else")).entries.map { it.mediaId })
        assertEquals(listOf(61), rig.page(MappingQuery(text = "61")).entries.map { it.mediaId })
        val none = rig.page(MappingQuery(text = "zzz"))
        assertEquals(0, none.total)
        assertTrue(none.entries.isEmpty())
        assertEquals("facets count the unsearched set", 2, none.sources.single().count)
    }

    @Test fun pagingIsStableAcrossRoomRowsAndSegments() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val pages = listOf(0, 2, 4).map { rig.page(MappingQuery(offset = it, limit = 2)) }
        assertTrue(pages.all { it.total == 6 })
        assertEquals(listOf(2, 2, 2), pages.map { it.entries.size })
        assertEquals(rig.page().entries.map { it.id }, pages.flatMap { it.entries }.map { it.id })
        assertTrue(rig.page(MappingQuery(offset = 6, limit = 2)).entries.isEmpty())
    }

    @Test fun resetRemovesOnlyTheCapturedEntryAndRaisesItsWriterFence() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val page = rig.page()
        val target = rig.entry(page.entries, 11)
        val token = rig.repository.capture(MappingScope.Entries(mapOf(target.id to target.revision)))
        assertEquals(1, token.count)
        rig.clock.now = t0.plusSeconds(60)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        val after = rig.page()
        assertEquals(listOf(21, 41, 12, 31, 51), after.entries.map { it.mediaId })
        val entryKey = "${subject("alpha", 1).stableKey}|anilist"
        val fenceKey = "${MappingEntryIds.sourceKey(keyA)}|$entryKey"
        assertFalse("a job that began before the reset must not write",
            rig.fence.allows(MappingEntryRef.FENCE_V3_SOURCE, fenceKey, t0.plusSeconds(30)))
        assertTrue(rig.fence.allows(MappingEntryRef.FENCE_V3_SOURCE, fenceKey, t0.plusSeconds(61)))
        assertEquals("a token is single use", MappingMutationResult.UNAVAILABLE, rig.repository.reset(token))
    }

    @Test fun resetIsAtomicWhenAnyCapturedRevisionChanged() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val page = rig.page()
        val first = rig.entry(page.entries, 11)
        val second = rig.entry(page.entries, 12)
        val token = rig.repository.capture(MappingScope.Entries(
            mapOf(first.id to first.revision, second.id to second.revision)))
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(second.id, second.revision, 99))
        assertEquals(MappingMutationResult.STALE, rig.repository.reset(token))
        assertEquals("nothing was removed", 6, rig.page().total)
        assertNull("the token stays usable for a fresh decision", rig.dao.action(token.value)?.consumedAt)
    }

    @Test fun sourceScopeIsFrozenAndNeverTouchesOtherSourcesUnknownOriginOrAnotherSourcesSegments() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        rig.navigation.upsertSegment(segment(keyB, 52, "alpha", season = 4))
        val token = rig.repository.capture(MappingScope.Source(keyA))
        assertEquals(3, token.count)
        // A binding that appears after the confirmation is not part of the frozen scope.
        rig.dao.upsertSourceMapping(sourceRow(keyA, "late", 1, 13))
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        val after = rig.page()
        assertEquals(listOf(21, 13, 41, 31, 52).sorted(), after.entries.map { it.mediaId }.sorted())
        assertEquals(listOf(keyB), rig.navigation.state.value.segments.map { it.key })
    }

    @Test fun allScopeCoversLegacyOlderLaneAndSegmentsAndFencesEveryKind() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val token = rig.repository.capture(MappingScope.All)
        assertEquals(6, token.count)
        rig.clock.now = t0.plusSeconds(10)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        assertEquals(0, rig.page().total)
        assertTrue(rig.navigation.state.value.segments.isEmpty())
        assertNotNull(rig.dao.fence(MappingEntryRef.FENCE_V3_LEGACY, "${subject("gamma", 1).stableKey}|anilist"))
        assertNotNull(rig.dao.fence(MappingEntryRef.FENCE_R2, "aniworld/delta/EPISODE/1/DE_SUB"))
        assertNotNull(rig.dao.fence(MappingEntryRef.FENCE_V3_SOURCE,
            "${MappingEntryIds.sourceKey(keyB)}|${subject("alpha", 1).stableKey}|anilist"))
    }

    @Test fun correctionNeedsTheUnchangedRevisionAndMarksTheBindingManual() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val page = rig.page()
        val own = rig.entry(page.entries, 11)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(own.id, own.revision, 99))
        val row = rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("alpha", 1).stableKey, "anilist")!!
        assertEquals("99", row.externalId)
        assertEquals(MappingSource.MANUAL.name, row.mappingSource)
        assertEquals(MappingConfidence.EXACT.name, row.confidence)
        assertEquals(2L, row.revision)
        assertEquals(MappingMutationResult.STALE, rig.repository.correct(own.id, own.revision, 100))
        assertEquals(MappingMutationResult.UNAVAILABLE, rig.repository.correct(own.id, "2", 0))
        assertEquals(MappingMutationResult.UNAVAILABLE, rig.repository.correct("bogus", "1", 5))

        val legacy = rig.entry(page.entries, 31)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(legacy.id, legacy.revision, 77))
        val legacyRow = rig.releaseDao.getExternalMapping(subject("gamma", 1).stableKey, "anilist")!!
        assertEquals("77", legacyRow.externalId)
        assertEquals(MappingSource.MANUAL.name, legacyRow.mappingSource)

        val older = rig.entry(page.entries, 41)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(older.id, older.revision, 78))
        assertEquals(78, rig.releaseDao.getMapping("aniworld/delta/EPISODE/1/DE_SUB")?.mediaId)
        assertEquals("MANUAL", rig.releaseDao.getMapping("aniworld/delta/EPISODE/1/DE_SUB")?.origin)

        val manual = rig.entry(page.entries, 51)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(manual.id, manual.revision, 79))
        assertEquals(79, rig.navigation.state.value.segments.single().mediaId)
        assertEquals(MappingMutationResult.STALE, rig.repository.correct(manual.id, manual.revision, 80))
    }

    @Test fun effectiveBindingsAreSourceBoundAndAnOldRowIsHiddenOnlyForTheSourceThatResetItsOwn() = runBlocking {
        val rig = Rig()
        rig.releaseDao.upsertExternalMapping(domain("shared", 1, 31, MappingSource.MALSYNC).toEntity())
        assertEquals(listOf("31"), rig.effective(keyA))
        assertEquals(listOf("31"), rig.effective(keyB))
        rig.dao.upsertSourceMapping(sourceRow(keyA, "shared", 1, 32))
        assertEquals("its own row wins for A", listOf("32"), rig.effective(keyA))
        assertEquals("B is not affected by A", listOf("31"), rig.effective(keyB))
        val overview = rig.dao.effectiveOverviewMappings(keyA.sourceId, keyA.extensionId, keyA.publisherId,
            keyA.providerId, MappingEntryIds.sourceKey(keyA), "32")
        assertEquals(listOf("shared"), overview.map { it.siteSlug })
        val own = rig.entry(rig.page(MappingQuery(source = keyA)).entries, 32)
        val token = rig.repository.capture(MappingScope.Entries(mapOf(own.id to own.revision)))
        rig.clock.now = t0.plusSeconds(5)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        assertEquals("A reset it: the old provider-wide row must not come back for A", emptyList<String?>(),
            rig.effective(keyA))
        assertEquals(listOf("31"), rig.effective(keyB))
    }

    @Test fun anAutomaticWriterThatStartedBeforeAResetCannotBringTheBindingBack() = runBlocking {
        val rig = Rig()
        rig.releaseDao.upsertExternalMapping(domain("gamma", 1, 31, MappingSource.MALSYNC).toEntity())
        val legacy = rig.entry(rig.page().entries, 31)
        val token = rig.repository.capture(MappingScope.Entries(mapOf(legacy.id to legacy.revision)))
        rig.clock.now = t0.plusSeconds(60)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        val writer = RoomExternalMappingRepository(rig.database, rig.clock)
        assertFalse("resolved before the reset", writer.put(domain("gamma", 1, 31, MappingSource.MALSYNC, t0.plusSeconds(30))))
        assertFalse("a writer that claims to have observed in the future is refused too",
            writer.put(domain("gamma", 1, 31, MappingSource.MALSYNC, t0.plusSeconds(86_400))))
        assertNull(rig.releaseDao.getExternalMapping(subject("gamma", 1).stableKey, "anilist"))
        rig.clock.now = t0.plusSeconds(120)
        assertTrue("resolved after the reset", writer.put(domain("gamma", 1, 31, MappingSource.MALSYNC, t0.plusSeconds(61))))
        assertTrue("an explicit manual binding is never blocked",
            writer.put(domain("gamma", 1, 32, MappingSource.MANUAL, t0.plusSeconds(1))))
    }

    @Test fun detailEntryRunsNoMatcherWhenABindingAlreadyExists() = runBlocking {
        val rig = Rig()
        rig.dao.upsertSourceMapping(sourceRow(keyA, "aot", 1, 42))
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        rig.repository.ensureDetailMapping(DetailMappingRequest(42, setOf("Attack on Titan"), "TV"))
        rig.repository.ensureDetailMapping(42)
        assertEquals(0, rig.candidates.localCalls + rig.candidates.targetedCalls)
        assertEquals(1, rig.page().total)
    }

    @Test fun detailEntryResolvesExactlyOneSourceSeriesOnceAndKeepsItAfterwards() = runBlocking {
        val rig = Rig()
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        val request = DetailMappingRequest(7, setOf("Attack on Titan"), "TV", 2013)
        rig.repository.ensureDetailMapping(request)
        val row = rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!
        assertEquals("7", row.externalId)
        assertEquals(MappingSource.PERSISTED.name, row.mappingSource)
        assertEquals(MappingConfidence.EXACT.name, row.confidence)
        assertTrue(row.provenance.startsWith("targeted:"))
        assertEquals("another source got nothing", 0, rig.page(MappingQuery(source = keyB)).total)
        rig.repository.ensureDetailMapping(request)
        assertEquals(1L, rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!.revision)
        assertEquals("no AniList request was needed", 0, rig.candidates.targetedCalls)
    }

    @Test fun detailEntryDoesNotGuessBetweenTwoSourceSeriesWithTheSameTitle() = runBlocking {
        val rig = Rig()
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        rig.dao.upsertLabel(label(keyA, "aot-2", "Attack on Titan"))
        rig.repository.ensureDetailMapping(DetailMappingRequest(7, setOf("Attack on Titan"), "TV"))
        assertEquals(0, rig.page().total)
    }

    @Test fun concurrentDetailEntriesShareOneResolutionAndWriteOneRow() = runBlocking {
        val rig = Rig()
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        val request = DetailMappingRequest(7, setOf("Attack on Titan"), "TV")
        (1..4).map { async { rig.repository.ensureDetailMapping(request) } }.awaitAll()
        assertEquals(1, rig.page().total)
    }

    @Test fun detailEntryResolvesAgainAfterTheSourceItselfResetTheBinding() = runBlocking {
        val rig = Rig()
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        val request = DetailMappingRequest(7, setOf("Attack on Titan"), "TV")
        rig.repository.ensureDetailMapping(request)
        val own = rig.page().entries.single()
        val token = rig.repository.capture(MappingScope.Entries(mapOf(own.id to own.revision)))
        rig.clock.now = t0.plusSeconds(30)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        assertEquals(0, rig.page().total)
        rig.clock.now = t0.plusSeconds(90)
        rig.repository.ensureDetailMapping(request)
        assertEquals(1, rig.page().total)
    }

    @Test fun rematchReplacesAWrongBindingByAnExactOneAndReportsEachEntry() = runBlocking {
        val rig = Rig()
        rig.dao.upsertSourceMapping(sourceRow(keyA, "aot", 1, 9))
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        rig.candidates.targeted = listOf(IdentityCandidate(7, setOf("Attack on Titan"), "TV", null))
        val entry = rig.page().entries.single()
        val token = rig.repository.capture(MappingScope.Entries(mapOf(entry.id to entry.revision)))
        val progress = rig.repository.rematch(token).toList()
        assertEquals(0, progress.first().completed)
        assertEquals(1, progress.first().total)
        val last = progress.last()
        assertEquals(entry.id, last.entryId)
        assertEquals(MappingRematchOutcome.REPLACED, last.outcome)
        val row = rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!
        assertEquals("7", row.externalId)
        assertEquals(2L, row.revision)
    }

    @Test fun rematchKeepsABindingWhenNothingBetterExistsOrItChangedSinceConfirmation() = runBlocking {
        val rig = Rig()
        rig.dao.upsertSourceMapping(sourceRow(keyA, "aot", 1, 7))
        rig.dao.upsertSourceMapping(sourceRow(keyA, "bleach", 1, 8))
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        rig.dao.upsertLabel(label(keyA, "bleach", "Bleach"))
        rig.candidates.targeted = listOf(IdentityCandidate(7, setOf("Attack on Titan"), "TV", null))
        val page = rig.page()
        val same = rig.entry(page.entries, 7)
        val changed = rig.entry(page.entries, 8)
        val token = rig.repository.capture(MappingScope.Entries(
            mapOf(same.id to same.revision, changed.id to changed.revision)))
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(changed.id, changed.revision, 99))
        val outcomes = rig.repository.rematch(token).toList().filter { it.entryId != null }
            .associate { it.entryId to it.outcome }
        assertEquals(MappingRematchOutcome.RETAINED, outcomes[same.id])
        assertEquals(MappingRematchOutcome.STALE, outcomes[changed.id])
        assertEquals("99", rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("bleach", 1).stableKey, "anilist")!!.externalId)
    }

    @Test fun aRematchThatBeganBeforeACorrectionCannotWriteAReplacement() = runBlocking {
        val rig = Rig()
        rig.dao.upsertSourceMapping(sourceRow(keyA, "aot", 1, 9))
        rig.dao.upsertLabel(label(keyA, "aot", "Attack on Titan"))
        rig.candidates.targeted = listOf(IdentityCandidate(7, setOf("Attack on Titan"), "TV", null))
        val entry = rig.page().entries.single()
        val ref = MappingEntryIds.decode(entry.id)!!
        val (kind, key) = ref.fence!!
        val epochAtStart = rig.fence.epoch(kind, key)
        // The clock does not matter: the user corrects the binding while the job that began earlier is still running.
        assertEquals(MappingMutationResult.APPLIED, rig.repository.correct(entry.id, entry.revision, 55))
        assertEquals(epochAtStart + 1, rig.fence.epoch(kind, key))
        assertEquals(MappingRematchOutcome.STALE, rig.service.rematch(ref, 9, epochAtStart))
        assertEquals("55", rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!.externalId)
    }

    @Test fun theFenceNeverMovesBackwardsAndTimeBasedWritersAreCheckedAgainstTheLastChange() = runBlocking {
        val rig = Rig()
        val kind = MappingEntryRef.FENCE_R2
        val key = "aniworld/x/EPISODE/1/DE_SUB"
        assertEquals(0L, rig.fence.epoch(kind, key))
        assertTrue(rig.fence.allows(kind, key, t0.minusSeconds(3600)))
        rig.clock.now = t0.plusSeconds(100)
        rig.fence.bump(kind, key, rig.clock.now)
        // A clock that jumped back must not reopen the entry for writers that began before the change.
        rig.fence.bump(kind, key, t0.plusSeconds(10))
        assertEquals(2L, rig.fence.epoch(kind, key))
        assertEquals(t0.plusSeconds(100).toString(), rig.dao.fence(kind, key)!!.changedAt)
        assertFalse(rig.fence.allows(kind, key, t0.plusSeconds(50)))
        assertFalse(rig.fence.allows(kind, key, t0.plusSeconds(100)))
        assertFalse("claimed observation in the future", rig.fence.allows(kind, key, t0.plusSeconds(7200)))
        assertTrue(rig.fence.allows(kind, key, t0.plusSeconds(101).also { rig.clock.now = t0.plusSeconds(200) }))
        assertTrue(rig.fence.allowsEpoch(kind, key, 2L))
        assertFalse(rig.fence.allowsEpoch(kind, key, 1L))
    }

    @Test fun staleHistoricalBindingsStayVisibleAndCanBeReset() = runBlocking {
        val rig = Rig()
        val stale = sourceRow(keyA, "old", 1, 70).copy(mappingStatus = MappingStatus.STALE.name,
            staleAt = t0.plusSeconds(5).toString())
        rig.dao.upsertSourceMapping(stale)
        val page = rig.page()
        assertEquals(listOf(70), page.entries.map { it.mediaId })
        assertTrue("a stale binding is no authority for any consumer", rig.effective(keyA).isEmpty())
        val token = rig.repository.capture(MappingScope.All)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        assertEquals(0, rig.page().total)
    }

    @Test fun noMatcherOptionIsExposedAndSettingOneIsRejected() = runBlocking {
        val rig = Rig()
        assertTrue(rig.repository.observeMatcherOptions().first().isEmpty())
        assertTrue(runCatching { rig.repository.setMatcherOption("trust", "all") }.isFailure)
        rig.repository.resetMatcherOptions()
        assertEquals(0, rig.page().total)
    }
}

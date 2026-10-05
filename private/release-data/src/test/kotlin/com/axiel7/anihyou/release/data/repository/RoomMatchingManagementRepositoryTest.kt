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
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CanonicalReleaseState
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import com.axiel7.anihyou.release.data.db.forSource
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
        /** The AniList entries of a season pool by its cache key, and which pools were asked for, in order. */
        var pools: Map<String, List<IdentityCandidate>> = emptyMap()
        val poolRequests = mutableListOf<String>()
        /** The AniList entries that air on a day, and which days were asked for. */
        var airing: Map<java.time.LocalDate, List<IdentityCandidate>> = emptyMap()
        val dayRequests = mutableListOf<java.time.LocalDate>()
        override suspend fun airingCandidates(day: java.time.LocalDate): List<IdentityCandidate> {
            dayRequests += day
            return airing[day].orEmpty()
        }
        override suspend fun boundedSeasonPool(request: com.axiel7.anihyou.release.core.sync.CandidatePoolRequest): CandidateBatch {
            poolRequests += request.window.cacheKey
            return CandidateBatch(pools[request.window.cacheKey].orEmpty(), true)
        }
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

    @Test fun resetCanResumeAfterRoomCommitBeforeNavigationRemoval() = runBlocking {
        val rig = Rig()
        rig.seedAll()
        val page = rig.page()
        val roomEntry = rig.entry(page.entries, 11)
        val navigationEntry = rig.entry(page.entries, 51)
        val token = rig.repository.capture(MappingScope.Entries(mapOf(
            roomEntry.id to roomEntry.revision, navigationEntry.id to navigationEntry.revision)))

        // Simulate the durable Room half of a reset having committed just before process death. The file segment and
        // captured action remain, so retry must finish that half and then consume the action exactly once.
        val entryKey = subject("alpha", 1).stableKey
        assertEquals(1, rig.dao.deleteSourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId,
            keyA.providerId, entryKey, "anilist"))
        val fenceKey = "${MappingEntryIds.sourceKey(keyA)}|$entryKey"
        rig.fence.bump(MappingEntryRef.FENCE_V3_SOURCE, fenceKey, t0.plusSeconds(60))
        assertNull(rig.dao.action(token.value)?.consumedAt)
        assertEquals(1, rig.navigation.state.value.segments.size)

        assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
        assertTrue(rig.navigation.state.value.segments.isEmpty())
        assertFalse(rig.page().entries.any { it.id == roomEntry.id || it.id == navigationEntry.id })
        assertNotNull(rig.dao.action(token.value)?.consumedAt)
        assertEquals(MappingMutationResult.UNAVAILABLE, rig.repository.reset(token))
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

    /** A calendar row: forecast only, so it proves no navigation season. */
    private fun forecastRow(key: ExtensionSelectionKey, slug: String, season: Int, at: Instant) = run {
        val evidence = ReleaseEvidence(
            id = "e-$slug", sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceUrl = "https://aniworld.to/anime/stream/$slug", sourceHash = "hash", parserVersion = "fixture",
            observedAt = t0, sourceReportedAt = null, approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier(slug), sourceSeason = season, navigationSeason = season,
            installment = Installment.Episode(3), languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.CONFIRMATION, confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        val identity = requireNotNull(CanonicalReleaseIdentity.from(evidence))
        val state = CanonicalReleaseState(key = identity.key, underlyingPhase = ReleasePhase.EXPECTED,
            phase = ReleasePhase.EXPECTED, authority = ReleaseAuthority.NONE, releaseAt = null, forecastAt = at,
            conflicts = emptyList(), revision = 1, navigationSeasons = emptySet(), latestCompletedAt = t0)
        ReleaseReconciliationMapper.projection(state, identity.bucketKey, 1)
            .forSource(key.sourceId, key.extensionId, key.publisherId, key.providerId)
    }

    @Test fun autoMatchBindsTheForecastSeriesOfTheActiveSourceAndLeavesAmbiguousFencedAndTakenOnesAlone() = runBlocking {
        val rig = Rig()
        listOf("aot" to "Attack on Titan", "mystery" to "Some Unknown Show", "reset-by-user" to "Attack on Titan Reset")
            .forEach { (slug, title) ->
                rig.dao.upsertLabel(label(keyA, slug, title))
                rig.database.reconciliationDao().upsertSourceProjection(forecastRow(keyA, slug, 1, t0.plusSeconds(3_600)))
            }
        // The user reset this series before; the automatic pass never brings it back.
        rig.fence.bump(MappingEntryRef.FENCE_V3_SOURCE,
            "${MappingEntryIds.sourceKey(keyA)}|${subject("reset-by-user", 1).stableKey}|anilist", t0)
        rig.candidates.targeted = listOf(IdentityCandidate(7, setOf("Attack on Titan"), "TV", java.time.LocalDate.of(2013, 4, 7)))
        val report = rig.service.autoMatchPending(maxSearches = 10)
        assertEquals(2, report.examined)
        assertEquals(1, report.matched)
        val bound = rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!
        assertEquals("7", bound.externalId)
        assertNull(rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("mystery", 1).stableKey, "anilist"))
        assertNull(rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("reset-by-user", 1).stableKey, "anilist"))
        // A second run finds nothing new to bind and does not touch the accepted binding.
        val again = rig.service.autoMatchPending(maxSearches = 10)
        assertEquals(0, again.matched)
        assertEquals(1L, rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!.revision)
    }

    @Test fun unmatchedListsTheSeriesWithoutBindingAndAssignWritesAManualBindingOnce() = runBlocking {
        val rig = Rig()
        listOf("aot" to "Attack on Titan", "bleach" to "Bleach").forEach { (slug, title) ->
            rig.dao.upsertLabel(label(keyA, slug, title))
            rig.database.reconciliationDao().upsertSourceProjection(forecastRow(keyA, slug, 1, t0.plusSeconds(3_600)))
        }
        rig.dao.upsertSourceMapping(sourceRow(keyA, "bleach", 1, 5))
        val list = rig.repository.observeUnmatched().first()
        assertEquals(listOf("aot"), list.map { it.seriesKey })
        assertEquals("Attack on Titan", list.single().title)
        assertEquals(MappingMutationResult.APPLIED, rig.repository.assignUnmatched(list.single(), 7))
        val row = rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("aot", 1).stableKey, "anilist")!!
        assertEquals("7", row.externalId)
        assertEquals(MappingSource.MANUAL.name, row.mappingSource)
        assertEquals("an existing binding is never overwritten here", MappingMutationResult.STALE,
            rig.repository.assignUnmatched(list.single(), 8))
        assertTrue(rig.repository.observeUnmatched().first().isEmpty())
    }

    private suspend fun Rig.seedSeries(vararg series: Triple<String, String, Int>) = series.forEach { (slug, title, season) ->
        dao.upsertLabel(label(keyA, slug, title))
        database.reconciliationDao().upsertSourceProjection(forecastRow(keyA, slug, season, t0.plusSeconds(3_600)))
    }

    @Test fun autoMatchTakesTheAniListCalendarFirstAndStopsAsSoonAsNothingIsOpen() = runBlocking {
        val rig = Rig()
        rig.seedSeries(Triple("show", "Show", 2))
        val today = java.time.LocalDate.of(2026, 10, 1)
        rig.candidates.airing = mapOf(today.plusDays(2) to listOf(
            IdentityCandidate(11, setOf("Show Season 2"), "TV", today.plusDays(2))))
        val report = rig.service.autoMatchPending()
        assertEquals(1, report.matched)
        assertEquals("calendar days in order until the series was found", listOf(today, today.plusDays(1), today.plusDays(2)),
            rig.candidates.dayRequests)
        assertTrue("no season pool and no search were needed", rig.candidates.poolRequests.isEmpty() && rig.candidates.targetedCalls == 0)
    }

    @Test fun autoMatchTakesTheCurrentSeasonThenTheLastAndSingleSearchesOnlyAtTheEnd() = runBlocking {
        val rig = Rig()
        rig.seedSeries(Triple("now", "Now Show", 1), Triple("old", "Old Show", 1), Triple("nowhere", "Nowhere", 1))
        rig.candidates.pools = mapOf(
            "season-pool:fall:2026" to listOf(IdentityCandidate(20, setOf("Now Show"), "TV", java.time.LocalDate.of(2026, 10, 1))),
            "season-pool:summer:2026" to listOf(IdentityCandidate(21, setOf("Old Show"), "TV", java.time.LocalDate.of(2026, 7, 5))),
        )
        val report = rig.service.autoMatchPending()
        assertEquals(2, report.matched)
        assertEquals("the next season is never loaded; the third pool goes back one more season",
            listOf("season-pool:fall:2026", "season-pool:summer:2026", "season-pool:spring:2026"), rig.candidates.poolRequests)
        assertEquals("only the series nothing else settled is searched", 1, rig.candidates.targetedCalls)
    }

    @Test fun aDubThatRunsWeeksBehindIsFoundInTheSeasonBeforeTheLastOne() = runBlocking {
        val rig = Rig()
        rig.seedSeries(Triple("late", "Late Show", 1))
        rig.candidates.pools = mapOf(
            "season-pool:spring:2026" to listOf(IdentityCandidate(40, setOf("Late Show"), "TV", java.time.LocalDate.of(2026, 4, 6))))
        val report = rig.service.autoMatchPending()
        assertEquals(1, report.matched)
        assertEquals("no single search was needed", 0, rig.candidates.targetedCalls)
        assertEquals("40", rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("late", 1).stableKey, "anilist")!!.externalId)
    }

    @Test fun autoMatchTakesTheAiringEntryOfTheTitleWhenTheSeasonNumbersDiffer() = runBlocking {
        val rig = Rig()
        rig.seedSeries(Triple("black", "Black", 5))
        rig.candidates.pools = mapOf("season-pool:fall:2026" to listOf(
            IdentityCandidate(31, setOf("Black Season 2"), "TV", java.time.LocalDate.of(2026, 10, 2))))
        assertEquals(1, rig.service.autoMatchPending().matched)
        assertEquals("31", rig.dao.sourceMapping(keyA.sourceId, keyA.extensionId, keyA.publisherId, keyA.providerId,
            subject("black", 5).stableKey, "anilist")!!.externalId)
    }

    @Test fun autoMatchRunsOneAtATime() = runBlocking {
        val rig = Rig()
        rig.seedSeries(Triple("show", "Show", 2))
        rig.candidates.pools = mapOf("season-pool:fall:2026" to listOf(
            IdentityCandidate(11, setOf("Show Season 2"), "TV", java.time.LocalDate.of(2026, 10, 1))))
        listOf(async { rig.service.autoMatchPending() }, async { rig.service.autoMatchPending() }).awaitAll()
        assertEquals("the second run found nothing left and asked for no pool", 1, rig.candidates.poolRequests.size)
        assertEquals("and for no calendar day", 9, rig.candidates.dayRequests.size)
    }

    @Test fun autoMatchNeverBindsTwoSeriesToOneAniListEntry() = runBlocking {
        val rig = Rig()
        listOf("aot", "aot-copy").forEach { slug ->
            rig.dao.upsertLabel(label(keyA, slug, "Attack on Titan"))
            rig.database.reconciliationDao().upsertSourceProjection(forecastRow(keyA, slug, 1, t0.plusSeconds(3_600)))
        }
        rig.candidates.targeted = listOf(IdentityCandidate(7, setOf("Attack on Titan"), "TV", java.time.LocalDate.of(2013, 4, 7)))
        val report = rig.service.autoMatchPending(maxSearches = 10)
        assertEquals("only the first series takes the entry", 1, report.matched)
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

    @Test fun rematchProcessesTheEntireCapturedScopeBeyondOneHundredEntries() = runBlocking {
        val rig = Rig()
        rig.releaseDao.upsertMappings((0 until 101).map { index ->
            r2("aniworld/series-$index/EPISODE/1/DE_SUB", 1000 + index)
        })

        val token = rig.repository.capture(MappingScope.All)
        assertEquals(101, token.count)

        val progress = rig.repository.rematch(token).toList()

        assertEquals(101, progress.first().total)
        val results = progress.drop(1)
        assertEquals(101, results.size)
        assertTrue(results.all { it.outcome == MappingRematchOutcome.UNAVAILABLE })
        assertEquals(101, progress.last().completed)
        assertEquals(101, progress.last().total)
    }

    @Test fun rematchSpendsOnlyItsSearchBudgetAndLeavesTheRestUnexamined() = runBlocking {
        val rig = Rig()
        listOf("a" to 1, "b" to 2, "c" to 3).forEach { (slug, media) ->
            rig.dao.upsertSourceMapping(sourceRow(keyA, slug, 1, media))
            rig.dao.upsertLabel(label(keyA, slug, "Series $slug"))
        }
        val limited = RoomMatchingManagementRepository(rig.database, rig.navigation, rig.sources, rig.service,
            rig.fence, rig.clock, searchesPerRun = 1)
        val token = limited.capture(MappingScope.All)
        val outcomes = limited.rematch(token).toList().filter { it.entryId != null }.map { it.outcome }
        assertEquals(3, outcomes.size)
        assertEquals("one search was affordable", 1, rig.candidates.targetedCalls)
        assertEquals(1, outcomes.count { it == MappingRematchOutcome.RETAINED })
        assertEquals(2, outcomes.count { it == MappingRematchOutcome.UNAVAILABLE })
        assertEquals("nothing was removed or changed", 3, rig.page().total)
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

    /**
     * Synthetic scale measurement of the settings-only matching management on a real Room database: 100, 1,000 and 10,000
     * accepted bindings of one source (each with a title). Nothing asserts a time; the numbers go to the log (MEASURE lines)
     * and the test fails only when the scope is not complete: the first page, a search, a deep page, the frozen source scope
     * and the atomic reset must see and remove every binding.
     */
    @Test fun matchingManagementCostIsMeasuredAtScaleAndTheScopeStaysComplete() = runBlocking {
        fun nowMs() = System.nanoTime() / 1_000_000.0
        for (count in listOf(100, 1_000, 10_000)) {
            val rig = Rig()
            val seedStart = nowMs()
            for (index in 0 until count) {
                val slug = "series-%05d".format(index)
                rig.dao.upsertSourceMapping(sourceRow(keyA, slug, 1, 1_000 + index))
                rig.dao.upsertLabel(label(keyA, slug, "Series Title %05d".format(index)))
            }
            val seedMs = nowMs() - seedStart
            fun median(block: suspend () -> Unit): Double {
                runBlocking { block() } // warm-up
                return (0 until 3).map { val t = nowMs(); runBlocking { block() }; nowMs() - t }.sorted()[1]
            }
            var firstTotal = -1
            val firstPageMs = median { firstTotal = rig.page().total }
            assertEquals(count, firstTotal)
            var searchHits = -1
            val searchMs = median { searchHits = rig.page(MappingQuery(text = "Series Title %05d".format(count / 2))).entries.size }
            assertEquals("a folded title search finds exactly its binding", 1, searchHits)
            var deepSize = -1
            val deepPageMs = median { deepSize = rig.page(MappingQuery(source = keyA, offset = count - 50)).entries.size }
            assertEquals(50, deepSize)
            val captureStart = nowMs()
            val token = rig.repository.capture(MappingScope.Source(keyA))
            val captureMs = nowMs() - captureStart
            assertEquals("the frozen scope holds every binding of the source", count, token.count)
            val resetStart = nowMs()
            assertEquals(MappingMutationResult.APPLIED, rig.repository.reset(token))
            val resetMs = nowMs() - resetStart
            assertEquals("the reset removed every captured binding and nothing else", 0, rig.page().total)
            println("MEASURE matching bindings=$count seedMs=%.0f firstPageMs=%.2f searchMs=%.2f deepPageMs=%.2f captureMs=%.2f resetMs=%.2f"
                .format(seedMs, firstPageMs, searchMs, deepPageMs, captureMs, resetMs))
            rig.database.close()
            openDatabases.remove(rig.database)
            context.deleteDatabase(name)
        }
    }
}

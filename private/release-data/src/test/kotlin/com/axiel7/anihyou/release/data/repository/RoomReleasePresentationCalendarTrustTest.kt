package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.InstalledPackageStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Transition tests for the calendar trust gate (4f52e946) against the real Room projection with rows that were
 * accepted earlier. The persisted evidence never changes in these tests; only the live trust state of the active
 * source does. Presented rows must follow the current trust state, and losing trust must not delete accepted rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomReleasePresentationCalendarTrustTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "calendar-trust-transition-test.db"
    private val observedAt = Instant.parse("2026-09-26T10:00:00Z")
    private val range = LocalDate.parse("2026-09-20")..LocalDate.parse("2026-10-03")
    private val keyA = ExtensionSelectionKey("source-a", "de.aniworld", "publisher.a", "aniworld")
    private val keyB = ExtensionSelectionKey("source-b", "de.aniworld", "publisher.b", "aniworld")

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun open() = Room.databaseBuilder(context, ReleaseDatabase::class.java, name)
        .allowMainThreadQueries().build()

    private fun forecast(
        series: String = "trust-transition", reportedAt: Instant = observedAt, at: Instant = observedAt,
    ): ReleaseEvidence {
        val item = ReleaseEvidence(
            "trust-forecast-$series-$reportedAt", ReleaseSourceType.ANIWORLD_CALENDAR,
            "https://aniworld.to/anime/stream/$series", "hash-trust-forecast-$series-$reportedAt", "fixture",
            at, reportedAt, false, AniWorldSiteIdentifier(series), 2, 4,
            Installment.Episode(1), LanguageTrack.DE_SUB, ReleaseEvidenceType.FORECAST,
            ScheduleCondition.UNKNOWN, ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        return item.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(item))
    }

    /** Commits one cycle through the source-bound route, exactly like a refresh of [source] does. */
    private suspend fun seedAcceptedRow(
        db: ReleaseDatabase, series: String = "trust-transition", cycle: String = "trust-cycle", baseline: Boolean = true,
        source: ExtensionSelectionKey = keyA, reportedAt: Instant = observedAt, at: Instant = observedAt,
    ) {
        val item = forecast(series, reportedAt, at)
        val reconciliation = RoomReleaseReconciliationRepository(db)
        if (baseline) reconciliation.importBaseline()
        reconciliation.persistCompletedCycle(CompletedObservationCycle(
            cycle, series, at.minusSeconds(60), at, AbsencePolicySnapshot(),
            listOf(CycleSourceObservation(
                "$cycle:source", item.sourceType, CanonicalReleaseIdentity.from(item)?.key ?: "scope",
                item.languageTrack, CycleResult.SUCCESS, SourceHealthStatus.HEALTHY,
                observedAt = at, evidence = listOf(item),
            )),
        ), source)
        reconciliation.rebuildProjections()
    }

    private fun extension(
        key: ExtensionSelectionKey,
        usable: Boolean = true,
        status: InstalledPackageStatus = if (usable) InstalledPackageStatus.USABLE else InstalledPackageStatus.UNUSABLE,
        revoked: Boolean = false,
        candidateYanked: Boolean = false,
    ) = SourceExtension(
        extensionId = key.extensionId, displayName = "AniWorld", version = "1.0.0", digest = "digest-${key.sourceId}",
        releaseSequence = 1, capabilities = listOf("CALENDAR"), installedVersion = "1.0.0",
        installedDigest = "digest-${key.sourceId}", activationAllowed = true, providerId = key.providerId,
        publisherId = key.publisherId, installedUsable = usable, installedStatus = status, revoked = revoked,
        candidateYanked = candidateYanked,
    )

    private fun source(
        key: ExtensionSelectionKey,
        extension: SourceExtension = extension(key),
        enabled: Boolean = true,
        status: ExtensionSourceStatus = ExtensionSourceStatus.CURRENT,
        failure: ExtensionSourceFailure? = null,
    ) = ExtensionSource(
        id = key.sourceId, url = "https://${key.sourceId}.example.test/repository",
        origin = "https://${key.sourceId}.example.test", enabled = enabled, status = status,
        lastFailure = failure, extensions = listOf(extension),
    )

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

    private suspend fun presented(repository: RoomReleasePresentationRepository) =
        repository.observeCalendar(null, range).first()

    private fun repository(db: ReleaseDatabase, policy: Policy, sources: Sources) =
        RoomReleasePresentationRepository(RoomReleaseProjectionRepository(db), db, policy, sources)

    @Test fun usableThenRevokedHidesAcceptedRowsWithoutDeletingThemAndResumesOnlyWhenTrustedAgain() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            val trusted = presented(repository)
            assertEquals("accepted row is presented while the active source is usable", 1, trusted.size)

            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false,
                status = InstalledPackageStatus.REVOKED, revoked = true)))
            assertTrue("revoked source must not present its old rows", presented(repository).isEmpty())
            assertEquals("accepted evidence stays stored internally", 1,
                db.reconciliationDao().observeSourceProjections(keyA.sourceId, keyA.extensionId, keyA.publisherId,
                    keyA.providerId).first().size)

            sources.sources.value = listOf(source(keyA))
            assertEquals("a source trusted again presents the retained rows", trusted, presented(repository))
        } finally { db.close() }
    }

    @Test fun disabledAndUnusableAndQuarantinedStatesPresentNothing() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            assertEquals(1, presented(repository).size)

            sources.sources.value = listOf(source(keyA, enabled = false))
            assertTrue("disabled source", presented(repository).isEmpty())
            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false)))
            assertTrue("unusable package", presented(repository).isEmpty())
            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false,
                status = InstalledPackageStatus.QUARANTINED)))
            assertTrue("quarantined package", presented(repository).isEmpty())
            sources.sources.value = emptyList()
            assertTrue("source removed", presented(repository).isEmpty())
            assertEquals(1, db.reconciliationDao().sourceProjectionPage(keyA.sourceId, keyA.extensionId,
                keyA.publisherId, keyA.providerId, 10, 0).size)
        } finally { db.close() }
    }

    @Test fun switchingTheActiveSelectionToAnUntrustedSourceNeverPresentsTheOldSourcesRows() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA), source(keyB, extension(keyB, usable = false))))
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
            val repository = repository(db, policy, sources)
            assertEquals(1, presented(repository).size)

            policy.selectActiveSource(keyB)
            assertTrue("rows of source A must not appear while source B is not usable", presented(repository).isEmpty())
            policy.selectActiveSource(keyA)
            assertEquals(1, presented(repository).size)
        } finally { db.close() }
    }

    /**
     * Two trusted sources that both offer provider "aniworld" (different source id and publisher) fold their cycles
     * into their own rows: B presents exactly what B committed, never what A accepted, in particular not after A
     * lost trust, and A gets its own rows back when it is active again.
     */
    @Test fun aSecondTrustedSourceOfTheSameProviderPresentsOnlyItsOwnCommittedRows() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA), source(keyB)))
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
            val repository = repository(db, policy, sources)
            assertEquals("A committed these rows and is active", 1, presented(repository).size)

            policy.selectActiveSource(keyB)
            assertTrue("B is usable but has committed nothing, A's row is not B's", presented(repository).isEmpty())

            // A is revoked, B is still trusted and active: the revoked source's rows stay hidden.
            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false, status = InstalledPackageStatus.REVOKED,
                revoked = true)), source(keyB))
            assertTrue(presented(repository).isEmpty())

            // B's own refresh commits a different row: under B only that row is presented.
            seedAcceptedRow(db, series = "b-only-series", cycle = "b-cycle", baseline = false, source = keyB)
            val underB = presented(repository)
            assertEquals("only B's own row", 1, underB.size)
            assertTrue(underB.single().stream.stableSeriesKey.value.endsWith("/b-only-series"))

            // A is trusted again and active: only A's own row, B's lane is not presented as A's.
            sources.sources.value = listOf(source(keyA), source(keyB))
            policy.selectActiveSource(keyA)
            val underA = presented(repository)
            assertEquals(1, underA.size)
            assertTrue(underA.single().stream.stableSeriesKey.value.endsWith("/trust-transition"))
            // The provider-wide persisted history was never touched by any of this.
            assertEquals(2, db.reconciliationDao().projectionPage(10, 0).size)
        } finally { db.close() }
    }

    @Test fun anUpdatedPackageOfTheSameSourceKeepsItsRowsPresented() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            assertEquals(1, presented(repository).size)
            // A new package digest of the same source identity (update or rollback) keeps last-known-good rows.
            sources.sources.value = listOf(source(keyA, extension(keyA).copy(installedDigest = "digest-after-update", digest = "digest-after-update")))
            assertEquals(1, presented(repository).size)
        } finally { db.close() }
    }

    @Test fun aSourceThatNeverCommittedPresentsNothingEvenWhenAnotherSourceCommitted() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA), source(keyB)))
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyB))
            assertTrue(presented(repository(db, policy, sources)).isEmpty())
            assertEquals(1, presented(repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)).size)
        } finally { db.close() }
    }

    @Test fun healthyInstalledPackageStaysPresentedWhenOnlyTheCandidateIsYankedOrTheNetworkFails() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            assertEquals(1, presented(repository).size)

            // A yanked candidate is not a revocation of the healthy installed package (planner decision D1).
            sources.sources.value = listOf(source(keyA, extension(keyA, candidateYanked = true)))
            assertEquals(1, presented(repository).size)
            // A transient network failure is not a loss of trust either.
            sources.sources.value = listOf(source(keyA, status = ExtensionSourceStatus.ERROR,
                failure = ExtensionSourceFailure.NETWORK))
            assertEquals(1, presented(repository).size)
        } finally { db.close() }
    }

    /** The effective binding of the seeded row (series "trust-transition", navigation season 4) to AniList media [media]. */
    private suspend fun bindSeededRow(db: ReleaseDatabase, media: Int = 42, series: String = "trust-transition") =
        db.matchingDao().upsertSourceMapping(com.axiel7.anihyou.release.data.db.SourceMappingEntity(
            sourceId = keyA.sourceId, extensionId = keyA.extensionId, publisherId = keyA.publisherId,
            providerId = keyA.providerId, mappingSubjectKey = "subject-$series-4", externalProvider = "anilist",
            seriesStableKey = series, siteSlug = series, subjectType = "SEASON", navigationSeason = 4, filmNumber = null,
            externalId = media.toString(), mappingSource = "MANUAL", mappingStatus = "ACTIVE", confidence = "EXACT",
            createdAt = observedAt.toString(), validatedAt = observedAt.toString(), staleAt = null,
            provenance = "fixture", parserVersion = null, revision = 1L, updatedAt = observedAt.toString(),
        ))

    private fun legacyProjection(media: Int, confirmedThrough: Int) = MediaReleaseProjection(
        mediaId = media,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/legacy-series"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
        authority = AuthorityStatus.VALID, confirmedThroughEpisode = confirmedThrough,
        confirmedInstallments = emptyList(), nextForecast = null, pendingCount = 0,
        freshness = Freshness(FreshnessStatus.FRESH, observedAt, observedAt, observedAt, "legacy", "hash"),
        mapping = null, sourceRoot = "https://aniworld.to/anime/stream/legacy-series", revision = 1,
    )

    private suspend fun presentedForMedia(repository: RoomReleasePresentationRepository, ids: Set<Int> = setOf(42)) =
        repository.observeForMedia(null, ids).first()

    @Test fun perMediaPresentationFollowsTheSameTrustGateAsTheCalendar() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            bindSeededRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            val trusted = presentedForMedia(repository)
            assertEquals("the accepted row reaches the per-media consumers as it reaches the calendar", 1, trusted.getValue(42).size)
            assertEquals(1, presented(repository).size)
            assertTrue(trusted.getValue(42).single().isAuthoritative)

            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false,
                status = InstalledPackageStatus.REVOKED, revoked = true)))
            assertTrue("a revoked source presents nothing to Home, lists and details either", presentedForMedia(repository).isEmpty())
            assertTrue(presented(repository).isEmpty())

            sources.sources.value = listOf(source(keyA))
            assertEquals(trusted, presentedForMedia(repository))
        } finally { db.close() }
    }

    @Test fun anActiveSourceOwnsThePerMediaFieldsOverTheOldProviderWideProjection() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            bindSeededRow(db)
            db.releaseDao().upsertMediaProjections(listOf(legacyProjection(42, confirmedThrough = 7).toEntity(null)))
            val sources = Sources(listOf(source(keyA)))
            val policy = Policy(ExtensionProductPolicy())
            val repository = repository(db, policy, sources)

            assertEquals("no active source: the old projection stays", 7,
                presentedForMedia(repository).getValue(42).single().confirmedThroughEpisode)

            policy.selectActiveSource(keyA)
            val owned = presentedForMedia(repository).getValue(42).single()
            assertEquals("the active source's accepted row replaces it (a plan does not confirm)", null,
                owned.confirmedThroughEpisode)
            assertEquals(Installment.Episode(1), owned.nextExpectedInstallment)

            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false)))
            assertTrue("an unusable active source must not fall back to the old projection",
                presentedForMedia(repository).isEmpty())

            policy.selectActiveSource(null)
            assertEquals("deselected again: the old projection returns", 7,
                presentedForMedia(repository).getValue(42).single().confirmedThroughEpisode)
        } finally { db.close() }
    }

    @Test fun aSourceThatCommittedNothingPresentsNothingPerMediaEvenWhenAnotherCommitted() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            bindSeededRow(db)
            val sources = Sources(listOf(source(keyA), source(keyB)))
            assertTrue(presentedForMedia(repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyB)), sources)).isEmpty())
            assertEquals(1, presentedForMedia(repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)).getValue(42).size)
        } finally { db.close() }
    }

    @Test fun aMediaWithoutAnEffectiveBindingKeepsAniListInsteadOfAGuessedMedia() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            assertTrue(presentedForMedia(repository).isEmpty())
            assertTrue(presentedForMedia(repository, setOf(42, 43)).isEmpty())
        } finally { db.close() }
    }

    /** F02 of the independent review, through the real Room flows: a switched-off track leaves every entry point together. */
    @Test fun aTrackTheUserTurnsOffLeavesTheCalendarAndThePerMediaFlowTogetherWithoutDeletingTheRow() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db) // DE_SUB
            bindSeededRow(db)
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
            val repository = repository(db, policy, Sources(listOf(source(keyA))))
            assertEquals(1, presented(repository).size)
            assertEquals(1, presentedForMedia(repository).getValue(42).size)

            fun only(vararg tracks: String) = ExtensionProductPolicy(
                activeReleaseSource = keyA,
                preferences = mapOf(keyA to ExtensionPreferences(enabledTracks = tracks.toSet(), preferredTrackOrder = tracks.toList())),
            )
            policy.policy.value = only("DE_DUB")
            assertTrue("the calendar (and so the widget and the explore rows) no longer shows the switched-off track", presented(repository).isEmpty())
            assertTrue("Home, lists and details do not either", presentedForMedia(repository).isEmpty())

            policy.policy.value = only("DE_SUB")
            assertEquals("switching it on again shows the accepted row, nothing was deleted", 1, presented(repository).size)
            assertEquals(1, presentedForMedia(repository).getValue(42).size)
        } finally { db.close() }
    }

    /**
     * A planned time moves from 13:00 to 14:00 UTC with a later refresh of the same source. The collectors of the per-media
     * and the calendar flow stay open the whole time: both see the new time without being restarted, so no entry point keeps
     * an old copy as its authority.
     */
    @Test fun aChangedPlannedTimeReachesEveryEntryPointThroughTheSameOpenFlows() = runBlocking {
        val db = open()
        val t13 = Instant.parse("2026-09-26T13:00:00Z")
        val t14 = Instant.parse("2026-09-26T14:00:00Z")
        val perMedia = java.util.concurrent.CopyOnWriteArrayList<Instant?>()
        val calendar = java.util.concurrent.CopyOnWriteArrayList<Instant?>()
        val collectors = mutableListOf<kotlinx.coroutines.Job>()
        try {
            seedAcceptedRow(db, cycle = "cycle-13", reportedAt = t13)
            bindSeededRow(db)
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)),
                Sources(listOf(source(keyA))))
            collectors += launch(kotlinx.coroutines.Dispatchers.Default) {
                repository.observeForMedia(null, setOf(42)).collect { perMedia += it[42]?.singleOrNull()?.nextForecastAt }
            }
            collectors += launch(kotlinx.coroutines.Dispatchers.Default) {
                repository.observeCalendar(null, range).collect { calendar += it.singleOrNull()?.forecastAt }
            }
            suspend fun until(what: String, seen: List<Instant?>, expected: Instant) =
                kotlinx.coroutines.withTimeout(10_000) {
                    while (expected !in seen) kotlinx.coroutines.delay(25)
                }.also { assertTrue("$what shows $expected", expected in seen) }
            until("per-media flow", perMedia, t13)
            until("calendar flow", calendar, t13)

            seedAcceptedRow(db, cycle = "cycle-14", baseline = false, reportedAt = t14, at = observedAt.plusSeconds(3_600))
            until("per-media flow", perMedia, t14)
            until("calendar flow", calendar, t14)
            assertEquals("the latest emission of each flow is the new time", t14, perMedia.last())
            assertEquals(t14, calendar.last())
        } finally {
            collectors.forEach { it.cancel() }
            db.close()
        }
    }
}

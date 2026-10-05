package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiSelection
import com.axiel7.anihyou.release.core.api.pendingFor
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CanonicalReleaseState
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseConflict
import com.axiel7.anihyou.release.core.model.ReleaseConflictKind
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Extension First for the per-media consumers, on the accepted rows of the active source. Fixed clock
 * 2026-10-04T12:00:00Z. The inputs contradict each other on purpose: AniList would name episode 12 for tomorrow;
 * the accepted source confirms episode 10, plans episode 11 for today and the user has watched episode 8.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionMediaPresentationsTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val today3pm = Instant.parse("2026-10-04T15:00:00Z")
    private val defaults = ExtensionPreferences()

    private fun identity(
        slug: String, episode: Int, track: LanguageTrack = LanguageTrack.DE_SUB, season: Int = 1,
    ): CanonicalReleaseIdentity {
        val evidence = ReleaseEvidence(
            id = "e-$slug-$episode-$track", sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceUrl = "https://aniworld.to/anime/stream/$slug", sourceHash = "hash", parserVersion = "fixture",
            observedAt = now, sourceReportedAt = null, approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier(slug), sourceSeason = season, navigationSeason = season,
            installment = Installment.Episode(episode), languageTrack = track,
            evidenceType = ReleaseEvidenceType.CONFIRMATION, confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        return requireNotNull(CanonicalReleaseIdentity.from(evidence))
    }

    private fun released(slug: String, episode: Int, track: LanguageTrack = LanguageTrack.DE_SUB, season: Int = 1) =
        row(identity(slug, episode, track, season), ReleasePhase.RELEASED, ReleaseAuthority.ANIWORLD,
            releaseAt = now.minusSeconds(86_400L * (12 - episode)))

    private fun planned(
        slug: String, episode: Int, at: Instant, track: LanguageTrack = LanguageTrack.DE_SUB, season: Int = 1,
        phase: ReleasePhase = ReleasePhase.EXPECTED, conflict: Boolean = false,
    ) = row(identity(slug, episode, track, season), phase, ReleaseAuthority.NONE, forecastAt = at, conflict = conflict)

    private fun row(
        identity: CanonicalReleaseIdentity, underlying: ReleasePhase, authority: ReleaseAuthority,
        releaseAt: Instant? = null, forecastAt: Instant? = null, conflict: Boolean = false,
    ): CanonicalReleaseProjectionEntity {
        val conflicts = if (conflict) listOf(ReleaseConflict("c-${identity.key.hashCode()}",
            ReleaseConflictKind.SCHEDULE_DISAGREEMENT, setOf("e1"), true)) else emptyList()
        val state = CanonicalReleaseState(
            key = identity.key, underlyingPhase = underlying,
            phase = if (conflict) ReleasePhase.CONFLICT else underlying, authority = authority,
            releaseAt = releaseAt, forecastAt = forecastAt, conflicts = conflicts, revision = 3,
            navigationSeasons = setOf(identity.sourceSeason ?: 0), latestCompletedAt = now,
        )
        return ReleaseReconciliationMapper.projection(state, identity.bucketKey, 1)
    }

    private fun binding(slug: String, media: Int, season: Int = 1) = ExternalMappingEntity(
        mappingSubjectKey = "subject-$slug-$season-$media", seriesStableKey = slug, siteSlug = slug,
        subjectType = "SEASON", navigationSeason = season, filmNumber = null, externalProvider = "anilist",
        externalId = media.toString(), mappingSource = "MANUAL", mappingStatus = "ACTIVE", confidence = "EXACT",
        createdAt = now.toString(), validatedAt = now.toString(), staleAt = null, provenance = "fixture", parserVersion = null,
    )

    private fun presented(
        rows: List<CanonicalReleaseProjectionEntity>, mappings: List<ExternalMappingEntity>,
        ids: Set<Int> = setOf(42), preferences: ExtensionPreferences = defaults,
    ) = rows.toExtensionMediaPresentations(mappings, ids, preferences)

    @Test fun confirmedComesFromReleasedRowsAndAPlanNeverConfirms() {
        val rows = listOf(
            released("series-a", 9), released("series-a", 10),
            planned("series-a", 11, today3pm), planned("series-a", 12, today3pm.plusSeconds(86_400)),
        )
        val release = presented(rows, listOf(binding("series-a", 42))).getValue(42).single()
        assertEquals(ReleaseUiAuthority.VALID, release.authority)
        assertEquals(42, release.mediaId)
        assertEquals(10, release.confirmedThroughEpisode)
        assertEquals(listOf(Installment.Episode(9), Installment.Episode(10)), release.confirmedInstallments)
        assertEquals("the nearest plan after the confirmed state, not the later one", Installment.Episode(11),
            release.nextExpectedInstallment)
        assertEquals(today3pm, release.nextForecastAt)
        assertEquals(ReleaseKind.EPISODE, release.stream.releaseKind)
        // Two confirmed episodes are missing for progress 8; the plan for episode 11 adds nothing.
        assertEquals(2, release.pendingFor(8))
        assertEquals(0, release.pendingFor(10))
        assertEquals(0, release.pendingFor(null))
    }

    @Test fun aPlanThatIsNotAfterTheConfirmedStateIsNeverTheNextOne() {
        val rows = listOf(released("series-a", 10), planned("series-a", 10, today3pm), planned("series-a", 9, today3pm))
        val release = presented(rows, listOf(binding("series-a", 42))).getValue(42).single()
        assertNull(release.nextExpectedInstallment)
        assertNull(release.nextForecast)
        assertEquals(10, release.confirmedThroughEpisode)
    }

    @Test fun aRowInConflictIsNeverPresentedAsThePlanAndAnOnlyConflictStreamKeepsAniList() {
        val withOk = presented(
            listOf(released("series-a", 10), planned("series-a", 11, today3pm, conflict = true),
                planned("series-a", 12, today3pm.plusSeconds(3_600))),
            listOf(binding("series-a", 42)),
        ).getValue(42).single()
        assertEquals(Installment.Episode(12), withOk.nextExpectedInstallment)

        val onlyConflict = presented(
            listOf(planned("series-a", 11, today3pm, conflict = true)), listOf(binding("series-a", 42)),
        ).getValue(42).single()
        assertEquals(ReleaseUiAuthority.AMBIGUOUS, onlyConflict.authority)
        assertTrue("an ambiguous stream never replaces AniList", ReleaseUiSelection.authoritative(listOf(onlyConflict)).isEmpty())
        assertEquals(0, onlyConflict.pendingFor(0))
    }

    @Test fun aForecastOnlyRowWithoutNavigationSeasonUsesItsSourceSeasonForTheBinding() {
        val identity = identity("series-f", 3, season = 2)
        val state = CanonicalReleaseState(
            key = identity.key, underlyingPhase = ReleasePhase.EXPECTED, phase = ReleasePhase.EXPECTED,
            authority = ReleaseAuthority.NONE, forecastAt = today3pm, conflicts = emptyList(), revision = 3,
            navigationSeasons = emptySet(), latestCompletedAt = now,
        )
        val rows = listOf(ReleaseReconciliationMapper.projection(state, identity.bucketKey, 1))
        val items = rows.toExtensionCalendarItems(listOf(binding("series-f", 42, season = 2)),
            LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 10))
        assertEquals(listOf(42), items.map { it.mediaId })
        val other = rows.toExtensionCalendarItems(listOf(binding("series-f", 42, season = 3)),
            LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 10))
        assertEquals("another season is another entry", listOf<Int?>(null), other.map { it.mediaId })
    }

    @Test fun anUnmappedOrAmbiguousBindingNeverGuessesAMedia() {
        val rows = listOf(released("series-a", 10))
        assertTrue("no binding at all", presented(rows, emptyList()).isEmpty())
        assertTrue("two different media for one subject", presented(rows,
            listOf(binding("series-a", 42), binding("series-a", 43)), ids = setOf(42, 43)).isEmpty())
        assertTrue("a binding of another series", presented(rows, listOf(binding("series-b", 42))).isEmpty())
        assertTrue("a binding of another season", presented(rows, listOf(binding("series-a", 42, season = 2))).isEmpty())
    }

    @Test fun onlyTheRequestedMediaArePresented() {
        val rows = listOf(released("series-a", 10), released("series-b", 4))
        val mappings = listOf(binding("series-a", 42), binding("series-b", 77))
        assertEquals(setOf(42), presented(rows, mappings, ids = setOf(42)).keys)
        assertEquals(setOf(42, 77), presented(rows, mappings, ids = setOf(42, 77)).keys)
        assertTrue(presented(rows, mappings, ids = emptySet()).isEmpty())
    }

    @Test fun tracksFollowTheSourcePreferencesInOrderAndAreFilteredWhenDisabled() {
        val rows = listOf(
            released("series-a", 10, LanguageTrack.DE_SUB), released("series-a", 8, LanguageTrack.DE_DUB),
        )
        val mappings = listOf(binding("series-a", 42))
        val subFirst = presented(rows, mappings).getValue(42)
        assertEquals(listOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB), subFirst.map { it.stream.languageTrack })
        assertEquals(10, ReleaseUiSelection.effective(subFirst)?.confirmedThroughEpisode)

        val dubFirst = presented(rows, mappings,
            preferences = ExtensionPreferences(preferredTrackOrder = listOf("DE_DUB", "DE_SUB"))).getValue(42)
        assertEquals(listOf(LanguageTrack.DE_DUB, LanguageTrack.DE_SUB), dubFirst.map { it.stream.languageTrack })
        assertEquals("SUB decides counts even when navigation prefers DUB", 10, ReleaseUiSelection.effective(dubFirst)?.confirmedThroughEpisode)

        val onlyDub = presented(rows, mappings, preferences = ExtensionPreferences(enabledTracks = setOf("DE_DUB"),
            preferredTrackOrder = listOf("DE_DUB"))).getValue(42)
        assertEquals(listOf(LanguageTrack.DE_DUB), onlyDub.map { it.stream.languageTrack })
        assertNull("DUB alone never replaces canonical episode state", ReleaseUiSelection.effective(onlyDub))
    }

    @Test fun separateSeasonsOfOneSeriesMapToTheirOwnMedia() {
        val rows = listOf(released("series-a", 10, season = 1), released("series-a", 3, season = 2))
        val mappings = listOf(binding("series-a", 42, season = 1), binding("series-a", 43, season = 2))
        val result = presented(rows, mappings, ids = setOf(42, 43))
        assertEquals(10, result.getValue(42).single().confirmedThroughEpisode)
        assertEquals(3, result.getValue(43).single().confirmedThroughEpisode)
        assertNotNull(result.getValue(43).single().stream.sourceSeason)
    }

    @Test fun aCorruptRowIsSkippedAndNeverBreaksTheOtherRows() {
        val good = released("series-a", 10)
        val corrupt = good.copy(projectionKey = "partial-v1:x", underlyingPhase = "NOT_A_PHASE")
        val result = presented(listOf(corrupt, good), listOf(binding("series-a", 42)))
        assertEquals(10, result.getValue(42).single().confirmedThroughEpisode)
    }

    /** The linear scan the lookup replaced; it stays here only as the reference for equivalence. */
    private fun linearAniListId(
        identity: CanonicalReleaseIdentity, navigationSeasons: Set<Int>, mappings: List<ExternalMappingEntity>,
    ): Int? {
        val slug = identity.seriesPath.removePrefix("/anime/stream/")
        val candidates = mappings.filter { row ->
            row.siteSlug == slug && when (val installment = identity.installment) {
                is Installment.Episode ->
                    row.subjectType == "SEASON" && row.navigationSeason != null && row.navigationSeason in navigationSeasons
                is Installment.Film -> row.subjectType == "FILM" && row.filmNumber == installment.number
                is Installment.Special -> false
            }
        }
        return candidates.mapNotNull { it.externalId?.toIntOrNull()?.takeIf { id -> id > 0 } }.distinct().singleOrNull()
    }

    private fun medianMillis(runs: Int = 5, block: () -> Unit): Double {
        block() // warm-up, not counted
        val samples = DoubleArray(runs) {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }
        return samples.sorted()[runs / 2]
    }

    private val week = LocalDate.parse("2026-10-01")..LocalDate.parse("2026-10-07")

    private fun calendar(
        rows: List<CanonicalReleaseProjectionEntity>, mappings: List<ExternalMappingEntity>,
        preferences: ExtensionPreferences = defaults,
    ) = rows.toExtensionCalendarItems(mappings, week, preferences)

    /** A released episode whose publication time two observations disagree on: the monotonic contract keeps it RELEASED. */
    private fun releasedWithAnOpenConflict(slug: String, episode: Int): CanonicalReleaseProjectionEntity {
        val identity = identity(slug, episode)
        val state = CanonicalReleaseState(
            key = identity.key, underlyingPhase = ReleasePhase.RELEASED, phase = ReleasePhase.RELEASED,
            authority = ReleaseAuthority.ANIWORLD, releaseAt = now.minusSeconds(3_600),
            conflicts = listOf(ReleaseConflict("c-publication-$episode", ReleaseConflictKind.PUBLICATION_TIME_DISAGREEMENT,
                setOf("e1", "e2"), true)),
            revision = 3, navigationSeasons = setOf(1), latestCompletedAt = now,
        )
        return ReleaseReconciliationMapper.projection(state, identity.bucketKey, 1)
    }

    /** F02 of the independent review: a track switched off in the settings must leave every entry point, not only some. */
    @Test fun aTrackTheUserTurnedOffIsPresentedByNeitherTheCalendarNorThePerMediaFold() {
        val rows = listOf(
            released("series-a", 10, LanguageTrack.DE_SUB), planned("series-a", 11, today3pm, LanguageTrack.DE_SUB),
            released("series-a", 10, LanguageTrack.DE_DUB), planned("series-a", 11, today3pm, LanguageTrack.DE_DUB),
        )
        val mappings = listOf(binding("series-a", 42))
        fun tracksOf(preferences: ExtensionPreferences) = Pair(
            presented(rows, mappings, preferences = preferences)[42].orEmpty().map { it.stream.languageTrack }.toSet(),
            calendar(rows, mappings, preferences).map { it.stream.languageTrack }.toSet(),
        )
        val both = setOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB)
        assertEquals(Pair(both, both), tracksOf(defaults))
        val dubOnly = ExtensionPreferences(enabledTracks = setOf("DE_DUB"), preferredTrackOrder = listOf("DE_DUB"))
        assertEquals(Pair(setOf(LanguageTrack.DE_DUB), setOf(LanguageTrack.DE_DUB)), tracksOf(dubOnly))
        val subOnly = ExtensionPreferences(enabledTracks = setOf("DE_SUB"), preferredTrackOrder = listOf("DE_SUB"))
        assertEquals(Pair(setOf(LanguageTrack.DE_SUB), setOf(LanguageTrack.DE_SUB)), tracksOf(subOnly))
        val none = ExtensionPreferences(enabledTracks = emptySet(), preferredTrackOrder = emptyList())
        assertEquals(Pair(emptySet<LanguageTrack>(), emptySet<LanguageTrack>()), tracksOf(none))
    }

    /** F03 of the independent review: one released episode with an open conflict has one authority in every entry point. */
    @Test fun aReleasedEpisodeWithAnOpenConflictHasTheSameAuthorityInTheCalendarAndThePerMediaFold() {
        val rows = listOf(releasedWithAnOpenConflict("series-a", 10))
        val mappings = listOf(binding("series-a", 42))
        val perMedia = presented(rows, mappings).getValue(42).single()
        val inCalendar = calendar(rows, mappings).single()
        assertEquals("the released episode stays valid (ReleaseConflictPolicy.effectivePhase is monotonic)",
            ReleaseUiAuthority.VALID, perMedia.authority)
        assertEquals(perMedia.authority, inCalendar.authority)
        assertTrue(inCalendar.confirmed)
        assertEquals(10, perMedia.confirmedThroughEpisode)

        // A planned row in conflict is not a fact in either fold.
        val plannedInConflict = listOf(planned("series-a", 11, today3pm, conflict = true))
        assertEquals(ReleaseUiAuthority.AMBIGUOUS, presented(plannedInConflict, mappings).getValue(42).single().authority)
        assertEquals(ReleaseUiAuthority.AMBIGUOUS, calendar(plannedInConflict, mappings).single().authority)
    }

    /**
     * Synthetic scale measurement for the binding lookup of one emission: 100, 1,000 and 10,000 bindings against 1,000
     * accepted rows (500 series, a confirmed and a planned row each). Nothing here asserts a time; the numbers are written
     * to the log (MEASURE lines) and the test fails only when the lookup disagrees with the linear reference.
     */
    @Test fun bindingLookupAgreesWithTheLinearScanAndItsCostIsMeasuredAtScale() {
        val seriesCount = 500
        val rows = (0 until seriesCount).flatMap { index ->
            val slug = "series-%05d".format(index)
            listOf(released(slug, 5), planned(slug, 6, today3pm))
        }
        val identities = rows.map { requireNotNull(CanonicalReleaseIdentity.decode(it.projectionKey)) }
        for (bindingCount in listOf(100, 1_000, 10_000)) {
            // The first 100 bindings belong to the accepted series; the rest are other series of the library.
            val mappings = (0 until bindingCount).map { index ->
                binding(if (index < seriesCount) "series-%05d".format(index) else "library-%05d".format(index), 1_000 + index)
            }
            val lookup = MappingLookup(mappings)
            identities.forEach { identity ->
                assertEquals(linearAniListId(identity, setOf(1), mappings), lookup.aniListId(identity, setOf(1)))
            }
            val linearMs = medianMillis { identities.forEach { linearAniListId(it, setOf(1), mappings) } }
            val indexedMs = medianMillis { val index = MappingLookup(mappings); identities.forEach { index.aniListId(it, setOf(1)) } }
            val ids = (0 until minOf(bindingCount, seriesCount)).map { 1_000 + it }.toSet()
            var presentedMedia = 0
            val builderMs = medianMillis { presentedMedia = rows.toExtensionMediaPresentations(mappings, ids, defaults).size }
            assertEquals("every requested bound media is presented once", ids.size, presentedMedia)
            println("MEASURE bindings=$bindingCount rows=${rows.size} linearLookupMs=%.2f indexedLookupMs=%.2f fullBuilderMs=%.2f"
                .format(linearMs, indexedMs, builderMs))
        }
    }
}

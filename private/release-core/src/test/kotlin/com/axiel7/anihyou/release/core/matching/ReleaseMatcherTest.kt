package com.axiel7.anihyou.release.core.matching

import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMatcherTest {
    private val source = SourceIdentity(
        stream = ReleaseStreamKey(
            providerId = ProviderId("provider"),
            stableSeriesKey = SourceSeriesKey("series"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = com.axiel7.anihyou.release.core.model.LanguageTrack.DE_SUB,
        ),
        installment = com.axiel7.anihyou.release.core.model.Installment.Episode(1),
    )

    @Test
    fun normalizerFoldsAccentsAndSeasonSpellingWithoutLosingPart() {
        val normalized = TitleNormalizer.normalize("Boku no Héroe Academia Season 2 Part 1")
        assertEquals("boku no heroe academia", normalized.base)
        assertEquals(2, normalized.season)
        assertEquals(1, normalized.part)
        assertEquals("boku no heroe academia|season:2|part:1", normalized.canonical)
    }

    @Test
    fun manualMappingWinsEvenWhenCandidateIndexIsEmpty() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "Any title", manualMediaId = 77),
            emptyList(),
        )
        val matched = decision as MatchDecision.Matched
        assertEquals(77, matched.mediaId)
        assertEquals(MatchTier.MANUAL, matched.tier)
    }

    @Test
    fun historicalMappingWinsOverACompetingExactTitle() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "The Same Show", historicalMediaId = 22),
            listOf(candidate(11, "The Same Show"), candidate(22, "The Same Show")),
        )
        val matched = decision as MatchDecision.Matched
        assertEquals(22, matched.mediaId)
        assertEquals(MatchTier.HISTORICAL, matched.tier)
    }

    @Test
    fun equalExactTitlesRemainAmbiguous() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "The Same Show"),
            listOf(candidate(11, "The Same Show"), candidate(22, "The Same Show")),
        )
        assertTrue(decision is MatchDecision.Ambiguous)
    }

    @Test
    fun seasonMismatchIsExcludedAndCorrectSeasonIsSelected() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "The Same Show S2"),
            listOf(
                candidate(11, "The Same Show Season 1"),
                candidate(22, "The Same Show Season 2"),
            ),
        )
        val matched = decision as MatchDecision.Matched
        assertEquals(22, matched.mediaId)
        assertEquals(MatchTier.EXACT_NORMALIZED, matched.tier)
    }

    @Test
    fun explicitFormatGuardRejectsMovieForEpisodeSource() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "The Same Show", format = "TV"),
            listOf(candidate(11, "The Same Show", format = "MOVIE")),
        )
        assertTrue(decision is MatchDecision.Unmapped)
    }

    @Test
    fun aliasAndGuardedFuzzyMatchingAreOrderedAfterExactEvidence() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "Legend of Heroes", aliases = setOf("Eiyuu Densetsu")),
            listOf(
                candidate(11, "Eiyuu Densetsu"),
                candidate(22, "Legendary Hero Stories"),
            ),
        )
        val matched = decision as MatchDecision.Matched
        assertEquals(11, matched.mediaId)
        assertEquals(MatchTier.ALIAS, matched.tier)
    }

    @Test
    fun guardedFuzzyEvidenceCanSelectAUniqueCandidate() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "The Hero League Story"),
            listOf(candidate(11, "The Hero of League Story Saga")),
        )
        val matched = decision as MatchDecision.Matched
        assertEquals(11, matched.mediaId)
        assertEquals(MatchTier.FUZZY, matched.tier)
        assertTrue(matched.score >= 0.78)
    }

    @Test
    fun unrelatedSingleTokenCandidateDoesNotProduceFuzzyWinner() {
        val decision = ReleaseMatcher().match(
            ReleaseMatchRequest(source, "A Very Specific Series"),
            listOf(candidate(11, "Series")),
        )
        assertTrue(decision is MatchDecision.Unmapped)
    }

    private fun candidate(
        mediaId: Int,
        title: String,
        format: String = "TV",
    ) = IdentityCandidate(
        mediaId = mediaId,
        titles = setOf(title),
        format = format,
        startDate = LocalDate.of(2026, 1, 1),
    )
}

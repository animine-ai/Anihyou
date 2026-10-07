package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.extension.ProviderMediaNumbering
import com.axiel7.anihyou.release.data.malsync.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MALSyncEpisodeNumberingTest {
    private val key = ExtensionSelectionKey("source", "extension", "publisher", "provider")
    private val part1 = ProviderMediaNumbering(108632, setOf("Re:Zero kara Hajimeru Isekai Seikatsu 2nd Season"), 13, 39587)
    private val part2 = ProviderMediaNumbering(119661, setOf("Re:Zero kara Hajimeru Isekai Seikatsu 2nd Season Part 2"), 12, 42203,
        listOf(EpisodeNumberingRule(42203, 42203, 39, 1, 12), EpisodeNumberingRule(42203, 42203, 14, 1, 12)))
    private val bound = ExternalMappingEntity("subject", "series", "rezero", "SEASON", 2, null,
        "anilist", "108632", "PERSISTED", "ACTIVE", "EXACT", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z",
        null, "fixture", null)

    @Test fun splitSeasonUsesVerifiedLocalRangeRatherThanFirstGlobalRange() {
        val segments = effectiveEpisodeSegments(key, listOf(bound), emptyList(), listOf(part1, part2))
        val next = segments.single { it.mediaId == part2.mediaId }
        assertEquals(BigDecimal("14"), next.providerEpisode(BigDecimal.ONE))
        assertEquals(BigDecimal("22"), next.providerEpisode(BigDecimal("9")))
        assertEquals(BigDecimal("25"), next.providerEpisode(BigDecimal("12")))
        assertNull(next.providerEpisode(BigDecimal("13")))
        assertEquals(2, next.sourceSeason)
        assertEquals(BigDecimal("9"), next.canonicalEpisode(BigDecimal("22")))
    }
    @Test fun noGuessedPartOffsetWithoutRulesAcceptedAnchorOrMatchingExtent() {
        fun derived(bindings: List<ExternalMappingEntity> = listOf(bound), metadata: List<ProviderMediaNumbering>) =
            effectiveEpisodeSegments(key, bindings, emptyList(), metadata).filter { it.mediaId == part2.mediaId }
        assertTrue(derived(metadata = listOf(part1, part2.copy(episodeRules = emptyList()))).isEmpty())
        assertTrue(derived(metadata = listOf(part2)).isEmpty())
        assertTrue(derived(emptyList(), listOf(part1, part2)).isEmpty())
        assertTrue(derived(metadata = listOf(part1, part2.copy(episodeExtent = 11))).isEmpty())
        assertTrue(derived(metadata = listOf(part1, part2.copy(malId = 777))).isEmpty())
        assertTrue(derived(listOf(bound.copy(staleAt = "2026-10-02T00:00:00Z")), listOf(part1, part2)).isEmpty())
    }
    @Test fun currentManualMappingAlwaysWinsAndParentResetRevokesOnlyDerivedCoordinates() {
        val manual = com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment(key, 119661, "manual-series", 4, 51, 1, 12)
        val segments = effectiveEpisodeSegments(key, listOf(bound), listOf(manual), listOf(part1, part2))
        assertEquals(listOf(manual), segments.filter { it.mediaId == 119661 })
        assertNull(partOverviewBinding(part2, emptyList(), listOf(part1, part2)))
    }
    @Test fun crossEntryRangeCanBeTakenFromPreviousPartButNotUnrelatedMalId() {
        val rule = EpisodeNumberingRule(39587, 42203, 14, 1, 12)
        val segments = effectiveEpisodeSegments(key, listOf(bound), emptyList(),
            listOf(part1.copy(episodeRules = listOf(rule)), part2.copy(episodeRules = emptyList())))
        assertEquals(14, segments.single { it.mediaId == 119661 }.providerFirst)
        val invalid = part2.copy(episodeRules = listOf(rule.copy(fromMalId = 999)))
        assertFalse(effectiveEpisodeSegments(key, listOf(bound), emptyList(), listOf(part1, invalid)).any { it.mediaId == 119661 })
    }
    @Test fun animeHomeDoesNotRequireEpisodeOffset() {
        assertEquals(bound, partOverviewBinding(part2.copy(episodeRules = emptyList()), listOf(bound), listOf(part1, part2)))
        assertNull(partOverviewBinding(part2.copy(titles = setOf("Unrelated Season 2 Part 2")), listOf(bound), listOf(part1, part2)))
    }
    @Test fun ambiguousAnchorIsNeverSelected() {
        val duplicate = part1.copy(mediaId = 999)
        assertNull(partOverviewBinding(part2, listOf(bound, bound.copy(externalId = "999")), listOf(part1, duplicate, part2)))
        assertFalse(effectiveEpisodeSegments(key, listOf(bound), emptyList(), listOf(part1, duplicate, part2)).any { it.mediaId == 119661 })
    }
}

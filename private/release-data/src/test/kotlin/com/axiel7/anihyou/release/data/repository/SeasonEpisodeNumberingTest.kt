package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.ProviderMediaNumbering
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SeasonEpisodeNumberingTest {
    private val key = ExtensionSelectionKey("source", "de.aniworld", "publisher", "aniworld")
    private val now = Instant.parse("2026-10-06T10:00:00Z")
    private fun binding(season: Int = 1, media: Int = 42) = ExternalMapping(
        AniWorldMappingSubject.Season(AniWorldSiteIdentifier("ordinary-show"), season),
        ExternalProvider.ANILIST, media.toString(), MappingSource.PERSISTED, MappingConfidence.HIGH,
        now, now, MappingStatus.ACTIVE).toEntity()
    private fun metadata(title: String = "Ordinary Show", extent: Int = 12) =
        ProviderMediaNumbering(42, setOf(title), extent)

    @Test fun ordinaryAcceptedSeasonUsesBoundedLocalNumbering() {
        val segment = effectiveEpisodeSegments(key, listOf(binding()), emptyList(), listOf(metadata())).single()
        assertEquals(BigDecimal("9"), segment.canonicalEpisode(BigDecimal("9")))
        assertNull(segment.canonicalEpisode(BigDecimal("13")))
        assertEquals(12, segment.count)
    }
    @Test fun secondSeasonStartsAtOneForItsSeparateAniListEntry() {
        val s = effectiveEpisodeSegments(key, listOf(binding(2)), emptyList(),
            listOf(metadata("Ordinary Show Season 2"))).single()
        assertEquals(2, s.sourceSeason)
        assertEquals(BigDecimal.ONE, s.canonicalEpisode(BigDecimal.ONE))
    }
    @Test fun fiveHundredEpisodeEntryStaysOneSeriesWithoutInventingTwentySeasons() {
        val s = effectiveEpisodeSegments(key, listOf(binding()), emptyList(), listOf(metadata(extent = 500))).single()
        assertEquals(1, s.sourceSeason)
        assertEquals(500, s.count)
        assertEquals(BigDecimal("500"), s.providerEpisode(BigDecimal("500")))
        assertTrue(effectiveEpisodeSegments(key, listOf(binding(20)), emptyList(), listOf(metadata(extent = 500))).isEmpty())
    }
    @Test fun partsMultipleBindingsAndConflictingSeasonTitlesAreNotGuessed() {
        assertTrue(effectiveEpisodeSegments(key, listOf(binding()), emptyList(), listOf(metadata("Ordinary Show Part 2"))).isEmpty())
        assertTrue(effectiveEpisodeSegments(key, listOf(binding(), binding(2)), emptyList(), listOf(metadata())).isEmpty())
        assertTrue(effectiveEpisodeSegments(key, listOf(binding(), binding(media = 99)), emptyList(), listOf(metadata())).isEmpty())
    }
    @Test fun manualOffsetAlwaysWinsAndResetRevokesDerivedCoordinates() {
        val manual = ProviderEpisodeSegment(key, 42, "ordinary-show", 1, 13, 1, 12)
        assertEquals(listOf(manual), effectiveEpisodeSegments(key, listOf(binding()), listOf(manual), listOf(metadata())))
        assertTrue(effectiveEpisodeSegments(key, emptyList(), emptyList(), listOf(metadata())).isEmpty())
        assertTrue(effectiveEpisodeSegments(key, listOf(binding(media = 99)), emptyList(), listOf(metadata())).isEmpty())
    }
    @Test fun staleOrUnvalidatedBindingsNeverBecomeEpisodeCoordinates() {
        assertTrue(effectiveEpisodeSegments(key, listOf(binding().copy(staleAt = now.toString())), emptyList(), listOf(metadata())).isEmpty())
        assertTrue(effectiveEpisodeSegments(key, listOf(binding().copy(validatedAt = null)), emptyList(), listOf(metadata())).isEmpty())
        assertTrue(effectiveEpisodeSegments(key, listOf(binding()), emptyList(), emptyList()).isEmpty())
    }
}

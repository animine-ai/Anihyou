package com.axiel7.anihyou.feature.explore.explore.content

import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.*
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ProviderAiringGroupsTest {
    private fun media(id: Int, listed: Boolean = false, adult: Boolean = false, title: String = "Anime $id") =
        mockk<ExploreMedia>(relaxed = true) {
            every { this@mockk.id } returns id
            every { basicMediaDetails.title?.userPreferred } returns title
            every { basicMediaDetails.isAdult } returns adult
            every { mediaListEntry } returns if (listed) mockk(relaxed = true) else null
        }
    private fun row(id: Int?) = ReleaseUiCalendarItem(
        mediaId = id, stream = ReleaseStreamKey(ProviderId("test-provider"), SourceSeriesKey("series-$id"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB), installment = Installment.Episode(23),
        forecastAt = Instant.parse("2026-10-06T15:10:00Z"), confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = LocalDate.of(2026, 10, 6),
        sourceRoot = "https://example.test", revision = 1,
    )

    @Test fun allTenEnrichedCardsKeepTheirTrueTitlesAndSourceEpisodeAndTime() {
        val rows = (1..10).map(::row)
        val metadata = (1..10).associateWith { media(it) }
        val groups = providerAiringGroups(rows, metadata, false, false)
        assertEquals(10, groups.size)
        assertEquals((1..10).map { "Anime $it" }, groups.map { it.media.basicMediaDetails.title?.userPreferred })
        assertEquals(rows, groups.flatMap { it.rows })
    }
    @Test fun unloadedOrUnboundEventsNeverBecomeGenericAnimeCards() {
        assertTrue(providerAiringGroups(listOf(row(7), row(null)), emptyMap(), false, false).isEmpty())
    }
    @Test fun bothFiltersRequireLoadedMembershipAndUseEnrichedMedia() {
        val metadata = mapOf(7 to media(7, true), 8 to media(8))
        val rows = listOf(row(7), row(8), row(9), row(null))
        assertEquals(listOf(7), providerAiringGroups(rows, metadata, true, false).map { it.media.id })
        assertEquals(listOf(8), providerAiringGroups(rows, metadata, false, false).map { it.media.id })
    }
    @Test fun adultPreferenceIsAppliedToSourceCards() {
        val metadata = mapOf(7 to media(7, adult = true))
        assertTrue(providerAiringGroups(listOf(row(7)), metadata, false, false).isEmpty())
        assertEquals(1, providerAiringGroups(listOf(row(7)), metadata, false, true).size)
    }
    @Test fun revisionDuplicatesDisappearButSubDubRemainDistinct() {
        val sub = row(7)
        val dub = sub.copy(stream = sub.stream.copy(languageTrack = LanguageTrack.DE_DUB))
        val groups = providerAiringGroups(listOf(sub, sub.copy(revision = 2), dub), mapOf(7 to media(7)), false, false)
        assertEquals(listOf(sub.eventKey, dub.eventKey), groups.map { it.rows.single().eventKey })
    }
    @Test fun missingTitleCannotBeRenderedAsProviderPublication() {
        assertTrue(providerAiringGroups(listOf(row(7)), mapOf(7 to media(7, title = "")), false, false).isEmpty())
    }
}

package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.*
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadEpisodeLinksTest {
    private fun observation(series: String, season: Int, episode: String) = ProviderObservationV1(
        1, ExtensionId.parse("de.aniworld"), ProviderId.parse("aniworld"), "calendar",
        SourceRole.CALENDAR, series, "Title", season, season,
        InstallmentV1(ObservationInstallmentKind.EPISODE, episode), ObservationTrack.DE_SUB,
        ObservationClaimKind.FORECAST, null, null, null, Instant.EPOCH.toString(),
        false, ObservationScheduleMarker.NONE, null, "https://aniworld.to/animekalender",
        "d".repeat(64), emptyList(),
    )

    private val page = """
        <a href="/anime/stream/bleach/staffel-17/episode-3">Bleach</a>
        <a href="/anime/stream/bleach/staffel-17/episode-3">Bleach again</a>
        <a href="/anime/stream/one-piece/filme/film-1">Film</a>
        <a href="/anime/stream/overgeared/staffel-1/episode-4">Overgeared</a>
        <a href="/other/path">Not an episode link</a>
    """.trimIndent()

    @Test fun onlyTheLinksWithoutAnObservationAreNamed() {
        val read = listOf(observation("bleach", 17, "3"))
        assertEquals(
            listOf("/anime/stream/one-piece/filme/film-1", "/anime/stream/overgeared/staffel-1/episode-4"),
            unreadEpisodeLinks(page, read),
        )
    }

    @Test fun aLinkIsCoveredOnlyByTheSameSeasonAndEpisode() {
        val read = listOf(observation("overgeared", 2, "4"), observation("bleach", 17, "3"))
        assertEquals(true, "/anime/stream/overgeared/staffel-1/episode-4" in unreadEpisodeLinks(page, read))
    }

    @Test fun theListIsBoundedAndKeepsThePageOrder() {
        val many = (1..20).joinToString("\n") { """<a href="/anime/stream/s$it/staffel-1/episode-1">x</a>""" }
        assertEquals((1..8).map { "/anime/stream/s$it/staffel-1/episode-1" }, unreadEpisodeLinks(many, emptyList()))
    }

    @Test fun aPageWithoutLinksHasNoMissedOnes() {
        assertEquals(emptyList<String>(), unreadEpisodeLinks("<p>nothing</p>", emptyList()))
    }
}

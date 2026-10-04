package com.axiel7.anihyou.release.data.aniworld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldCanonicalRouteParserTest {
    @Test
    fun conflictingDataKeyAndHrefFailClosed() {
        val result = AniWorldCardRouteResolver.resolve(
            sourceUrl = "https://aniworld.to/neue-episoden",
            sourceKey = "alpha",
            href = "/anime/stream/beta/staffel-1/episode-7",
        )

        val failure = result as? AniWorldCardRouteResult.Failure
            ?: error("expected conflicting card route to fail but got $result")
        assertTrue(failure.diagnostic.contains("conflicts"))
    }

    @Test
    fun filmRouteIsSeparateFromSeriesAndEpisodeRoutes() {
        val series = route("https://www.aniworld.to/anime/stream/alpha")
        val films = route("https://aniworld.to/anime/stream/alpha/filme")
        val filmTwo = route("https://aniworld.to/anime/stream/alpha/filme/film-2")
        val episode = route("https://aniworld.to/anime/stream/alpha/staffel-1/episode-2")

        assertEquals(AniWorldCanonicalRouteKind.SERIES, series.kind)
        assertEquals(AniWorldCanonicalRouteKind.FILMS_OVERVIEW, films.kind)
        assertEquals(AniWorldCanonicalRouteKind.FILM, filmTwo.kind)
        assertEquals(AniWorldCanonicalRouteKind.EPISODE, episode.kind)
        assertEquals("alpha", filmTwo.slug)
        assertEquals("alpha", episode.slug)
    }

    private fun route(url: String): AniWorldCanonicalRoute =
        (AniWorldCanonicalRouteParser.parse(url) as? AniWorldCanonicalRouteResult.Success)
            ?.route
            ?: error("expected valid AniWorld route: $url")
}

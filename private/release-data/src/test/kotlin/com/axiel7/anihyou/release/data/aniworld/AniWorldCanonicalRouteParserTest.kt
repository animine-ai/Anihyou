package com.axiel7.anihyou.release.data.aniworld

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldCanonicalRouteParserTest {
    @Test
    fun realProductionLinksWithoutSyntheticSourceKeysKeepTheirSeriesSlugs() {
        val html = """
            <html><body><main><h1>Neue Episoden</h1>
              <article>
                <a href="/anime/stream/alpha/staffel-1/episode-7">
                  <img class="flag" data-track="DE_SUB">
                </a>
              </article>
              <article>
                <a href="/anime/stream/beta/staffel-1/episode-7">
                  <img class="flag" data-track="DE_SUB">
                </a>
              </article>
            </main></body></html>
        """.trimIndent()

        val result = AniWorldEvidenceParser().parse(
            html = html,
            sourceUrl = "https://aniworld.to/neue-episoden",
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = Instant.parse("2026-09-22T12:00:00Z"),
        )

        val success = result as? AniWorldEvidenceParseResult.Success
            ?: error("expected successful evidence parse but got $result")
        assertEquals(setOf("alpha", "beta"), success.observations.map { it.stream.stableSeriesKey.value }.toSet())
        assertTrue(success.observations.none { it.stream.stableSeriesKey.value == "stream" })
        assertEquals(
            setOf(
                "https://aniworld.to/anime/stream/alpha",
                "https://aniworld.to/anime/stream/beta",
            ),
            success.observations.map { it.sourceRoot }.toSet(),
        )
    }

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

package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.Installment
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldIdentityResolverTest {
    private val now = Instant.parse("2026-09-21T12:00:00Z")
    private val resolver = AniWorldIdentityResolver(
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun realSeriesRouteUsesSlugWithoutTheStreamRoutingPrefix() {
        val result = success("https://aniworld.to/anime/stream/alpha")

        assertEquals("aniworld", result.series.provider)
        assertEquals("alpha", result.series.slug)
        assertEquals("alpha", result.series.canonicalId)
        assertEquals("/anime/stream/alpha", result.series.canonicalSeriesPath)
        assertEquals("https://aniworld.to/anime/stream/alpha", result.series.canonicalUrl)
        assertNotEquals("stream", result.series.canonicalId)
    }

    @Test
    fun seasonOverviewSharesSeriesIdentityWithRealSeriesRoute() {
        val overview = success("https://aniworld.to/anime/stream/alpha")
        val season = success("https://aniworld.to/anime/stream/alpha/staffel-1")

        assertEquals(overview.series.stableKey, season.series.stableKey)
        assertEquals("alpha", season.series.canonicalId)
        assertEquals(1, season.season)
        assertNull(season.installment)
    }

    @Test
    fun differentRealSeriesRemainDistinctEvenWithTheSameEpisodeAndSeason() {
        val alpha = success("https://aniworld.to/anime/stream/alpha/staffel-1/episode-7")
        val beta = success("https://aniworld.to/anime/stream/beta/staffel-1/episode-7")

        assertNotEquals(alpha.series.stableKey, beta.series.stableKey)
        assertNotEquals(alpha.installment!!.stableKey, beta.installment!!.stableKey)
        assertEquals("alpha", alpha.series.canonicalId)
        assertEquals("beta", beta.series.canonicalId)
    }

    @Test
    fun differentEpisodesRemainDistinctInstallmentIdentities() {
        val first = success("https://aniworld.to/anime/stream/alpha/staffel-2/episode-7")
        val second = success("https://aniworld.to/anime/stream/alpha/staffel-2/episode-8")
        val firstInstallment = first.installment!!
        val secondInstallment = second.installment!!

        assertEquals(first.series.stableKey, second.series.stableKey)
        assertNotEquals(firstInstallment.stableKey, secondInstallment.stableKey)
        assertEquals(Installment.Episode(7), firstInstallment.installment)
        assertEquals(Installment.Episode(8), secondInstallment.installment)
        assertEquals(2, firstInstallment.sourceSeason)
        assertEquals(2, secondInstallment.sourceSeason)
    }

    @Test
    fun staffelAndSeasonAliasesShareTheSameInstallmentIdentity() {
        val staffel = success("https://aniworld.to/anime/stream/alpha/staffel-2/episode-4")
        val season = success("https://aniworld.to/anime/stream/alpha/season_2/folge-4")

        assertEquals(staffel.series.stableKey, season.series.stableKey)
        assertEquals(staffel.installment!!.stableKey, season.installment!!.stableKey)
    }

    @Test
    fun filmsOverviewDoesNotInventAnEpisodeAndFilmRouteCreatesAFilmInstallment() {
        val overview = success("https://aniworld.to/anime/stream/alpha/filme")
        val film = success("https://aniworld.to/anime/stream/alpha/filme/film-2")

        assertEquals("alpha", overview.series.canonicalId)
        assertNull(overview.season)
        assertNull(overview.installment)
        assertEquals(Installment.Film(2), film.installment!!.installment)
        assertEquals(film.series.stableKey, overview.series.stableKey)
    }

    @Test
    fun filmAndEpisodeHaveDifferentInstallmentIdentitiesForOneSeries() {
        val film = success("https://aniworld.to/anime/stream/alpha/filme/film-2")
        val episode = success("https://aniworld.to/anime/stream/alpha/staffel-1/episode-2")

        assertEquals(film.series.stableKey, episode.series.stableKey)
        assertNotEquals(film.installment!!.stableKey, episode.installment!!.stableKey)
    }

    @Test
    fun sameTitleDoesNotCollapseDifferentCanonicalSeries() {
        val first = success("https://aniworld.to/anime/stream/naruto", title = "Naruto")
        val second = success("https://aniworld.to/anime/stream/naruto-shippuden", title = "Naruto")

        assertEquals(first.series.normalizedTitle, second.series.normalizedTitle)
        assertNotEquals(first.series.stableKey, second.series.stableKey)
    }

    @Test
    fun unicodeTitleAndSpecialSlugCharactersRemainMetadataOnly() {
        val result = success(
            "https://aniworld.to/anime/stream/maedchen_und-drache",
            title = "Mädchen & Drache: 黄金時代",
        )

        assertEquals("maedchen_und-drache", result.series.slug)
        assertEquals("mädchen drache 黄金時代", result.series.normalizedTitle)
        assertEquals("https://aniworld.to/anime/stream/maedchen_und-drache", result.series.canonicalUrl)
    }

    @Test
    fun queryFragmentAndWwwHostDoNotChangeCanonicalIdentity() {
        val plain = success("https://aniworld.to/anime/stream/alpha/staffel-1")
        val tracked = success("https://www.aniworld.to/anime/stream/alpha/staffel-1?utm_source=test#episode")

        assertEquals(plain.series.stableKey, tracked.series.stableKey)
        assertEquals(plain.series.canonicalUrl, tracked.series.canonicalUrl)
    }

    @Test
    fun malformedRoutesFailClosed() {
        val missingSlug = resolver.resolve("https://aniworld.to/anime/stream")
        val encodedSeparator = resolver.resolve("https://aniworld.to/anime/stream/alpha%2Fbeta")
        val invalidEpisode = resolver.resolve("https://aniworld.to/anime/stream/alpha/episode-nope")
        val unexpectedTail = resolver.resolve("https://aniworld.to/anime/stream/alpha/staffel-1/episode-2/extra")
        val foreignHost = resolver.resolve("https://example.com/anime/stream/alpha")
        val http = resolver.resolve("http://aniworld.to/anime/stream/alpha")

        assertTrue(missingSlug is AniWorldIdentityResolution.Failure)
        assertTrue(encodedSeparator is AniWorldIdentityResolution.Failure)
        assertTrue(invalidEpisode is AniWorldIdentityResolution.Failure)
        assertTrue(unexpectedTail is AniWorldIdentityResolution.Failure)
        assertTrue(foreignHost is AniWorldIdentityResolution.Failure)
        assertTrue(http is AniWorldIdentityResolution.Failure)
    }

    @Test
    fun legacySourceKeyInputIsNormalizedToTheRealCanonicalRoute() {
        val result = AniWorldCanonicalRouteParser.parseSourceKey("alpha")

        val route = (result as AniWorldCanonicalRouteResult.Success).route
        assertEquals("alpha", route.slug)
        assertEquals("/anime/stream/alpha", route.canonicalSeriesPath)
    }

    private fun success(url: String, title: String? = null): AniWorldIdentityResolution.Success {
        val result = resolver.resolve(sourceUrl = url, normalizedTitle = title)
        return result as? AniWorldIdentityResolution.Success
            ?: error("expected identity success but got $result")
    }
}

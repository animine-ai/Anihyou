package com.axiel7.anihyou.release.core

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldInstallmentIdentity
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubjectFactory
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubjectResolution
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.Installment
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldMappingSubjectTest {
    private val now = Instant.parse("2026-09-21T12:00:00Z")
    private val site = AniWorldSiteIdentifier(
        slug = "serie-mit-ä",
        normalizedTitle = "serie mit ä",
        sourceType = AniWorldIdentitySourceType.PAGE,
        firstSeenAt = now,
        lastValidatedAt = now,
    )

    @Test
    fun episodesWithinOneSeasonShareAStableSubject() {
        val first = subject(Installment.Episode(1), navigationSeason = 1)
        val seventh = subject(Installment.Episode(7), navigationSeason = 1)

        assertEquals(first, seventh)
        assertEquals("aniworld:/anime/stream/serie-mit-ä/season:1", first.stableKey)
    }

    @Test
    fun seasonsAndFilmsAreSeparateSubjects() {
        val seasonOne = AniWorldMappingSubjectFactory.season(site, 1)
        val seasonTwo = AniWorldMappingSubjectFactory.season(site, 2)
        val filmOne = AniWorldMappingSubjectFactory.film(site, 1)
        val filmTwo = AniWorldMappingSubjectFactory.film(site, 2)

        assertNotEquals(seasonOne, seasonTwo)
        assertNotEquals(filmOne, filmTwo)
        assertNotEquals(seasonOne, filmOne)
        assertEquals("aniworld:/anime/stream/serie-mit-ä/film:1", filmOne.stableKey)
    }

    @Test
    fun unknownSeasonAndUnnumberedFilmRemainUnmapped() {
        val unknownSeason = subjectResolution(Installment.Episode(1), navigationSeason = null)
        val unnumberedFilm = subjectResolution(Installment.Film(), navigationSeason = null)

        assertTrue(unknownSeason is AniWorldMappingSubjectResolution.Unmapped)
        assertTrue(unnumberedFilm is AniWorldMappingSubjectResolution.Unmapped)
    }

    @Test
    fun sameTitleDoesNotCollapseDistinctSiteIdentities() {
        val otherSite = site.copy(
            slug = "serie-mit-a",
            canonicalSeriesPath = "/anime/stream/serie-mit-a",
            canonicalId = "serie-mit-a",
            canonicalUrl = "https://aniworld.to/anime/stream/serie-mit-a",
        )

        assertNotEquals(site, otherSite)
        assertNotEquals(
            AniWorldMappingSubjectFactory.season(site, 1),
            AniWorldMappingSubjectFactory.season(otherSite, 1),
        )
    }

    private fun subject(
        installment: Installment,
        navigationSeason: Int?,
    ): AniWorldMappingSubject = when (val resolution = subjectResolution(installment, navigationSeason)) {
        is AniWorldMappingSubjectResolution.Resolved -> resolution.subject
        is AniWorldMappingSubjectResolution.Unmapped -> error(resolution.diagnostic)
    }

    private fun subjectResolution(
        installment: Installment,
        navigationSeason: Int?,
    ): AniWorldMappingSubjectResolution =
        AniWorldMappingSubjectFactory.from(
            AniWorldInstallmentIdentity(
                series = site,
                sourceSeason = navigationSeason,
                navigationSeason = navigationSeason,
                installment = installment,
            ),
        )
}

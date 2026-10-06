package com.axiel7.anihyou.release.core.projection

import com.axiel7.anihyou.release.core.model.AniListFallbackPresentation
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePresentationTest {
    private val observedAt = Instant.parse("2026-09-11T00:00:00Z")

    @Test
    fun invalidProviderProjectionUsesAniListFallbackAsAnAtomicAlternative() {
        val fallback = AniListFallbackPresentation(
            releasedEpisode = 10,
            nextAiringEpisode = 11,
            nextAiringAt = observedAt,
        )
        val providerProjection = com.axiel7.anihyou.release.core.model.MediaReleaseProjection(
            mediaId = 42,
            stream = stream(),
            authority = AuthorityStatus.AMBIGUOUS,
            confirmedThroughEpisode = 9,
            confirmedInstallments = emptyList(),
            nextForecast = null,
            pendingCount = 0,
            freshness = freshness(FreshnessStatus.FRESH),
            mapping = mapping(MappingConfidence.AMBIGUOUS),
            sourceRoot = "https://provider.example/anime/series",
            revision = 1,
        )

        val selected = ReleasePresentationSelector.select(providerProjection, fallback)

        assertTrue(selected is ReleasePresentation.AniListFallback)
        assertEquals(fallback, (selected as ReleasePresentation.AniListFallback).value)
    }

    @Test
    fun missingProviderAndFallbackIsUnavailable() {
        assertEquals(ReleasePresentation.Unavailable, ReleasePresentationSelector.select(null, null))
    }

    private fun stream(): ReleaseStreamKey = ReleaseStreamKey(
        providerId = ProviderId("provider"),
        stableSeriesKey = SourceSeriesKey("series-1"),
        releaseKind = ReleaseKind.EPISODE,
        sourceSeason = 2026,
        languageTrack = LanguageTrack.DE_SUB,
    )

    private fun mapping(confidence: MappingConfidence): ReleaseMapping = ReleaseMapping(
        mediaId = 42,
        confidence = confidence,
        score = null,
        runnerUpMargin = null,
        evidence = "presentation-test",
        matcherVersion = "test-v1",
        origin = MappingOrigin.AUTO,
    )

    private fun freshness(status: FreshnessStatus): Freshness = Freshness(
        status = status,
        lastAttemptAt = observedAt,
        lastSuccessAt = observedAt,
        observedAt = observedAt,
        parserVersion = "parser-v1",
        sourceHash = "hash",
    )
}

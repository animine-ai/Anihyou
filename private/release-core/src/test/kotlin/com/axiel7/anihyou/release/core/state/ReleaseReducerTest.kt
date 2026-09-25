package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.AniListFallbackPresentation
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseReducerTest {
    private val observedAt = Instant.parse("2026-09-11T00:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun providerProjectionIsPoisonSafeAndDoesNotMixAniListFields() {
        val stream = stream()
        val result = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                confirmations = listOf(confirmation(stream, Installment.Episode(9))),
                forecasts = listOf(forecast(stream, Installment.Episode(10), daysFromNow = 5)),
            ),
            accountProgress = 4,
        )

        val projection = (result as ReductionResult.Applied).projection
        assertEquals(AuthorityStatus.VALID, projection.authority)
        assertEquals(9, projection.confirmedThroughEpisode)
        assertEquals(5, projection.pendingCount)
        assertNotNull(projection.nextForecast)
        assertEquals(10, projection.nextForecast!!.identity.installment.wholeEpisodeNumber)
        assertEquals(observedAt.plusSeconds(5 * DAY_SECONDS), projection.nextForecast.forecastAt)

        val aniListFallback = AniListFallbackPresentation(
            releasedEpisode = 10,
            nextAiringEpisode = 11,
            nextAiringAt = observedAt.plusSeconds(6 * DAY_SECONDS),
        )
        val selected = com.axiel7.anihyou.release.core.projection.ReleasePresentationSelector.select(
            provider = projection,
            aniListFallback = aniListFallback,
        )
        assertTrue(selected is com.axiel7.anihyou.release.core.projection.ReleasePresentation.Provider)
        val provider = (selected as com.axiel7.anihyou.release.core.projection.ReleasePresentation.Provider).value
        assertEquals(Installment.Episode(9), provider.confirmedInstallments.single())
        assertEquals(10, provider.nextForecast!!.identity.installment.wholeEpisodeNumber)
        assertEquals(observedAt.plusSeconds(5 * DAY_SECONDS), provider.nextForecast.forecastAt)
        assertEquals(5, provider.confirmedPending)
        assertFalse(provider.nextForecast.forecastAt == aniListFallback.nextAiringAt)
    }

    @Test
    fun forecastNeverAdvancesConfirmedThroughOrPending() {
        val stream = stream()
        val result = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                forecasts = listOf(forecast(stream, Installment.Episode(10), daysFromNow = 5)),
            ),
            accountProgress = 0,
        )

        val projection = (result as ReductionResult.Applied).projection
        assertNull(projection.confirmedThroughEpisode)
        assertEquals(0, projection.pendingCount)
        assertEquals(10, projection.nextForecast!!.identity.installment.wholeEpisodeNumber)
    }

    @Test
    fun tracksAreIndependentAndRemainInTheStreamIdentity() {
        val subStream = stream(LanguageTrack.DE_SUB)
        val dubStream = stream(LanguageTrack.DE_DUB)
        val sub = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = subStream,
                confirmations = listOf(confirmation(subStream, Installment.Episode(9))),
            ),
            accountProgress = 0,
        )
        val dub = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = dubStream,
                confirmations = listOf(confirmation(dubStream, Installment.Episode(3))),
            ),
            accountProgress = 0,
        )

        val subProjection = (sub as ReductionResult.Applied).projection
        val dubProjection = (dub as ReductionResult.Applied).projection
        assertEquals(LanguageTrack.DE_SUB, subProjection.stream.languageTrack)
        assertEquals(LanguageTrack.DE_DUB, dubProjection.stream.languageTrack)
        assertEquals(9, subProjection.confirmedThroughEpisode)
        assertEquals(3, dubProjection.confirmedThroughEpisode)
        assertFalse(subProjection.stream.stableKey == dubProjection.stream.stableKey)
    }

    @Test
    fun confirmationHistoryRemainsMonotonicAcrossLowerSnapshot() {
        val stream = stream()
        val first = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                confirmations = listOf(confirmation(stream, Installment.Episode(9))),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied
        val second = ReleaseReducer.reduce(
            previous = first.state,
            snapshot = snapshot(
                stream = stream,
                confirmations = listOf(confirmation(stream, Installment.Episode(8))),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied

        assertEquals(9, second.projection.confirmedThroughEpisode)
        assertTrue(second.state.confirmations.any { it.identity.installment == Installment.Episode(9) })
        assertTrue(second.state.confirmations.any { it.identity.installment == Installment.Episode(8) })
    }

    @Test
    fun sourceDisappearanceKeepsLastGoodHistoryButFailsClosed() {
        val stream = stream()
        val first = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                confirmations = listOf(confirmation(stream, Installment.Episode(9))),
            ),
            accountProgress = 4,
        ) as ReductionResult.Applied
        val second = ReleaseReducer.reduce(
            previous = first.state,
            snapshot = snapshot(
                stream = stream,
                confirmations = emptyList(),
                forecasts = emptyList(),
                freshness = freshness(FreshnessStatus.STALE),
                mapping = null,
                sourcePresent = false,
                sourceRoot = null,
            ),
            accountProgress = 4,
        ) as ReductionResult.Applied

        assertEquals(9, second.projection.confirmedThroughEpisode)
        assertEquals(0, second.projection.pendingCount)
        assertEquals(AuthorityStatus.STALE, second.projection.authority)
        assertNull(second.projection.nextForecast)
        assertEquals("https://provider.example/anime/series", second.projection.sourceRoot)
    }

    @Test
    fun ambiguousAndUnmappedMappingsFailClosed() {
        val stream = stream()
        val ambiguous = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(stream = stream, mapping = mapping(MappingConfidence.AMBIGUOUS)),
            accountProgress = 0,
        ) as ReductionResult.Applied
        val unmapped = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(stream = stream, mapping = mapping(MappingConfidence.NONE)),
            accountProgress = 0,
        ) as ReductionResult.Applied

        assertEquals(AuthorityStatus.AMBIGUOUS, ambiguous.projection.authority)
        assertEquals(0, ambiguous.projection.pendingCount)
        assertEquals(AuthorityStatus.UNMAPPED, unmapped.projection.authority)
        assertEquals(0, unmapped.projection.pendingCount)
    }

    @Test
    fun manualMappingCannotBeOverwrittenByAutomaticMapping() {
        val stream = stream()
        val manual = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                mapping = mapping(mediaId = 7, origin = MappingOrigin.MANUAL),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied
        val automatic = ReleaseReducer.reduce(
            previous = manual.state,
            snapshot = snapshot(
                stream = stream,
                mapping = mapping(mediaId = 42, origin = MappingOrigin.AUTO),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied

        assertEquals(7, automatic.state.mapping!!.mediaId)
        assertEquals(MappingOrigin.MANUAL, automatic.state.mapping.origin)
    }

    @Test
    fun incompatibleInstallmentRejectsAndMarksAuthorityError() {
        val stream = stream(kind = ReleaseKind.MOVIE)
        val invalidIdentity = SourceIdentity(stream, Installment.Episode(1))
        val result = ReleaseReducer.reduce(
            previous = null,
            snapshot = ReleaseSnapshot(
                stream = stream,
                confirmations = listOf(
                    Confirmation(invalidIdentity, ConfirmationEvidenceKind.CURRENT_PAGE_EXPLICIT_MARKER, observedAt),
                ),
                forecasts = emptyList(),
                freshness = freshness(),
                mapping = mapping(),
                observedAt = observedAt,
            ),
            accountProgress = 0,
        )

        assertTrue(result is ReductionResult.Rejected)
        assertEquals(AuthorityStatus.ERROR, result.projection.authority)
        assertEquals(0, result.projection.pendingCount)
    }

    @Test
    fun fractionalEpisodeDoesNotCreateWholeEpisodePending() {
        val stream = stream()
        val result = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                confirmations = listOf(confirmation(stream, Installment.Episode(10, fraction = 5))),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied

        assertNull(result.projection.confirmedThroughEpisode)
        assertEquals(0, result.projection.pendingCount)
        assertEquals(1, result.projection.confirmedInstallments.size)
        assertEquals("episode:10.5", result.projection.confirmedInstallments.single().stableKey)
    }

    @Test
    fun staleRefreshPreservesLastGoodParserAndSourceHash() {
        val stream = stream()
        val first = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(
                stream = stream,
                freshness = freshness(),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied
        val stale = ReleaseReducer.reduce(
            previous = first.state,
            snapshot = snapshot(
                stream = stream,
                freshness = Freshness(
                    status = FreshnessStatus.STALE,
                    lastAttemptAt = observedAt.plusSeconds(1),
                    lastSuccessAt = null,
                    observedAt = observedAt.plusSeconds(1),
                    parserVersion = null,
                    sourceHash = null,
                    diagnostic = "provider unavailable",
                ),
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied

        assertEquals(first.state.freshness.lastSuccessAt, stale.state.freshness.lastSuccessAt)
        assertEquals(first.state.freshness.parserVersion, stale.state.freshness.parserVersion)
        assertEquals(first.state.freshness.sourceHash, stale.state.freshness.sourceHash)
        assertEquals(FreshnessStatus.STALE, stale.state.freshness.status)
    }

    @Test
    fun latestObservedForecastWinsForTheSameInstallment() {
        val stream = stream()
        val firstForecast = forecast(stream, Installment.Episode(10), daysFromNow = 5)
        val first = ReleaseReducer.reduce(
            previous = null,
            snapshot = snapshot(stream = stream, forecasts = listOf(firstForecast)),
            accountProgress = 0,
        ) as ReductionResult.Applied
        val laterForecast = firstForecast.copy(
            forecastAt = observedAt.plusSeconds(6 * DAY_SECONDS),
            observedAt = observedAt.plusSeconds(1),
        )
        val second = ReleaseReducer.reduce(
            previous = first.state,
            snapshot = snapshot(stream = stream, forecasts = listOf(laterForecast)),
            accountProgress = 0,
        ) as ReductionResult.Applied

        assertEquals(laterForecast.forecastAt, second.projection.nextForecast!!.forecastAt)
        assertEquals(laterForecast.observedAt, second.state.forecasts.single().observedAt)
    }

    private fun stream(
        track: LanguageTrack = LanguageTrack.DE_SUB,
        kind: ReleaseKind = ReleaseKind.EPISODE,
    ): ReleaseStreamKey = ReleaseStreamKey(
        providerId = ProviderId("provider"),
        stableSeriesKey = SourceSeriesKey("series-1"),
        releaseKind = kind,
        sourceSeason = 2026,
        languageTrack = track,
    )

    private fun mapping(
        confidence: MappingConfidence = MappingConfidence.HIGH,
        mediaId: Int = 42,
        origin: MappingOrigin = MappingOrigin.AUTO,
    ): ReleaseMapping = ReleaseMapping(
        mediaId = mediaId,
        confidence = confidence,
        score = 0.98,
        runnerUpMargin = 0.20,
        evidence = "frozen-authority-test",
        matcherVersion = "test-v1",
        origin = origin,
    )

    private fun freshness(status: FreshnessStatus = FreshnessStatus.FRESH): Freshness = Freshness(
        status = status,
        lastAttemptAt = observedAt,
        lastSuccessAt = observedAt,
        observedAt = observedAt,
        parserVersion = "parser-v1",
        sourceHash = "fixture-hash",
    )

    private fun confirmation(
        stream: ReleaseStreamKey,
        installment: Installment,
    ): Confirmation = Confirmation(
        identity = SourceIdentity(stream, installment),
        evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
        confirmedObservedAt = observedAt,
    )

    private fun forecast(
        stream: ReleaseStreamKey,
        installment: Installment,
        daysFromNow: Int,
    ): Forecast = Forecast(
        identity = SourceIdentity(stream, installment),
        forecastAt = observedAt.plusSeconds(daysFromNow * DAY_SECONDS),
        sourceDate = LocalDate.of(2026, 9, 16),
        sourceTime = "20:15",
        sourceZone = utc,
        approximate = false,
        observedAt = observedAt,
    )

    private fun snapshot(
        stream: ReleaseStreamKey,
        confirmations: List<Confirmation> = emptyList(),
        forecasts: List<Forecast> = emptyList(),
        freshness: Freshness = freshness(),
        mapping: ReleaseMapping? = mapping(),
        sourcePresent: Boolean = true,
        sourceRoot: String? = "https://provider.example/anime/series",
    ): ReleaseSnapshot = ReleaseSnapshot(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = freshness,
        mapping = mapping,
        sourcePresent = sourcePresent,
        sourceRoot = sourceRoot,
        observedAt = observedAt,
    )

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}

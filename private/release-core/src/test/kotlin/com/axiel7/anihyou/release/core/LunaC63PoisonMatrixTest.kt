package com.axiel7.anihyou.release.core

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
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationDecision
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationPolicy
import com.axiel7.anihyou.release.core.projection.ReleasePresentation
import com.axiel7.anihyou.release.core.projection.ReleasePresentationSelector
import com.axiel7.anihyou.release.core.state.ReductionResult
import com.axiel7.anihyou.release.core.state.ReleaseReducer
import com.axiel7.anihyou.release.core.sync.ForecastRecheckActionKind
import com.axiel7.anihyou.release.core.sync.ForecastRecheckPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaC63PoisonMatrixTest {
    private val observedAt = Instant.parse("2026-09-11T12:00:00Z")
    private val stream = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey("poison-series"),
        releaseKind = ReleaseKind.EPISODE,
        sourceSeason = 2026,
        languageTrack = LanguageTrack.DE_SUB,
    )

    @Test
    fun providerConfirmationAndForecastWinAcrossTypedCoreConsumers() {
        val previous = applied(
            confirmations = listOf(confirmation(Installment.Episode(8))),
            accountProgress = 8,
        ).state
        val current = ReleaseReducer.reduce(
            previous = previous,
            snapshot = snapshot(
                confirmations = listOf(confirmation(Installment.Episode(9))),
                forecasts = listOf(forecast(Installment.Episode(10), 5)),
            ),
            accountProgress = 8,
        ) as ReductionResult.Applied

        assertEquals(AuthorityStatus.VALID, current.projection.authority)
        assertEquals(9, current.projection.confirmedThroughEpisode)
        assertEquals(1, current.projection.pendingCount)
        assertEquals(Installment.Episode(10), current.projection.nextForecast?.identity?.installment)
        assertEquals(observedAt.plusSeconds(5 * DAY_SECONDS), current.projection.nextForecast?.forecastAt)

        val selected = ReleasePresentationSelector.select(
            provider = current.projection,
            aniListFallback = AniListFallbackPresentation(
                releasedEpisode = 10,
                nextAiringEpisode = 11,
                nextAiringAt = observedAt.plusSeconds(6 * DAY_SECONDS),
            ),
        )
        assertTrue(selected is ReleasePresentation.Provider)
        val provider = (selected as ReleasePresentation.Provider).value
        assertEquals(Installment.Episode(10), provider.nextExpectedInstallment)
        assertFalse(provider.nextForecast?.forecastAt == observedAt.plusSeconds(6 * DAY_SECONDS))

        val decisions = ReleaseNotificationPolicy.decide(
            previous = previous,
            current = current.state,
            accountId = 7L,
            mediaId = 42,
            accountProgress = 8,
        )
        val enqueued = decisions.filterIsInstance<ReleaseNotificationDecision.Enqueue>()
        assertEquals(1, enqueued.size)
        assertEquals(Installment.Episode(9), enqueued.single().candidate.identity.installment)

        val forecastPlan = ForecastRecheckPlanner().plan(
            now = observedAt,
            forecasts = listOf(forecast(Installment.Episode(10), 5)),
            existing = emptyList(),
        )
        assertEquals(listOf(ForecastRecheckActionKind.SCHEDULE), forecastPlan.actions.map { it.kind })
        assertEquals(Installment.Episode(10), forecastPlan.scheduled.single().identity.installment)
    }

    @Test
    fun forecastEpisodeElevenCannotBecomeProviderState() {
        val result = applied(
            confirmations = listOf(confirmation(Installment.Episode(9))),
            forecasts = listOf(forecast(Installment.Episode(10), 5)),
            accountProgress = 8,
        )

        assertFalse(result.state.confirmations.any { it.identity.installment == Installment.Episode(10) })
        assertFalse(result.state.confirmations.any { it.identity.installment == Installment.Episode(11) })
        assertEquals(9, result.projection.confirmedThroughEpisode)
        assertEquals(Installment.Episode(10), result.projection.nextForecast?.identity?.installment)
    }

    private fun applied(
        confirmations: List<Confirmation> = emptyList(),
        forecasts: List<Forecast> = emptyList(),
        accountProgress: Int = 0,
    ): ReductionResult.Applied = ReleaseReducer.reduce(
        previous = null,
        snapshot = snapshot(confirmations = confirmations, forecasts = forecasts),
        accountProgress = accountProgress,
    ) as ReductionResult.Applied

    private fun snapshot(
        confirmations: List<Confirmation> = emptyList(),
        forecasts: List<Forecast> = emptyList(),
    ) = ReleaseSnapshot(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = observedAt,
            lastSuccessAt = observedAt,
            observedAt = observedAt,
            parserVersion = "luna-c63",
            sourceHash = "poison-fixture",
        ),
        mapping = ReleaseMapping(
            mediaId = 42,
            confidence = MappingConfidence.HIGH,
            score = 0.99,
            runnerUpMargin = 0.2,
            evidence = "fixed-poison-fixture",
            matcherVersion = "luna-c63",
            origin = MappingOrigin.AUTO,
        ),
        observedAt = observedAt,
    )

    private fun confirmation(installment: Installment) = Confirmation(
        identity = SourceIdentity(stream, installment),
        evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
        confirmedObservedAt = observedAt,
    )

    private fun forecast(installment: Installment, daysFromNow: Int) = Forecast(
        identity = SourceIdentity(stream, installment),
        forecastAt = observedAt.plusSeconds(daysFromNow * DAY_SECONDS),
        sourceDate = LocalDate.of(2026, 9, 16),
        sourceTime = "20:15",
        sourceZone = ZoneOffset.UTC,
        approximate = false,
        observedAt = observedAt,
    )

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}

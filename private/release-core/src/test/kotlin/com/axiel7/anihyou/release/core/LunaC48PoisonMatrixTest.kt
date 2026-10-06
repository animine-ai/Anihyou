package com.axiel7.anihyou.release.core

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
import com.axiel7.anihyou.release.core.sync.ScheduledForecastRecheck
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaC48PoisonMatrixTest {
    private val observedAt = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun fixedPoisonFixtureKeepsProviderAuthorityAtomic() {
        val stream = stream()
        val result = applied(
            stream = stream,
            confirmations = listOf(confirmation(stream, Installment.Episode(9))),
            forecasts = listOf(forecast(stream, Installment.Episode(10), 5)),
            accountProgress = 8,
        )

        assertEquals(AuthorityStatus.VALID, result.projection.authority)
        assertEquals(9, result.projection.confirmedThroughEpisode)
        assertEquals(1, result.projection.pendingCount)
        assertEquals(listOf(Installment.Episode(9)), result.projection.confirmedInstallments)
        assertEquals(Installment.Episode(10), result.projection.nextForecast?.identity?.installment)
        assertEquals(observedAt.plusSeconds(5 * DAY_SECONDS), result.projection.nextForecast?.forecastAt)

        val selected = ReleasePresentationSelector.select(
            provider = result.projection,
            aniListFallback = com.axiel7.anihyou.release.core.model.AniListFallbackPresentation(
                releasedEpisode = 9,
                nextAiringEpisode = 11,
                nextAiringAt = observedAt.plusSeconds(6 * DAY_SECONDS),
            ),
        )
        assertTrue(selected is ReleasePresentation.Provider)
        val provider = (selected as ReleasePresentation.Provider).value
        assertEquals(Installment.Episode(10), provider.nextExpectedInstallment)
        assertEquals(observedAt.plusSeconds(5 * DAY_SECONDS), provider.nextForecast?.forecastAt)
        assertFalse(provider.nextForecast?.forecastAt == observedAt.plusSeconds(6 * DAY_SECONDS))
    }

    @Test
    fun forecastCannotBecomeConfirmationOrPending() {
        val stream = stream()
        val result = applied(
            stream = stream,
            forecasts = listOf(forecast(stream, Installment.Episode(10), 5)),
            accountProgress = 0,
        )

        assertTrue(result.state.confirmations.isEmpty())
        assertEquals(AuthorityStatus.VALID, result.projection.authority)
        assertEquals(null, result.projection.confirmedThroughEpisode)
        assertEquals(0, result.projection.pendingCount)
        assertEquals(Installment.Episode(10), result.projection.nextForecast?.identity?.installment)
    }

    @Test
    fun tracksSeasonsAndDelayedDubRemainIndependent() {
        val subStream = stream(track = LanguageTrack.DE_SUB, season = 2025)
        val sub = applied(
            stream = subStream,
            confirmations = listOf(confirmation(subStream, Installment.Episode(9))),
            forecasts = listOf(forecast(subStream, Installment.Episode(10), 5)),
            accountProgress = 8,
        )
        val dubStream = stream(track = LanguageTrack.DE_DUB, season = 2025)
        val dub = applied(
            stream = dubStream,
            confirmations = listOf(confirmation(dubStream, Installment.Episode(7))),
            forecasts = listOf(forecast(dubStream, Installment.Episode(8), 8)),
            accountProgress = 6,
        )

        assertNotEquals(sub.projection.stream.stableKey, dub.projection.stream.stableKey)
        assertEquals(1, sub.projection.pendingCount)
        assertEquals(1, dub.projection.pendingCount)
        assertEquals(Installment.Episode(10), sub.projection.nextForecast?.identity?.installment)
        assertEquals(Installment.Episode(8), dub.projection.nextForecast?.identity?.installment)
        assertEquals(
            LocalDate.of(2026, 1, 5),
            forecast(
                stream = stream(season = 2025),
                installment = Installment.Episode(10),
                daysFromNow = 5,
                sourceDate = LocalDate.of(2026, 1, 5),
            ).sourceDate,
        )
    }

    @Test
    fun filmSpecialAndFractionalRowsStayTypedAndFailClosedForEpisodeProgress() {
        val typedCases = listOf(
            ReleaseKind.MOVIE to Installment.Film(1),
            ReleaseKind.SPECIAL to Installment.Special(1),
            ReleaseKind.OVA to Installment.Special(1),
            ReleaseKind.ONA to Installment.Special(1),
        )
        typedCases.forEach { (kind, installment) ->
            val typedStream = stream(kind = kind)
            val result = applied(
                stream = typedStream,
                confirmations = listOf(confirmation(typedStream, installment)),
                accountProgress = 999,
            )
            assertEquals(listOf(installment), result.projection.confirmedInstallments)
            assertEquals(null, result.projection.confirmedThroughEpisode)
            assertEquals(0, result.projection.pendingCount)
        }

        val fractionalStream = stream()
        val fractional = applied(
            stream = fractionalStream,
            confirmations = listOf(
                confirmation(fractionalStream, Installment.Episode(10, fraction = 5)),
            ),
            accountProgress = 0,
        )
        assertEquals(0, fractional.projection.pendingCount)
        assertEquals(
            Installment.Episode(10, fraction = 5),
            fractional.projection.confirmedInstallments.single(),
        )
    }

    @Test
    fun staleAndAmbiguousStatesDoNotLeakLastGoodRowsAsAuthoritative() {
        val stream = stream()
        val first = applied(
            stream = stream,
            confirmations = listOf(confirmation(stream, Installment.Episode(9))),
            accountProgress = 0,
        )
        val stale = ReleaseReducer.reduce(
            previous = first.state,
            snapshot = snapshot(
                stream = stream,
                freshness = freshness(FreshnessStatus.STALE),
                mapping = null,
                sourcePresent = false,
                sourceRoot = null,
            ),
            accountProgress = 0,
        ) as ReductionResult.Applied
        assertEquals(AuthorityStatus.STALE, stale.projection.authority)

        val ambiguous = applied(
            stream = stream(kind = ReleaseKind.EPISODE),
            mapping = mapping(MappingConfidence.AMBIGUOUS),
        )
        assertEquals(AuthorityStatus.AMBIGUOUS, ambiguous.projection.authority)
        assertFalse(ambiguous.projection.authority == AuthorityStatus.VALID)
    }

    @Test
    fun notificationAndForecastWorkHaveIndependentIdempotentTransitions() {
        val stream = stream()
        val previous = applied(
            stream = stream,
            confirmations = listOf(confirmation(stream, Installment.Episode(8))),
        ).state
        val current = applied(
            stream = stream,
            confirmations = listOf(
                confirmation(stream, Installment.Episode(8)),
                confirmation(stream, Installment.Episode(9)),
            ),
        ).state
        val decisions = ReleaseNotificationPolicy.decide(
            previous = previous,
            current = current,
            accountId = 7L,
            mediaId = 42,
            accountProgress = 8,
        )
        assertEquals(1, decisions.count { it is ReleaseNotificationDecision.Enqueue })
        assertEquals(
            Installment.Episode(9),
            (decisions.single() as ReleaseNotificationDecision.Enqueue).candidate.identity.installment,
        )

        val planner = ForecastRecheckPlanner()
        val identity = SourceIdentity(stream, Installment.Episode(10))
        val old = ScheduledForecastRecheck(
            workKey = ForecastRecheckPlanner.workKey(identity),
            identity = identity,
            forecastAt = observedAt.plusSeconds(5 * DAY_SECONDS),
            runAt = observedAt.plusSeconds(5 * DAY_SECONDS),
        )
        val moved = forecast(stream, Installment.Episode(10), 6)
        val movedPlan = planner.plan(
            now = observedAt,
            forecasts = listOf(moved),
            existing = listOf(old),
        )
        assertEquals(
            listOf(ForecastRecheckActionKind.CANCEL, ForecastRecheckActionKind.SCHEDULE),
            movedPlan.actions.map { it.kind },
        )
        val removedPlan = planner.plan(observedAt, emptyList(), listOf(old))
        assertEquals(listOf(ForecastRecheckActionKind.CANCEL), removedPlan.actions.map { it.kind })
    }

    private fun applied(
        stream: ReleaseStreamKey,
        confirmations: List<Confirmation> = emptyList(),
        forecasts: List<Forecast> = emptyList(),
        accountProgress: Int = 0,
        mapping: ReleaseMapping? = mapping(),
    ): ReductionResult.Applied = ReleaseReducer.reduce(
        previous = null,
        snapshot = snapshot(
            stream = stream,
            confirmations = confirmations,
            forecasts = forecasts,
            mapping = mapping,
        ),
        accountProgress = accountProgress,
    ) as ReductionResult.Applied

    private fun snapshot(
        stream: ReleaseStreamKey,
        confirmations: List<Confirmation> = emptyList(),
        forecasts: List<Forecast> = emptyList(),
        freshness: Freshness = freshness(),
        mapping: ReleaseMapping? = mapping(),
        sourcePresent: Boolean = true,
        sourceRoot: String? = "https://aniworld.to/anime/series",
    ) = ReleaseSnapshot(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = freshness,
        mapping = mapping,
        sourcePresent = sourcePresent,
        sourceRoot = sourceRoot,
        observedAt = observedAt,
    )

    private fun stream(
        track: LanguageTrack = LanguageTrack.DE_SUB,
        kind: ReleaseKind = ReleaseKind.EPISODE,
        season: Int? = 2026,
    ) = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey("luna-series"),
        releaseKind = kind,
        sourceSeason = season,
        languageTrack = track,
    )

    private fun mapping(
        confidence: MappingConfidence = MappingConfidence.HIGH,
        mediaId: Int = 42,
    ) = ReleaseMapping(
        mediaId = mediaId,
        confidence = confidence,
        score = 0.99,
        runnerUpMargin = 0.2,
        evidence = "luna-c48",
        matcherVersion = "matcher-v1",
        origin = MappingOrigin.AUTO,
    )

    private fun freshness(status: FreshnessStatus = FreshnessStatus.FRESH) = Freshness(
        status = status,
        lastAttemptAt = observedAt,
        lastSuccessAt = observedAt,
        observedAt = observedAt,
        parserVersion = "luna-c48",
        sourceHash = "fixture",
    )

    private fun confirmation(
        stream: ReleaseStreamKey,
        installment: Installment,
    ) = Confirmation(
        identity = SourceIdentity(stream, installment),
        evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
        confirmedObservedAt = observedAt,
    )

    private fun forecast(
        stream: ReleaseStreamKey,
        installment: Installment,
        daysFromNow: Int,
        sourceDate: LocalDate = LocalDate.of(2026, 9, 16),
    ) = Forecast(
        identity = SourceIdentity(stream, installment),
        forecastAt = observedAt.plusSeconds(daysFromNow * DAY_SECONDS),
        sourceDate = sourceDate,
        sourceTime = "20:15",
        sourceZone = ZoneOffset.UTC,
        approximate = false,
        observedAt = observedAt,
    )

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}

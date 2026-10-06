package com.axiel7.anihyou.release.core.sync

import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastRecheckPlannerTest {
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private val identity = SourceIdentity(
        stream = ReleaseStreamKey(
            providerId = ProviderId("provider"),
            stableSeriesKey = SourceSeriesKey("series"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        ),
        installment = Installment.Episode(10),
    )

    private fun forecast(
        identity: SourceIdentity = this.identity,
        forecastAt: Instant = now.plus(Duration.ofDays(5)),
        observedAt: Instant = now,
    ) = Forecast(
        identity = identity,
        forecastAt = forecastAt,
        sourceDate = null,
        sourceTime = null,
        sourceZone = null,
        approximate = false,
        observedAt = observedAt,
    )

    @Test
    fun newForecastSchedulesOnlyBoundedRecheckWork() {
        val plan = ForecastRecheckPlanner().plan(
            now = now,
            forecasts = listOf(forecast()),
            existing = emptyList(),
        )

        assertEquals(listOf(ForecastRecheckActionKind.SCHEDULE), plan.actions.map { it.kind })
        assertEquals(ForecastRecheckPlanner.workKey(identity), plan.scheduled.single().workKey)
        assertEquals(now.plus(Duration.ofDays(5)), plan.scheduled.single().runAt)
    }

    @Test
    fun overdueAndFarFutureForecastsAreBounded() {
        val overdue = forecast(forecastAt = now.minusSeconds(1))
        val farFuture = forecast(
            identity = identity.copy(installment = Installment.Episode(11)),
            forecastAt = now.plus(Duration.ofDays(30)),
        )

        val plan = ForecastRecheckPlanner(maxDelay = Duration.ofDays(7)).plan(
            now = now,
            forecasts = listOf(overdue, farFuture),
            existing = emptyList(),
        )

        assertEquals(now, plan.scheduled[0].runAt)
        assertEquals(now.plus(Duration.ofDays(7)), plan.scheduled[1].runAt)
    }

    @Test
    fun movedForecastReplacesExistingDeterministicWork() {
        val oldForecast = forecast(forecastAt = now.plus(Duration.ofDays(5)))
        val movedForecast = forecast(
            forecastAt = now.plus(Duration.ofDays(6)),
            observedAt = now.plusSeconds(1),
        )
        val existing = ScheduledForecastRecheck(
            workKey = ForecastRecheckPlanner.workKey(identity),
            identity = identity,
            forecastAt = oldForecast.forecastAt,
            runAt = oldForecast.forecastAt,
        )

        val plan = ForecastRecheckPlanner().plan(
            now = now,
            forecasts = listOf(movedForecast),
            existing = listOf(existing),
        )

        assertEquals(
            listOf(
                ForecastRecheckActionKind.CANCEL,
                ForecastRecheckActionKind.SCHEDULE,
            ),
            plan.actions.map { it.kind },
        )
        assertEquals(movedForecast.forecastAt, plan.scheduled.single().forecastAt)
    }

    @Test
    fun forecastEpisodeChangeCancelsObsoleteWorkAndSchedulesNewKey() {
        val nextIdentity = identity.copy(installment = Installment.Episode(11))
        val existing = ScheduledForecastRecheck(
            workKey = ForecastRecheckPlanner.workKey(identity),
            identity = identity,
            forecastAt = now.plus(Duration.ofDays(5)),
            runAt = now.plus(Duration.ofDays(5)),
        )

        val plan = ForecastRecheckPlanner().plan(
            now = now,
            forecasts = listOf(forecast(identity = nextIdentity)),
            existing = listOf(existing),
        )

        assertEquals(1, plan.cancelled.size)
        assertEquals(1, plan.scheduled.size)
        assertEquals(ForecastRecheckPlanner.workKey(identity), plan.cancelled.single().workKey)
        assertEquals(ForecastRecheckPlanner.workKey(nextIdentity), plan.scheduled.single().workKey)
    }

    @Test
    fun latestObservedDuplicateWinsAndEntryBudgetIsDeterministic() {
        val secondIdentity = identity.copy(installment = Installment.Episode(11))
        val duplicateOlder = forecast(forecastAt = now.plus(Duration.ofDays(2)))
        val duplicateNewer = forecast(
            forecastAt = now.plus(Duration.ofDays(3)),
            observedAt = now.plusSeconds(1),
        )
        val plan = ForecastRecheckPlanner(maxEntries = 1).plan(
            now = now,
            forecasts = listOf(
                secondIdentity.let { forecast(identity = it) },
                duplicateOlder,
                duplicateNewer,
            ),
            existing = emptyList(),
        )

        assertTrue(plan.scheduled.single().identity == identity)
        assertEquals(duplicateNewer.forecastAt, plan.scheduled.single().forecastAt)
    }
}

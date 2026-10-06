package com.axiel7.anihyou.release.core.sync

import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.SourceIdentity
import java.time.Duration
import java.time.Instant

enum class ForecastRecheckActionKind {
    SCHEDULE,
    KEEP,
    CANCEL,
}

data class ScheduledForecastRecheck(
    val workKey: String,
    val identity: SourceIdentity,
    val forecastAt: Instant,
    val runAt: Instant,
)

data class ForecastRecheckAction(
    val kind: ForecastRecheckActionKind,
    val workKey: String,
    val identity: SourceIdentity,
    val forecastAt: Instant?,
    val runAt: Instant?,
)

data class ForecastRecheckPlan(
    val actions: List<ForecastRecheckAction>,
) {
    val scheduled: List<ForecastRecheckAction>
        get() = actions.filter { it.kind == ForecastRecheckActionKind.SCHEDULE }

    val cancelled: List<ForecastRecheckAction>
        get() = actions.filter { it.kind == ForecastRecheckActionKind.CANCEL }
}

/**
 * Forecasts only reconcile bounded refresh work. They never mutate release state,
 * create confirmations, change pending counts, or enqueue delivery events.
 */
class ForecastRecheckPlanner(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxDelay: Duration = DEFAULT_MAX_DELAY,
) {
    init {
        require(maxEntries > 0) { "forecast recheck entry budget must be positive" }
        require(!maxDelay.isZero && !maxDelay.isNegative) {
            "forecast recheck delay must be positive"
        }
    }

    fun plan(
        now: Instant,
        forecasts: List<Forecast>,
        existing: List<ScheduledForecastRecheck>,
    ): ForecastRecheckPlan {
        val desired = forecasts
            .groupBy { it.identity.stableKey }
            .mapNotNull { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<Forecast> { it.observedAt }
                        .thenBy { it.forecastAt }
                        .thenBy { it.sourceDate?.toString().orEmpty() }
                )
            }
            .sortedBy { it.identity.stableKey }
            .take(maxEntries)

        val desiredByKey = desired.associateBy { workKey(it.identity) }
        val existingByKey = existing
            .groupBy { it.workKey }
            .mapValues { (_, entries) ->
                entries.sortedWith(
                    compareBy<ScheduledForecastRecheck> { it.runAt }
                        .thenBy { it.forecastAt }
                        .thenBy { it.identity.stableKey }
                )
            }
        val actions = mutableListOf<ForecastRecheckAction>()

        existingByKey.toSortedMap().forEach { (key, entries) ->
            val desiredForecast = desiredByKey[key]
            if (desiredForecast == null) {
                entries.forEach { actions += cancel(it) }
                return@forEach
            }

            val desiredRunAt = boundedRunAt(now, desiredForecast.forecastAt)
            val canonical = entries.first()
            entries.drop(1).forEach { actions += cancel(it) }
            if (canonical.forecastAt == desiredForecast.forecastAt &&
                canonical.runAt == desiredRunAt &&
                canonical.identity == desiredForecast.identity
            ) {
                actions += ForecastRecheckAction(
                    kind = ForecastRecheckActionKind.KEEP,
                    workKey = key,
                    identity = canonical.identity,
                    forecastAt = canonical.forecastAt,
                    runAt = canonical.runAt,
                )
            } else {
                actions += cancel(canonical)
                actions += schedule(desiredForecast, desiredRunAt)
            }
        }

        desired.forEach { forecast ->
            val key = workKey(forecast.identity)
            if (key !in existingByKey) {
                actions += schedule(forecast, boundedRunAt(now, forecast.forecastAt))
            }
        }

        return ForecastRecheckPlan(actions = actions)
    }

    private fun boundedRunAt(now: Instant, forecastAt: Instant): Instant {
        val latestAllowed = now.plus(maxDelay)
        return when {
            forecastAt.isBefore(now) -> now
            forecastAt.isAfter(latestAllowed) -> latestAllowed
            else -> forecastAt
        }
    }

    private fun schedule(forecast: Forecast, runAt: Instant) = ForecastRecheckAction(
        kind = ForecastRecheckActionKind.SCHEDULE,
        workKey = workKey(forecast.identity),
        identity = forecast.identity,
        forecastAt = forecast.forecastAt,
        runAt = runAt,
    )

    private fun cancel(existing: ScheduledForecastRecheck) = ForecastRecheckAction(
        kind = ForecastRecheckActionKind.CANCEL,
        workKey = existing.workKey,
        identity = existing.identity,
        forecastAt = existing.forecastAt,
        runAt = existing.runAt,
    )

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 128
        val DEFAULT_MAX_DELAY: Duration = Duration.ofDays(7)

        fun workKey(identity: SourceIdentity): String =
            "release-forecast-recheck:${identity.stableKey}"
    }
}

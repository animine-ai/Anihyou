package com.axiel7.anihyou.release.data.db

import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.sync.ForecastRecheckAction
import com.axiel7.anihyou.release.core.sync.ForecastRecheckActionKind
import com.axiel7.anihyou.release.core.sync.ForecastRecheckPlanner
import com.axiel7.anihyou.release.core.sync.ScheduledForecastRecheck
import java.time.Instant

internal data class ForecastRecheckUpdate(
    val actions: List<ForecastRecheckAction>,
    val invalidWorkKeys: List<String>,
)

internal suspend fun ReleaseDao.reconcileForecastRechecks(
    forecastsByStream: Map<String, List<Forecast>>,
    now: Instant,
    planner: ForecastRecheckPlanner = ForecastRecheckPlanner(),
): ForecastRecheckUpdate {
    val actions = mutableListOf<ForecastRecheckAction>()
    val invalidWorkKeys = mutableListOf<String>()
    forecastsByStream.toSortedMap().forEach { (streamKey, forecasts) ->
        val rows = getForecastRecheckWork(streamKey)
        val decoded = rows.mapNotNull { row -> row.toScheduledOrNull() }
        val invalidKeys = rows
            .filter { row -> row.toScheduledOrNull() == null }
            .map { it.workKey }
        if (invalidKeys.isNotEmpty()) {
            deleteForecastRecheckWork(invalidKeys)
            invalidWorkKeys += invalidKeys
        }

        val plan = planner.plan(
            now = now,
            forecasts = forecasts,
            existing = decoded,
        )
        actions += plan.actions
        plan.actions.forEach { action ->
            when (action.kind) {
                ForecastRecheckActionKind.SCHEDULE -> {
                    upsertForecastRecheckWork(listOf(action.toEntity(now)))
                }
                ForecastRecheckActionKind.CANCEL -> {
                    deleteForecastRecheckWork(listOf(action.workKey))
                }
                ForecastRecheckActionKind.KEEP -> Unit
            }
        }
    }
    return ForecastRecheckUpdate(actions = actions, invalidWorkKeys = invalidWorkKeys)
}

private fun ForecastRecheckWorkEntity.toScheduledOrNull(): ScheduledForecastRecheck? {
    val stream = decodeStream(streamPayload) ?: return null
    val installment = decodeInstallment(installmentPayload) ?: return null
    val identity = SourceIdentity(stream, installment)
    if (streamKey != stream.stableKey ||
        identityKey != identity.stableKey ||
        workKey != ForecastRecheckPlanner.workKey(identity)
    ) {
        return null
    }
    val forecastAt = runCatching { Instant.parse(forecastAt) }.getOrNull() ?: return null
    val runAt = runCatching { Instant.parse(runAt) }.getOrNull() ?: return null
    return ScheduledForecastRecheck(
        workKey = workKey,
        identity = identity,
        forecastAt = forecastAt,
        runAt = runAt,
    )
}

private fun ForecastRecheckAction.toEntity(updatedAt: Instant): ForecastRecheckWorkEntity {
    val forecast = forecastAt ?: error("schedule action must carry forecast time")
    val run = runAt ?: error("schedule action must carry run time")
    return ForecastRecheckWorkEntity(
        workKey = workKey,
        identityKey = identity.stableKey,
        streamKey = identity.stream.stableKey,
        streamPayload = encodeStream(identity.stream),
        installmentPayload = encodeInstallment(identity.installment),
        forecastAt = forecast.toString(),
        runAt = run.toString(),
        updatedAt = updatedAt.toString(),
    )
}

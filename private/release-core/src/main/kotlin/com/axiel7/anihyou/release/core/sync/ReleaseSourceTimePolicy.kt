package com.axiel7.anihyou.release.core.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One source-calendar policy for provider requests and forecast filtering.
 *
 * The source calendar is a local calendar, not a UTC date projection. This keeps
 * midnight boundaries and DST transitions deterministic for AniWorld's Berlin
 * timestamps while still honoring an explicit provider source date.
 */
object ReleaseSourceTimePolicy {
    val ANI_WORLD_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    fun range(
        now: Instant,
        lookaheadDays: Long,
        sourceZone: ZoneId,
    ): ClosedRange<LocalDate> {
        require(lookaheadDays > 0L) { "lookahead must be positive" }
        val sourceNow = now.atZone(sourceZone)
        return sourceNow.toLocalDate()..sourceNow.plusDays(lookaheadDays).toLocalDate()
    }

    fun effectiveDate(
        sourceDate: LocalDate?,
        forecastAt: Instant,
        forecastZone: ZoneId?,
        fallbackZone: ZoneId,
    ): LocalDate = sourceDate ?: forecastAt
        .atZone(forecastZone ?: fallbackZone)
        .toLocalDate()
}

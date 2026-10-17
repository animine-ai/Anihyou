package com.axiel7.anihyou.feature.calendar.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.axiel7.anihyou.feature.calendar.CalendarRow
import com.axiel7.anihyou.release.core.api.ReleaseCalendarUiSelection
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Where a calendar entry stands in its release. Two independent facts: the planned time has passed (the entry is dimmed)
 * (by the extension's time, never by an AniList time) and the source lists the episode (the entry is checked, as information). An entry that is both ends up dimmed and checked. The same
 * rule marks the entry in every calendar style.
 *
 * [UPCOMING] time not reached, not proven; [THEORETICAL] time passed, not proven (dimmed); [CONFIRMED] proven out, time not
 * passed or not known (checked); [CONFIRMED_PAST] proven out and the time has passed (dimmed and checked).
 */
internal enum class ReleaseMark { UPCOMING, THEORETICAL, CONFIRMED, CONFIRMED_PAST }

internal fun CalendarRow.releaseMark(now: Instant): ReleaseMark {
    val representative = releasePresentations.takeIf { it.isNotEmpty() }
        ?.let { ReleaseCalendarUiSelection.representative(it) }
    if (representative != null) {
        val plannedAt = representative.forecastAt
        val passed = plannedAt != null && !plannedAt.isAfter(now)
        return when {
            representative.confirmed -> if (passed) ReleaseMark.CONFIRMED_PAST else ReleaseMark.CONFIRMED
            passed -> ReleaseMark.THEORETICAL
            else -> ReleaseMark.UPCOMING
        }
    }
    // Only the time of the extension (the release source) dims an entry; an AniList airing time alone never does.
    return ReleaseMark.UPCOMING
}

/** An entry whose planned time (from the extension) has passed is dimmed slightly, checked or not; others are not. */
internal const val THEORETICAL_ALPHA = 0.55f

internal fun Modifier.releaseDim(mark: ReleaseMark): Modifier =
    if (mark == ReleaseMark.THEORETICAL || mark == ReleaseMark.CONFIRMED_PAST) alpha(THEORETICAL_ALPHA) else this

/** The current time, renewed every half minute, so an entry dims when its time passes while the calendar is open. */
@Composable
internal fun rememberNowInstant(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    return now
}

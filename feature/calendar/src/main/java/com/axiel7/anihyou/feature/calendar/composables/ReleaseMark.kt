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
 * Where a calendar entry stands in its release: not yet due, past its planned time without proof that it is out (dimmed),
 * or proven out by the source (checked). The same rule marks the entry in every calendar style.
 */
internal enum class ReleaseMark { UPCOMING, THEORETICAL, CONFIRMED }

internal fun CalendarRow.releaseMark(now: Instant): ReleaseMark {
    val representative = releasePresentations.takeIf { it.isNotEmpty() }
        ?.let { ReleaseCalendarUiSelection.representative(it) }
    if (representative != null) {
        if (representative.confirmed) return ReleaseMark.CONFIRMED
        val plannedAt = representative.forecastAt ?: return ReleaseMark.UPCOMING
        return if (plannedAt.isAfter(now)) ReleaseMark.UPCOMING else ReleaseMark.THEORETICAL
    }
    // An AniList airing time cannot be confirmed by a source: once it has passed the entry is past, nothing more.
    val airing = airingEvent ?: return ReleaseMark.UPCOMING
    return if (airing.airingAt.toLong() > now.epochSecond) ReleaseMark.UPCOMING else ReleaseMark.THEORETICAL
}

/** An entry that is past its planned time but not proven out is dimmed slightly; checked and upcoming ones are not. */
internal const val THEORETICAL_ALPHA = 0.55f

internal fun Modifier.releaseDim(mark: ReleaseMark): Modifier =
    if (mark == ReleaseMark.THEORETICAL) alpha(THEORETICAL_ALPHA) else this

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

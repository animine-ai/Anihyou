package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.secondsToLegibleText
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.api.ReleaseUiSelection
import com.axiel7.anihyou.release.core.api.pendingFor
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseKind
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The current time for a text that counts down to [target]. It follows the clock while the text is on screen, so an
 * open screen ages its countdown without any network request. The tick is coarse (once a minute) and only gets finer
 * inside the last hour; it stops when the target is overdue past the grace, because the text no longer changes then.
 */
@Composable
fun rememberReleaseNow(clock: Clock, target: Instant?): Instant {
    val now = remember(clock, target) { mutableStateOf(clock.instant()) }
    if (target != null) {
        LaunchedEffect(clock, target) {
            while (true) {
                val current = clock.instant()
                now.value = current
                val remaining = Duration.between(current, target)
                if (ReleaseUiSelection.isOverdue(target, current)) break
                val distance = remaining.abs().seconds
                delay(
                    when {
                        distance < 60L -> 1_000L
                        distance < 3_600L -> 15_000L
                        else -> 60_000L
                    },
                )
            }
        }
    }
    return now.value
}

@Composable
fun ReleaseScheduleText(
    presentation: ReleaseUiPresentation?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    clock: Clock = Clock.systemUTC(),
    /** The user's current AniList progress for this media; without it no pending count is claimed. */
    progress: Int? = null,
    fallback: @Composable () -> Unit,
) {
    if (presentation?.isAuthoritative != true || presentation.track != LanguageTrack.DE_SUB) {
        fallback()
        return
    }

    // The original wording of the app, with the data of the release source: "N episodes behind" in the accent colour,
    // otherwise "Ep N in 3d 4h". No wording of its own.
    val now = rememberReleaseNow(clock, presentation.nextForecastAt)
    val pending = presentation.pendingFor(progress)
    val next = presentation.nextExpectedInstallment
    // A plan that passed without a confirmation stays a plan: no time, never "now" for days.
    val forecastAt = presentation.nextForecast?.forecastAt?.takeUnless { ReleaseUiSelection.isOverdue(it, now) }
    val untilNext = forecastAt?.let { Duration.between(now, it).seconds.coerceAtLeast(0L).secondsToLegibleText() }
    val text = when {
        pending > 0 -> pluralStringResource(R.plurals.num_episodes_behind, pending, pending)
        next is Installment.Episode && untilNext != null -> stringResource(R.string.episode_in_time, next.number, untilNext)
        next != null && untilNext != null -> stringResource(R.string.airing_in, untilNext)
        else -> null
    }
    if (text == null) return // An authoritative source with unknown coordinates must not invent an AniList count.

    Text(
        text = text.withTrack(presentation.stream.languageTrack),
        modifier = modifier,
        color = if (pending > 0) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.labelLarge,
        textAlign = textAlign,
    )
}

/** "SUB" or "DUB" for a German track; null when the source did not say. */
fun releaseTrackLabel(track: LanguageTrack): String? = when (track) {
    LanguageTrack.DE_SUB -> "SUB"
    LanguageTrack.DE_DUB -> "DUB"
    else -> null
}

private fun String.withTrack(track: LanguageTrack): String =
    releaseTrackLabel(track)?.let { "$this · $it" } ?: this

@Composable
fun releaseInstallmentLabel(
    installment: Installment,
    releaseKind: ReleaseKind,
): String = when (installment) {
    is Installment.Episode -> installment.fraction?.let { fraction ->
        stringResource(R.string.release_installment_episode_fraction, installment.number, fraction)
    } ?: stringResource(R.string.release_installment_episode, installment.number)

    is Installment.Film -> installment.number?.let {
        stringResource(R.string.release_installment_film_number, it)
    } ?: stringResource(R.string.release_installment_film)

    is Installment.Special -> when (releaseKind) {
        ReleaseKind.OVA -> stringResource(R.string.release_installment_ova)
        ReleaseKind.ONA -> stringResource(R.string.release_installment_ona)
        else -> installment.number?.let {
            stringResource(R.string.release_installment_special_number, it)
        } ?: stringResource(R.string.release_installment_special)
    }
}

@Composable
fun Instant.releaseRelativeText(now: Instant): String {
    val seconds = Duration.between(now, this).seconds
    return when {
        seconds <= 0L -> stringResource(R.string.release_relative_now)
        seconds < 60L -> pluralStringResource(
            R.plurals.release_relative_seconds,
            seconds.toInt(),
            seconds.toInt(),
        )
        seconds < 3600L -> pluralStringResource(
            R.plurals.release_relative_minutes,
            (seconds / 60L).toInt(),
            (seconds / 60L).toInt(),
        )
        seconds < 86_400L -> pluralStringResource(
            R.plurals.release_relative_hours,
            (seconds / 3600L).toInt(),
            (seconds / 3600L).toInt(),
        )
        else -> pluralStringResource(
            R.plurals.release_relative_days,
            (seconds / 86_400L).toInt(),
            (seconds / 86_400L).toInt(),
        )
    }
}

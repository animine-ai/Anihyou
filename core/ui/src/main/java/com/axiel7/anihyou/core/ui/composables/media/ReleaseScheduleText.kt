package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseKind
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Composable
fun ReleaseScheduleText(
    presentation: ReleaseUiPresentation?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    clock: Clock = Clock.systemUTC(),
    fallback: @Composable () -> Unit,
) {
    if (presentation?.isAuthoritative != true) {
        fallback()
        return
    }

    val pending = presentation.confirmedPending
    val parts = mutableListOf<String>()
    presentation.confirmedThroughEpisode?.let {
        parts += stringResource(R.string.release_schedule_confirmed_through, it)
    }
    if (pending > 0) {
        parts += pluralStringResource(
            R.plurals.release_schedule_pending,
            pending,
            pending,
        )
    }
    presentation.nextExpectedInstallment?.let { installment ->
        val label = releaseInstallmentLabel(installment, presentation.stream.releaseKind)
        parts += presentation.nextForecast?.forecastAt?.let { forecastAt ->
            stringResource(
                R.string.release_schedule_next_at,
                label,
                forecastAt.releaseRelativeText(clock.instant()),
            )
        } ?: stringResource(R.string.release_schedule_next, label)
    }
    if (parts.isEmpty()) {
        parts += stringResource(R.string.release_schedule_available)
    }

    Text(
        text = parts.joinToString(" · "),
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

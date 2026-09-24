package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import java.time.Clock

@Composable
fun ReleaseCalendarScheduleText(
    presentation: ReleaseUiCalendarItem?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    clock: Clock = Clock.systemUTC(),
    fallback: @Composable () -> Unit,
) {
    if (presentation?.isAuthoritative != true) {
        fallback()
        return
    }

    val label = releaseInstallmentLabel(
        installment = presentation.installment,
        releaseKind = presentation.stream.releaseKind,
    )
    val text = if (presentation.confirmed) {
        stringResource(R.string.release_schedule_confirmed_installment, label)
    } else {
        presentation.forecastAt?.let {
            stringResource(
                R.string.release_schedule_next_at,
                label,
                it.releaseRelativeText(clock.instant()),
            )
        } ?: stringResource(R.string.release_schedule_next, label)
    }

    Text(
        text = text,
        modifier = modifier,
        color = if (presentation.confirmed) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        textAlign = textAlign,
    )
}

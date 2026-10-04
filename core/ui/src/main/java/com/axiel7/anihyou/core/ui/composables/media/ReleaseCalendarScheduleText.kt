package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.axiel7.anihyou.core.common.utils.DateUtils.timestampToTimeString
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.release.core.model.Installment
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

    // The original calendar wording ("Ep 3 airing at 14:30") with the time of the release source.
    val episode = (presentation.installment as? Installment.Episode)?.number
    val time = presentation.forecastAt?.epochSecond?.timestampToTimeString()
    if (episode == null || time == null) {
        fallback()
        return
    }

    Text(
        text = stringResource(R.string.episode_airing_at, episode, time),
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        textAlign = textAlign,
    )
}

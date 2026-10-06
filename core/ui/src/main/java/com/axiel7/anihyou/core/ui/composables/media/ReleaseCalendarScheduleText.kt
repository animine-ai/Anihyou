package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import com.axiel7.anihyou.release.core.api.ReleaseCalendarUiSelection
import java.time.Clock

@Composable
fun ReleaseCalendarGroupScheduleText(
    presentations: List<ReleaseUiCalendarItem>,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val representative = ReleaseCalendarUiSelection.representative(presentations)
    val tracks = presentations.mapNotNull { releaseTrackLabel(it.track) }.distinct().sortedDescending().joinToString("/")
    ReleaseCalendarScheduleText(
        presentation = representative, modifier = modifier, trackLabel = tracks.takeIf { it.isNotEmpty() }, fallback = fallback,
    )
}

@Composable
fun ReleaseCalendarScheduleText(
    presentation: ReleaseUiCalendarItem?,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    clock: Clock = Clock.systemUTC(),
    trackLabel: String? = presentation?.track?.let(::releaseTrackLabel),
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

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.episode_airing_at, episode, time)
                .let { line -> trackLabel?.let { "$line · $it" } ?: line },
            modifier = Modifier.weight(1f, fill = false),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            textAlign = textAlign,
        )
        if (presentation.confirmed) {
            Icon(painter = painterResource(R.drawable.check_20),
                contentDescription = stringResource(R.string.release_calendar_confirmed),
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

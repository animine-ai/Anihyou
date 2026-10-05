package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.secondsToLegibleText
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.api.ReleaseUiSelection
import com.axiel7.anihyou.release.core.model.Installment
import java.time.Clock
import java.time.Duration

/** The same next-release text in the details header and information section. No backlog replaces this field. */
@Composable
fun nextEpisodeText(
    presentations: List<ReleaseUiPresentation>,
    fallbackEpisode: Int?,
    fallbackSeconds: Long?,
    clock: Clock = Clock.systemUTC(),
): String? {
    val target = ReleaseUiSelection.upcoming(presentations, clock.instant())?.nextForecastAt
    val now = rememberReleaseNow(clock, target)
    val source = ReleaseUiSelection.upcoming(presentations, now)
    if (source != null) {
        val relative = Duration.between(now, requireNotNull(source.nextForecastAt)).seconds.coerceAtLeast(0L)
            .secondsToLegibleText()
        val episode = source.nextExpectedInstallment as? Installment.Episode
        val text = if (episode != null) stringResource(R.string.episode_in_time, episode.number, relative)
            else stringResource(R.string.airing_in, relative)
        return releaseTrackLabel(source.track)?.let { "$text · $it" } ?: text
    }
    if (fallbackEpisode == null || fallbackSeconds == null) return null
    return stringResource(R.string.episode_in_time, fallbackEpisode, fallbackSeconds.secondsToLegibleText())
}

package com.axiel7.anihyou.core.ui.composables.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.secondsToLegibleText
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.api.ReleaseUiSelection
import com.axiel7.anihyou.release.core.api.pendingForOrNull
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

/** Backlog is a separate field, so displaying it never removes the next-episode countdown. */
@Composable
fun releaseBacklogText(presentations: List<ReleaseUiPresentation>, progress: Int?, fallbackNextEpisode: Int?): String? {
    if (progress == null) return null
    val source = ReleaseUiSelection.effective(presentations)
    val sourceCount = source?.pendingForOrNull(progress)
    val count = sourceCount ?: fallbackNextEpisode?.let { (it - 1 - progress.coerceAtLeast(0)).coerceAtLeast(0) }
        ?: return null
    if (count == 0) return null
    val text = pluralStringResource(R.plurals.num_episodes_behind, count, count)
    return if (sourceCount != null) releaseTrackLabel(requireNotNull(source).track)?.let { "$text · $it" } ?: text else text
}

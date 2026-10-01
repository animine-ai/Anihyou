package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.axiel7.anihyou.core.common.utils.ContextUtils.openActionView
import com.axiel7.anihyou.core.common.utils.DateUtils.toLocalized
import com.axiel7.anihyou.core.model.media.AnimeSeason
import com.axiel7.anihyou.core.model.media.episodeNumber
import com.axiel7.anihyou.core.model.media.externalLinks
import com.axiel7.anihyou.core.model.media.isAnime
import com.axiel7.anihyou.core.model.media.languageShort
import com.axiel7.anihyou.core.model.media.link
import com.axiel7.anihyou.core.model.media.localized
import com.axiel7.anihyou.core.model.media.seasonAndYear
import com.axiel7.anihyou.core.model.media.streamingLinks
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.composables.InfoClickableItemView
import com.axiel7.anihyou.core.ui.composables.InfoItemView
import com.axiel7.anihyou.core.ui.composables.InfoTitle
import com.axiel7.anihyou.core.ui.composables.common.MoreLessButton
import com.axiel7.anihyou.core.ui.composables.common.SpoilerTagChip
import com.axiel7.anihyou.core.ui.composables.common.TagChip
import com.axiel7.anihyou.core.ui.composables.defaultPlaceholder
import com.axiel7.anihyou.core.ui.composables.media.VideoThumbnailItem
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.formatted
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.minutesToLegibleText
import com.axiel7.anihyou.core.ui.composables.media.releaseInstallmentLabel
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.secondsToLegibleText
import com.axiel7.anihyou.feature.mediadetails.MediaDetailsUiState
import com.axiel7.anihyou.feature.mediadetails.MediaDetailsEvent
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.WatchNextState
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import kotlinx.collections.immutable.persistentListOf
import java.time.ZoneId

private const val TagLimit = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaInformationView(
    uiState: MediaDetailsUiState,
    event: MediaDetailsEvent? = null,
    navigateToGenreTag: (mediaType: MediaType, genre: String?, tag: String?) -> Unit,
    navigateToStudioDetails: (Int) -> Unit,
    navigateToAnimeSeason: (AnimeSeason) -> Unit,
) {
    val context = LocalContext.current
    var showSpoiler by remember { mutableStateOf(false) }
    var showAllTags by remember { mutableStateOf(false) }
    var showNavigationProviderChooser by remember { mutableStateOf(false) }
    val isAnime = uiState.details?.basicMediaDetails?.isAnime() == true
    val navigationState = uiState.extensionNavigation
    val overviewProviders = navigationState.providers.filter {
        NavigationCapability.OVERVIEW_NAVIGATION in it.capabilities
    }
    val watchNextProviderChoices = (navigationState.watchNext as? WatchNextState.ChooseProvider)
        ?.providers.orEmpty()

    if (showNavigationProviderChooser && watchNextProviderChoices.size > 1) {
        AlertDialog(
            onDismissRequest = { showNavigationProviderChooser = false },
            title = { Text(stringResource(R.string.choose_watch_next_provider)) },
            text = {
                Column {
                    watchNextProviderChoices.forEach { provider ->
                        TextButton(
                            onClick = {
                                showNavigationProviderChooser = false
                                event?.chooseNavigationProvider(provider.key)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !navigationState.loading,
                        ) {
                            Text(provider.displayName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNavigationProviderChooser = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        InfoTitle(text = stringResource(R.string.information))

        val providerRelease = uiState.releasePresentations.firstOrNull { it.isAuthoritative }
        if (providerRelease != null) {
            val providerInfoParts = buildList {
                providerRelease.confirmedThroughEpisode?.let {
                    add(stringResource(R.string.release_schedule_confirmed_through, it))
                }
                if (providerRelease.confirmedPending > 0) {
                    add(
                        pluralStringResource(
                            R.plurals.release_schedule_pending,
                            providerRelease.confirmedPending,
                            providerRelease.confirmedPending,
                        ),
                    )
                }
                providerRelease.nextExpectedInstallment?.let { installment ->
                    val label = releaseInstallmentLabel(
                        installment = installment,
                        releaseKind = providerRelease.stream.releaseKind,
                    )
                    add(
                        providerRelease.nextForecastAt?.let { forecastAt ->
                            stringResource(
                                R.string.release_schedule_next_at,
                                label,
                                forecastAt.atZone(ZoneId.systemDefault())
                                    .toLocalDateTime()
                                    .toLocalized()
                                    .orEmpty(),
                            )
                        } ?: stringResource(R.string.release_schedule_next, label),
                    )
                }
            }
            InfoItemView(
                title = stringResource(R.string.airing),
                info = providerInfoParts.ifEmpty {
                    listOf(stringResource(R.string.release_schedule_available))
                }.joinToString(" · "),
                modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading),
            )
        } else {
            uiState.details?.nextAiringEpisode?.let { nextAiringEpisode ->
                InfoItemView(
                    title = stringResource(R.string.airing),
                    info = stringResource(
                        R.string.episode_in_time,
                        nextAiringEpisode.episode,
                        nextAiringEpisode.timeUntilAiring.toLong().secondsToLegibleText(),
                    ),
                    modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading),
                )
            }
        }
        InfoItemView(
            title = stringResource(R.string.duration),
            info = uiState.details?.duration?.toLong()?.minutesToLegibleText(),
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        InfoItemView(
            title = stringResource(R.string.start_date),
            info = uiState.details?.startDate?.fuzzyDate?.formatted(),
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        InfoItemView(
            title = stringResource(R.string.end_date),
            info = uiState.details?.endDate?.fuzzyDate?.formatted(),
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        if (isAnime) {
            InfoItemView(
                title = stringResource(R.string.season),
                info = uiState.details.seasonAndYear(),
                modifier = Modifier.clickable {
                    uiState.details.season?.let { season ->
                        uiState.details.seasonYear?.let { year ->
                            navigateToAnimeSeason(AnimeSeason(year, season))
                        }
                    }
                }
            )
        }
        InfoItemView(
            title = stringResource(R.string.source),
            info = uiState.details?.source?.localized(),
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        InfoItemView(
            title = stringResource(R.string.romaji),
            info = uiState.details?.title?.romaji,
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        InfoItemView(
            title = stringResource(R.string.english),
            info = uiState.details?.title?.english,
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        InfoItemView(
            title = stringResource(R.string.native_title),
            info = uiState.details?.title?.native,
            modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
        )
        if (!uiState.details?.synonyms.isNullOrEmpty()) {
            InfoItemView(
                title = stringResource(R.string.synonyms),
                info = uiState.details.synonyms?.joinToString("\n"),
                modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading)
            )
        }
        if (isAnime) {
            InfoClickableItemView(
                title = stringResource(R.string.studios),
                items = uiState.studios ?: persistentListOf(),
                itemName = { it.name },
                onItemClicked = { navigateToStudioDetails(it.id) }
            )
            InfoClickableItemView(
                title = stringResource(R.string.producers),
                items = uiState.producers ?: persistentListOf(),
                itemName = { it.name },
                onItemClicked = { navigateToStudioDetails(it.id) }
            )
        }

        // Tags
        InfoTitle(
            text = stringResource(R.string.tags),
            trailingIcon = {
                if (uiState.hasSpoilerTags) {
                    TextButton(onClick = { showSpoiler = !showSpoiler }) {
                        Text(
                            text = stringResource(
                                if (showSpoiler) R.string.hide_spoiler else R.string.show_spoiler
                            )
                        )
                    }
                }
            }
        )
        FlowRow(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .animateContentSize()
        ) {
            val tags = if (showAllTags) uiState.details?.tags
            else uiState.details?.tags?.take(TagLimit)
            tags?.forEach { tag ->
                if (tag != null) {
                    if (tag.isMediaSpoiler == false) {
                        TagChip(
                            name = tag.name,
                            description = tag.description,
                            rank = tag.rank,
                            onClick = {
                                uiState.details?.basicMediaDetails?.type?.let { mediaType ->
                                    navigateToGenreTag(mediaType, null, tag.name)
                                }
                            }
                        )
                    } else {
                        SpoilerTagChip(
                            name = tag.name,
                            description = tag.description,
                            rank = tag.rank,
                            visible = showSpoiler,
                            onClick = {
                                uiState.details?.basicMediaDetails?.type?.let { mediaType ->
                                    navigateToGenreTag(mediaType, null, tag.name)
                                }
                            }
                        )
                    }
                }
            }
        }//: FlowRow

        if ((uiState.details?.tags?.size ?: 0) > TagLimit) {
            MoreLessButton(
                isExpanded = showAllTags,
                onClick = { showAllTags = !showAllTags },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Trailer
        uiState.details?.trailer?.let { trailer ->
            InfoTitle(text = stringResource(R.string.trailer))
            VideoThumbnailItem(
                imageUrl = trailer.thumbnail,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onClick = {
                    trailer.link()?.let { context.openActionView(it) }
                }
            )
        }

        // Streaming episodes
        if (!uiState.details?.streamingEpisodes.isNullOrEmpty()) {
            // Next episode to watch
            uiState.details.mediaListEntry?.basicMediaListEntry?.progress?.let { progress ->
                if (progress > 0) {
                    uiState.details.streamingEpisodes
                        ?.find { it?.episodeNumber() == progress + 1 }
                        ?.let { nextEpisode ->
                            InfoTitle(text = stringResource(R.string.continue_watching))
                            EpisodeItem(
                                item = nextEpisode,
                                modifier = Modifier.padding(start = 8.dp),
                                onClick = {
                                    nextEpisode.url?.let { context.openActionView(it) }
                                }
                            )
                        }
                }
            }
            InfoTitle(text = stringResource(R.string.episodes))
            LazyRow(
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(uiState.details.streamingEpisodes.orEmpty()) { item ->
                    EpisodeItem(
                        item = item,
                        onClick = {
                            item?.url?.let { context.openActionView(it) }
                        }
                    )
                }
            }
        }

        // Streaming links
        uiState.details?.streamingLinks()?.let { streamingLinks ->
            if (streamingLinks.isNotEmpty()) {
                InfoTitle(text = stringResource(R.string.streaming_sites))
                FlowRow(
                    modifier = Modifier.padding(
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 8.dp
                    )
                ) {
                    streamingLinks.forEach { link ->
                        AssistChip(
                            onClick = { link.url?.let { context.openActionView(it) } },
                            label = { Text(text = link.site) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            trailingIcon = {
                                link.languageShort()?.let { lang ->
                                    Text(text = lang)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Product extension navigation is independent from AniList's metadata links above.
        // Only the signed provider display name is shown; this view never renders episode URLs.
        if (uiState.details != null) {
            InfoTitle(text = stringResource(R.string.provider_sources))
            if (overviewProviders.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                ) {
                    overviewProviders.forEach { provider ->
                        AssistChip(
                            onClick = { event?.openProviderOverview(provider.key) },
                            enabled = !navigationState.loading,
                            label = { Text(provider.displayName) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            if (watchNextProviderChoices.size > 1) {
                TextButton(
                    onClick = { showNavigationProviderChooser = true },
                    enabled = !navigationState.loading,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.choose_watch_next_provider))
                }
            }
            if (navigationState.loading) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(stringResource(R.string.provider_navigation_loading))
                }
            } else {
                val unavailableReason = navigationState.failure
                    ?: (navigationState.watchNext as? WatchNextState.Unavailable)
                        ?.reason
                        ?.takeUnless { it == NavigationUnavailableReason.CHOOSE_PROVIDER }
                if (unavailableReason != null) {
                    Text(
                        text = unavailableReason.localizedNavigationMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // External links
        uiState.details?.externalLinks()?.let { externalLinks ->
            if (externalLinks.isNotEmpty()) {
                InfoTitle(text = stringResource(R.string.external_links))
                FlowRow(
                    modifier = Modifier.padding(
                        start = 8.dp,
                        end = 8.dp,
                        bottom = 8.dp
                    )
                ) {
                    externalLinks.forEach { link ->
                        AssistChip(
                            onClick = { link.url?.let { context.openActionView(it) } },
                            label = { Text(text = link.site) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                            trailingIcon = {
                                link.languageShort()?.let { lang ->
                                    Text(text = lang)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Openings/Endings
        var showMusicSheet by remember { mutableStateOf(false) }
        var selectedSong by remember { mutableStateOf<String?>(null) }

        if (showMusicSheet && selectedSong != null) {
            MusicStreamingSheet(
                songTitle = selectedSong.orEmpty(),
                bottomPadding = WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding(),
                onDismiss = {
                    showMusicSheet = false
                    selectedSong = null
                }
            )
        }
        if (!uiState.openings.isNullOrEmpty()) {
            InfoTitle(text = stringResource(R.string.openings))

            uiState.openings.forEach { theme ->
                Text(
                    text = theme.text,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable {
                            selectedSong = theme.text
                            showMusicSheet = true
                        }
                        .padding(vertical = 4.dp)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!uiState.endings.isNullOrEmpty()) {
            InfoTitle(text = stringResource(R.string.endings))

            uiState.endings.forEach { theme ->
                Text(
                    text = theme.text,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable {
                            selectedSong = theme.text
                            showMusicSheet = true
                        }
                        .padding(vertical = 4.dp)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }//: Column
}

@Preview
@Composable
private fun MediaInformationViewPreview() {
    AniHyouTheme {
        Surface {
            MediaInformationView(
                uiState = MediaDetailsUiState(),
                event = null,
                navigateToGenreTag = { _, _, _ -> },
                navigateToStudioDetails = {},
                navigateToAnimeSeason = {}
            )
        }
    }
}

@Composable
private fun NavigationUnavailableReason.localizedNavigationMessage(): String = stringResource(
    when (this) {
        NavigationUnavailableReason.NO_ACTIVE_SOURCE -> R.string.navigation_no_active_source
        NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE -> R.string.navigation_release_source_unavailable
        NavigationUnavailableReason.NO_PROVIDERS,
        NavigationUnavailableReason.PROVIDER_UNAVAILABLE -> R.string.navigation_provider_unavailable
        NavigationUnavailableReason.CHOOSE_PROVIDER -> R.string.navigation_choose_provider
        NavigationUnavailableReason.NO_RELEASED_UNWATCHED -> R.string.navigation_no_released_episode
        NavigationUnavailableReason.MISSING_MAPPING -> R.string.navigation_missing_mapping
        NavigationUnavailableReason.TRACK_UNAVAILABLE -> R.string.navigation_track_unavailable
        NavigationUnavailableReason.INVALID_TARGET -> R.string.navigation_invalid_target
        NavigationUnavailableReason.STALE_RESULT -> R.string.navigation_stale_result
        NavigationUnavailableReason.LAUNCH_FAILED -> R.string.navigation_launch_failed
    }
)

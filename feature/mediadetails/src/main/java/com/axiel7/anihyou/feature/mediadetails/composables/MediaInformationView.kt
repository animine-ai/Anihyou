package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.annotation.VisibleForTesting
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
import com.axiel7.anihyou.core.ui.composables.media.nextEpisodeText
import com.axiel7.anihyou.core.ui.composables.media.releaseInstallmentLabel
import com.axiel7.anihyou.feature.mediadetails.MediaDetailsUiState
import com.axiel7.anihyou.feature.mediadetails.MediaDetailsEvent
import com.axiel7.anihyou.feature.mediadetails.EpisodeMappingSaveState
import com.axiel7.anihyou.feature.mediadetails.isValidProviderSeriesKey
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import kotlinx.collections.immutable.persistentListOf
import java.time.ZoneId

private const val TagLimit = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaInformationView(
    uiState: MediaDetailsUiState,
    navigateToGenreTag: (mediaType: MediaType, genre: String?, tag: String?) -> Unit,
    navigateToStudioDetails: (Int) -> Unit,
    navigateToAnimeSeason: (AnimeSeason) -> Unit,
    event: MediaDetailsEvent? = null,
) {
    val context = LocalContext.current
    var showSpoiler by remember { mutableStateOf(false) }
    var showAllTags by remember { mutableStateOf(false) }
    val isAnime = uiState.details?.basicMediaDetails?.isAnime() == true
    val navigationState = uiState.extensionNavigation

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        InfoTitle(text = stringResource(R.string.information))

        val nextEpisode = nextEpisodeText(
            presentations = uiState.releasePresentations,
            fallbackEpisode = uiState.details?.nextAiringEpisode?.episode,
            fallbackSeconds = uiState.details?.nextAiringEpisode?.timeUntilAiring?.toLong(),
        )
        if (nextEpisode != null) {
            InfoItemView(
                title = stringResource(R.string.airing),
                info = nextEpisode,
                modifier = Modifier.defaultPlaceholder(visible = uiState.isLoading),
            )
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
            ProviderOverviewField(
                navigationState = navigationState,
                onOpenProvider = { event?.openProviderOverview(it) },
                onChooseProvider = { event?.chooseNavigationProvider(it) },
            )

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

@VisibleForTesting
@Composable
fun ProviderEpisodeMappingDialog(
    provider: NavigationProvider,
    mediaId: Int,
    saveState: EpisodeMappingSaveState,
    onDismiss: () -> Unit,
    onSave: (seriesKey: String, providerSeason: Int, providerFirst: Int, anilistFirst: Int, count: Int) -> Unit,
) {
    var seriesKey by rememberSaveable(provider.key) { mutableStateOf("") }
    var providerSeason by rememberSaveable(provider.key) { mutableStateOf("") }
    var providerFirst by rememberSaveable(provider.key) { mutableStateOf("") }
    var anilistFirst by rememberSaveable(provider.key) { mutableStateOf("") }
    var episodeCount by rememberSaveable(provider.key) { mutableStateOf("") }
    val values = parseEpisodeMappingInput(
        mediaId = mediaId,
        seriesKey = seriesKey,
        providerSeason = providerSeason,
        providerFirst = providerFirst,
        anilistFirst = anilistFirst,
        episodeCount = episodeCount,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.episode_mapping_dialog_title, provider.displayName))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.episode_mapping_help))
                OutlinedTextField(
                    value = seriesKey,
                    onValueChange = { seriesKey = it },
                    modifier = Modifier.fillMaxWidth().testTag("provider-episode-series-key"),
                    label = { Text(stringResource(R.string.episode_mapping_series_key)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (seriesKey.isNotEmpty() && !isValidProviderSeriesKey(seriesKey))
                                    R.string.episode_mapping_invalid_series_key
                                else R.string.episode_mapping_series_key_help
                            )
                        )
                    },
                    isError = seriesKey.isNotEmpty() && !isValidProviderSeriesKey(seriesKey),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )
                MappingNumberField(
                    value = providerSeason,
                    onValueChange = { providerSeason = it },
                    label = stringResource(R.string.episode_mapping_provider_season),
                    testTag = "provider-episode-season",
                )
                MappingNumberField(
                    value = providerFirst,
                    onValueChange = { providerFirst = it },
                    label = stringResource(R.string.episode_mapping_provider_episode_first),
                    testTag = "provider-episode-provider-first",
                )
                MappingNumberField(
                    value = anilistFirst,
                    onValueChange = { anilistFirst = it },
                    label = stringResource(R.string.episode_mapping_anilist_episode_first),
                    testTag = "provider-episode-anilist-first",
                )
                MappingNumberField(
                    value = episodeCount,
                    onValueChange = { episodeCount = it },
                    label = stringResource(R.string.episode_mapping_episode_count),
                    testTag = "provider-episode-count",
                )
                if (listOf(providerSeason, providerFirst, anilistFirst, episodeCount).any { it.isNotEmpty() } && values == null) {
                    Text(
                        text = stringResource(R.string.episode_mapping_validation),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (saveState == EpisodeMappingSaveState.FAILED) {
                    Text(
                        text = stringResource(R.string.episode_mapping_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    values?.let { onSave(seriesKey, it.providerSeason, it.providerFirst, it.anilistFirst, it.count) }
                },
                enabled = values != null && saveState != EpisodeMappingSaveState.SAVING,
            ) {
                Text(
                    stringResource(
                        if (saveState == EpisodeMappingSaveState.SAVING) R.string.episode_mapping_saving
                        else R.string.save
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun MappingNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun MappingStatusText(stringId: Int) {
    Text(
        text = stringResource(stringId),
        style = MaterialTheme.typography.bodySmall,
        color = if (stringId == R.string.episode_mapping_save_failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private data class EpisodeMappingInput(
    val providerSeason: Int,
    val providerFirst: Int,
    val anilistFirst: Int,
    val count: Int,
)

private fun parseEpisodeMappingInput(
    mediaId: Int,
    seriesKey: String,
    providerSeason: String,
    providerFirst: String,
    anilistFirst: String,
    episodeCount: String,
): EpisodeMappingInput? {
    if (mediaId <= 0 || !isValidProviderSeriesKey(seriesKey)) return null
    val season = providerSeason.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
    val firstProvider = providerFirst.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
    val firstAniList = anilistFirst.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
    val count = episodeCount.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
    if (firstProvider.toLong() + count - 1 > 9999 || firstAniList.toLong() + count - 1 > 9999) return null
    return EpisodeMappingInput(season, firstProvider, firstAniList, count)
}

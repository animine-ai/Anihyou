package com.axiel7.anihyou.feature.explore.explore.content

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.base.UNKNOWN_CHAR
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.core.network.fragment.BasicMediaDetails
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.LocalBlurAdult
import com.axiel7.anihyou.core.ui.composables.list.DiscoverLazyRow
import com.axiel7.anihyou.core.ui.composables.list.HorizontalListHeader
import com.axiel7.anihyou.core.ui.composables.media.AiringAnimeHorizontalItem
import com.axiel7.anihyou.core.ui.composables.media.AiringAnimeHorizontalItemPlaceholder
import com.axiel7.anihyou.core.ui.composables.media.MEDIA_POSTER_SMALL_HEIGHT
import com.axiel7.anihyou.core.ui.utils.ComposeDateUtils.secondsToLegibleText

@Composable
fun AiringContent(
    airingOnMyList: Boolean?,
    airingAnime: SnapshotStateList<ExploreMedia>,
    airingAnimeOnMyList: SnapshotStateList<ExploreMedia>,
    releaseByMediaId: Map<Int, List<ReleaseUiPresentation>> = emptyMap(),
    providerAiringRows: List<ReleaseUiCalendarItem> = emptyList(),
    providerAiringMedia: Map<Int, ExploreMedia> = emptyMap(),
    isLoadingProviderAiring: Boolean = false,
    displayAdult: Boolean = false,
    isLoading: Boolean,
    onLongClickItem: (BasicMediaDetails, BasicMediaListEntry?) -> Unit,
    navigateToCalendar: () -> Unit,
    navigateToMediaDetails: (mediaId: Int) -> Unit
) {
    val blurAdult = LocalBlurAdult.current
    HorizontalListHeader(
        text = stringResource(R.string.airing_soon),
        onClick = navigateToCalendar
    )
    if (airingOnMyList != null && providerAiringRows.isNotEmpty()) {
        ProviderOwnedAiringContent(
            providerRows = providerAiringRows,
            metadata = providerAiringMedia,
            displayAdult = displayAdult,
            onMyList = airingOnMyList,
            isLoading = isLoadingProviderAiring,
            onLongClickItem = onLongClickItem,
            navigateToMediaDetails = navigateToMediaDetails,
        )
        return
    }
    when (airingOnMyList) {
        true -> {
            DiscoverLazyRow(
                minHeight = MEDIA_POSTER_SMALL_HEIGHT.dp
            ) {
                items(
                    items = airingAnimeOnMyList.providerOrdered(releaseByMediaId),
                    contentType = { it }
                ) { item ->
                    AiringAnimeHorizontalItem(
                        title = item.basicMediaDetails.title?.userPreferred.orEmpty(),
                        subtitle = if (releaseByMediaId[item.id].orEmpty().any { it.isAuthoritative }) {
                            ""
                        } else {
                            stringResource(
                                R.string.airing_in,
                                item.nextAiringEpisode?.timeUntilAiring?.toLong()
                                    ?.secondsToLegibleText() ?: UNKNOWN_CHAR
                            )
                        },
                        releasePresentations = releaseByMediaId[item.id].orEmpty(),
                        blurImage = blurAdult && item.basicMediaDetails.isAdult == true,
                        imageUrl = item.coverImage?.large,
                        score = item.averageScore,
                        status = item.mediaListEntry?.basicMediaListEntry?.status,
                        progress = item.mediaListEntry?.basicMediaListEntry?.progress,
                        onClick = {
                            navigateToMediaDetails(item.id)
                        },
                        onLongClick = {
                            onLongClickItem(
                                item.basicMediaDetails,
                                item.mediaListEntry?.basicMediaListEntry
                            )
                        }
                    )
                }
                if (isLoading) {
                    items(10) {
                        AiringAnimeHorizontalItemPlaceholder()
                    }
                }
            }//:LazyRow
        }

        false -> {
            DiscoverLazyRow(
                minHeight = MEDIA_POSTER_SMALL_HEIGHT.dp
            ) {
                items(
                    items = airingAnime.providerOrdered(releaseByMediaId),
                    contentType = { it }
                ) { item ->
                    AiringAnimeHorizontalItem(
                        title = item.basicMediaDetails.title?.userPreferred.orEmpty(),
                        subtitle = if (releaseByMediaId[item.id].orEmpty().any { it.isAuthoritative }) {
                            ""
                        } else {
                            stringResource(
                                R.string.airing_in,
                                item.nextAiringEpisode?.timeUntilAiring?.toLong()
                                    ?.secondsToLegibleText() ?: UNKNOWN_CHAR
                            )
                        },
                        releasePresentations = releaseByMediaId[item.id].orEmpty(),
                        imageUrl = item.coverImage?.large,
                        score = item.averageScore,
                        status = item.mediaListEntry?.basicMediaListEntry?.status,
                        progress = item.mediaListEntry?.basicMediaListEntry?.progress,
                        onClick = { item.id.let(navigateToMediaDetails) },
                        onLongClick = {
                            onLongClickItem(
                                item.basicMediaDetails,
                                item.mediaListEntry?.basicMediaListEntry
                            )
                        }
                    )
                }
                if (isLoading) {
                    items(10) {
                        AiringAnimeHorizontalItemPlaceholder()
                    }
                }
                if (airingAnime.isEmpty()) {
                    item {
                        Text(text = stringResource(R.string.no_information))
                    }
                }
            }//:LazyRow
        }

        else -> {
            DiscoverLazyRow(
                minHeight = MEDIA_POSTER_SMALL_HEIGHT.dp
            ) {
                items(10) {
                    AiringAnimeHorizontalItemPlaceholder()
                }
            }
        }
    }
}

internal data class ProviderAiringGroup(
    val media: ExploreMedia,
    val rows: List<ReleaseUiCalendarItem>,
)

/** Unknown metadata cannot prove membership or produce a usable anime card. The view model loads all mapped IDs. */
internal fun providerAiringGroups(
    rows: List<ReleaseUiCalendarItem>,
    metadata: Map<Int, ExploreMedia>,
    onMyList: Boolean,
    displayAdult: Boolean,
): List<ProviderAiringGroup> = rows.mapNotNull { row ->
    if (!row.isAuthoritative) return@mapNotNull null
    val media = row.mediaId?.let(metadata::get) ?: return@mapNotNull null
    if (media.basicMediaDetails.title?.userPreferred.isNullOrBlank()) return@mapNotNull null
    if (!displayAdult && media.basicMediaDetails.isAdult == true) return@mapNotNull null
    if ((media.mediaListEntry != null) != onMyList) return@mapNotNull null
    ProviderAiringGroup(media, listOf(row))
}.distinctBy { it.rows.single().eventKey }

@Composable
private fun ProviderOwnedAiringContent(
    providerRows: List<ReleaseUiCalendarItem>,
    metadata: Map<Int, ExploreMedia>,
    displayAdult: Boolean,
    onMyList: Boolean,
    isLoading: Boolean,
    onLongClickItem: (BasicMediaDetails, BasicMediaListEntry?) -> Unit,
    navigateToMediaDetails: (mediaId: Int) -> Unit,
) {
    val blurAdult = LocalBlurAdult.current
    val groups = providerAiringGroups(providerRows, metadata, onMyList, displayAdult)

    DiscoverLazyRow(
        minHeight = MEDIA_POSTER_SMALL_HEIGHT.dp,
    ) {
        items(
            items = groups,
            key = { group -> group.rows.single().eventKey },
            contentType = { "provider-airing" },
        ) { group ->
            val item = group.media
            AiringAnimeHorizontalItem(
                title = item.basicMediaDetails.title?.userPreferred.orEmpty(),
                subtitle = "",
                releaseCalendarPresentations = group.rows,
                blurImage = blurAdult && item.basicMediaDetails.isAdult == true,
                imageUrl = item.coverImage?.large,
                score = item.averageScore,
                status = item.mediaListEntry?.basicMediaListEntry?.status,
                onClick = { navigateToMediaDetails(item.id) },
                onLongClick = {
                    onLongClickItem(item.basicMediaDetails, item.mediaListEntry?.basicMediaListEntry)
                },
            )
        }
        if (isLoading) {
            items(10) {
                AiringAnimeHorizontalItemPlaceholder()
            }
        }
        if (groups.isEmpty() && !isLoading) {
            item {
                Text(text = stringResource(R.string.no_information))
            }
        }
    }
}

private fun List<ExploreMedia>.providerOrdered(
    releaseByMediaId: Map<Int, List<ReleaseUiPresentation>>,
): List<ExploreMedia> = sortedWith(
    compareBy<ExploreMedia> { media ->
        releaseByMediaId[media.id]
            .orEmpty()
            .minOfOrNull { it.nextForecastAt ?: java.time.Instant.MAX }
            ?: java.time.Instant.MAX
    }.thenBy { it.id },
)
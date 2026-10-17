package com.axiel7.anihyou.feature.usermedialist

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.model.media.episodesBehind
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.api.ReleaseUiSelection
import com.axiel7.anihyou.release.core.api.pendingForOrNull

/**
 * The anime being watched in two sections: what can be watched now on top, what waits for its next episode below.
 * An entry moves from the one to the other by itself when its progress changes; the list animates the move.
 */
internal class WatchGrouping(
    private val mediaType: MediaType,
    private val releaseByMediaId: Map<Int, List<ReleaseUiPresentation>>,
) {
    val readyTitle: Int get() = if (mediaType == MediaType.MANGA) R.string.list_group_ready_manga else R.string.list_group_ready

    /**
     * Anime: behind means episodes out and not watched, the source decides where it can, AniList otherwise.
     * Manga: chapters known to exist beyond the progress; a series with no known chapter count is waiting.
     */
    fun isReady(entry: CommonMediaListEntry): Boolean {
        if (mediaType == MediaType.MANGA) {
            val total = entry.media?.basicMediaDetails?.chapters ?: return false
            return (entry.basicMediaListEntry.progress ?: 0) < total
        }
        val release = ReleaseUiSelection.effective(releaseByMediaId[entry.mediaId].orEmpty())
        val pending = release?.pendingForOrNull(entry.basicMediaListEntry.progress) ?: entry.episodesBehind()
        return pending > 0
    }

    /** Null when everything is on one side: a lone header over every row says nothing. */
    fun split(entries: List<CommonMediaListEntry>): Pair<List<CommonMediaListEntry>, List<CommonMediaListEntry>>? {
        val (ready, waiting) = entries.partition(::isReady)
        return if (ready.isEmpty() || waiting.isEmpty()) null else ready to waiting
    }
}

internal fun watchGrouping(uiState: UserMediaListUiState): WatchGrouping? =
    if (uiState.status == MediaListStatus.CURRENT && uiState.isMyList)
        WatchGrouping(uiState.mediaType, uiState.releaseByMediaId) else null

@Composable
internal fun WatchSectionHeader(title: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(title), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
        )
        Text(text = count.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Entries keyed by media, so a row that changes section slides there instead of vanishing and reappearing. */
internal fun LazyListScope.sectionedItems(
    entries: List<CommonMediaListEntry>,
    grouping: WatchGrouping?,
    content: @Composable (CommonMediaListEntry) -> Unit,
) {
    val unique = entries.distinctBy { it.mediaId }
    val sections = grouping?.split(unique)
    val animation = spring<IntOffset>(stiffness = 400f)
    fun LazyListScope.rows(list: List<CommonMediaListEntry>) = items(items = list, key = { it.mediaId }, contentType = { "entry" }) { entry ->
        Column(Modifier.animateItem(placementSpec = animation)) { content(entry) }
    }
    if (sections == null) {
        rows(unique)
    } else {
        item(key = "header_ready", contentType = "header") {
            WatchSectionHeader(requireNotNull(grouping).readyTitle, sections.first.size, Modifier.animateItem(placementSpec = animation))
        }
        rows(sections.first)
        item(key = "header_waiting", contentType = "header") {
            WatchSectionHeader(R.string.list_group_waiting, sections.second.size, Modifier.animateItem(placementSpec = animation))
        }
        rows(sections.second)
    }
}

internal fun LazyGridScope.sectionedItems(
    entries: List<CommonMediaListEntry>,
    grouping: WatchGrouping?,
    content: @Composable (CommonMediaListEntry) -> Unit,
) {
    val unique = entries.distinctBy { it.mediaId }
    val sections = grouping?.split(unique)
    val animation = spring<IntOffset>(stiffness = 400f)
    fun LazyGridScope.rows(list: List<CommonMediaListEntry>) = items(items = list, key = { it.mediaId }, contentType = { "entry" }) { entry ->
        Column(Modifier.animateItem(placementSpec = animation)) { content(entry) }
    }
    if (sections == null) {
        rows(unique)
    } else {
        item(key = "header_ready", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            WatchSectionHeader(requireNotNull(grouping).readyTitle, sections.first.size, Modifier.animateItem(placementSpec = animation))
        }
        rows(sections.first)
        item(key = "header_waiting", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            WatchSectionHeader(R.string.list_group_waiting, sections.second.size, Modifier.animateItem(placementSpec = animation))
        }
        rows(sections.second)
    }
}

package com.axiel7.anihyou.feature.calendar.grid

import androidx.compose.runtime.Stable
import com.axiel7.anihyou.core.base.state.PagedUiState
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.feature.calendar.CalendarRow
import com.axiel7.anihyou.feature.calendar.CalendarUiState
import com.axiel7.anihyou.feature.calendar.presentationDays
import com.axiel7.anihyou.feature.calendar.providerOnlyByDate
import com.axiel7.anihyou.feature.calendar.providerRowsByDate
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import java.time.LocalDate

/**
 * One weekday tab of the current week. It holds what the list calendar holds for a day: the AniList airing events of
 * that day, the rows of the active release source, and the cover and title of the entries a source names that no loaded
 * AniList day holds. [rows] turns them into what the tab shows, by the same rule as the list.
 */
@Stable
data class CalendarGridUiState(
    /** 1 (Monday) to 7 (Sunday); 0 until the tab says which one it is. */
    val weekday: Int = 0,
    /** The date of this weekday in the current week. */
    val date: LocalDate = LocalDate.of(2000, 1, 1),
    val today: LocalDate = LocalDate.of(2000, 1, 1),
    val onMyList: Boolean? = null,
    /** Only matters while a release source supplies rows: then AniList entries without a source match stay hidden unless on. */
    val showAniListExtras: Boolean = false,
    val combineSimultaneousTracks: Boolean = true,
    val airingEvents: List<CalendarAiringEvent> = emptyList(),
    /** The rows of the source around today, not only this day: whether a source is the main calendar depends on all of them. */
    val releaseRows: List<ReleaseUiCalendarItem> = emptyList(),
    val extraMedia: Map<Int, ExploreMedia> = emptyMap(),
    val selectedItem: ExploreMedia? = null,
    val fetchFromNetwork: Boolean = false,
    /** Changes whenever the day has to be asked for again (refresh, list filter), so an unchanged page number does not hide it. */
    val loadId: Int = 0,
    override val page: Int = 1,
    override val hasNextPage: Boolean = true,
    override val error: String? = null,
    override val isLoading: Boolean = true,
) : PagedUiState() {
    override fun setError(value: String?) = copy(error = value)
    override fun setLoading(value: Boolean) = copy(isLoading = value)
    override fun setPage(value: Int) = copy(page = value)
    override fun setHasNextPage(value: Boolean) = copy(hasNextPage = value)
}

/** The rows of this tab's day: the list calendar's rule for one date, so the tabs and the list never disagree. */
internal fun CalendarGridUiState.rows(): List<CalendarRow> {
    if (weekday == 0) return emptyList()
    return CalendarUiState(
        onMyList = onMyList,
        showAniListExtras = showAniListExtras,
        combineSimultaneousTracks = combineSimultaneousTracks,
        today = today,
        day = date.atStartOfDay(),
        weeklyAnime = mutableMapOf(date to airingEvents),
        extraMedia = extraMedia,
        releaseCalendarRows = releaseRows,
        // A row without a date of its own belongs to today only, never to every tab.
        providerRowsByDate = releaseRows.providerRowsByDate(fallbackDate = today),
        providerOnlyByDate = releaseRows.providerOnlyByDate(
            knownMediaIds = airingEvents.mapTo(HashSet()) { it.media.id },
            fallbackDate = today,
        ),
        isLoading = isLoading,
    ).presentationDays().firstOrNull { it.date == date }?.rows.orEmpty()
}

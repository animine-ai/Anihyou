package com.axiel7.anihyou.feature.calendar

import androidx.compose.runtime.Stable
import com.axiel7.anihyou.core.base.state.PagedUiState
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import java.time.LocalDate
import java.time.LocalDateTime

@Stable
data class CalendarUiState(
    val onMyList: Boolean? = null,
    /** Only matters while a release source supplies rows: then AniList entries without a source match are hidden unless this is on. */
    val showAniListExtras: Boolean = false,
    val listStyle: ListStyle? = null,
    val selectedItem: ExploreMedia? = null,
    val todayFirstItemIndex: Int = 0,
    val todayAnchorReady: Boolean = false,
    val autoScrollToToday: Boolean = true,
    val today: LocalDate = LocalDate.of(2000, 1, 1),
    val day: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0),
    val weeklyAnime: MutableMap<LocalDate, List<CalendarAiringEvent>> = mutableMapOf(),
    val releaseCalendarRows: List<ReleaseUiCalendarItem> = emptyList(),
    val providerRowsByDate: Map<LocalDate, List<ReleaseUiCalendarItem>> = emptyMap(),
    val releaseByMediaId: Map<Int, List<ReleaseUiCalendarItem>> = emptyMap(),
    val providerOnlyByDate: Map<LocalDate, List<ReleaseUiCalendarItem>> = emptyMap(),
    val fetchFromNetwork: Boolean = false,
    override val page: Int = 1,
    override val hasNextPage: Boolean = true,
    override val isLoading: Boolean = true,
    override val error: String? = null,
) : PagedUiState() {
    override fun setError(value: String?) = copy(error = value)
    override fun setLoading(value: Boolean) = copy(isLoading = value)
    override fun setPage(value: Int) = copy(page = value)
    override fun setHasNextPage(value: Boolean) = copy(hasNextPage = value)
}

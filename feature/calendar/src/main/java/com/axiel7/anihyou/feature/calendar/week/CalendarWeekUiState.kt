package com.axiel7.anihyou.feature.calendar.week

import androidx.compose.runtime.Stable
import com.axiel7.anihyou.core.base.state.UiState
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.feature.calendar.CalendarRow
import com.axiel7.anihyou.feature.calendar.CalendarUiState
import com.axiel7.anihyou.feature.calendar.presentationDays
import com.axiel7.anihyou.feature.calendar.providerOnlyByDate
import com.axiel7.anihyou.feature.calendar.providerRowsByDate
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import java.time.DayOfWeek
import java.time.LocalDate

/** The Monday of the week that holds [date]. */
internal fun weekStartOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/**
 * One week of the calendar with the day the user looks at. It holds what the list calendar holds for each day: the AniList
 * airing events, the rows of the active release source, and cover and title of entries a source names that no loaded
 * AniList day holds. [rowsByDay] turns them into what the strip counts and the list shows, by the same rule as the list.
 */
@Stable
data class CalendarWeekUiState(
    val weekStart: LocalDate = weekStartOf(LocalDate.of(2000, 1, 3)),
    val selectedDate: LocalDate = LocalDate.of(2000, 1, 3),
    val today: LocalDate = LocalDate.of(2000, 1, 3),
    val onMyList: Boolean? = null,
    /** Only matters while a release source supplies rows: then AniList entries without a source match stay hidden unless on. */
    val showAniListExtras: Boolean = false,
    val combineSimultaneousTracks: Boolean = true,
    /** The AniList airing events by the day they were asked for. */
    val airingEvents: Map<LocalDate, List<CalendarAiringEvent>> = emptyMap(),
    /** The days of the shown week whose AniList events are complete; the strip shows no count for the others. */
    val loadedDays: Set<LocalDate> = emptySet(),
    /** The rows of the source around today and around the shown week. */
    val releaseRows: List<ReleaseUiCalendarItem> = emptyList(),
    val extraMedia: Map<Int, ExploreMedia> = emptyMap(),
    val selectedItem: ExploreMedia? = null,
    val fetchFromNetwork: Boolean = false,
    override val isLoading: Boolean = true,
    override val error: String? = null,
) : UiState() {
    override fun setError(value: String?) = copy(error = value)
    override fun setLoading(value: Boolean) = copy(isLoading = value)

    /** The seven dates of the shown week, Monday first. */
    val days: List<LocalDate> get() = (0L..6L).map { weekStart.plusDays(it) }

    /** Whether the AniList events of the selected day are complete. */
    val selectedDayLoaded: Boolean get() = selectedDate in loadedDays
}

/** The rows of every day of the shown week: the list calendar's rule applied to each date, so the styles never disagree. */
internal fun CalendarWeekUiState.rowsByDay(): Map<LocalDate, List<CalendarRow>> {
    val knownMediaIds = airingEvents.values.asSequence().flatten().mapTo(HashSet()) { it.media.id }
    val presented = CalendarUiState(
        onMyList = onMyList,
        showAniListExtras = showAniListExtras,
        combineSimultaneousTracks = combineSimultaneousTracks,
        today = today,
        day = selectedDate.atStartOfDay(),
        weeklyAnime = airingEvents.toMutableMap(),
        extraMedia = extraMedia,
        releaseCalendarRows = releaseRows,
        // A row without a date of its own belongs to today only, never to every day of the week.
        providerRowsByDate = releaseRows.providerRowsByDate(fallbackDate = today),
        providerOnlyByDate = releaseRows.providerOnlyByDate(knownMediaIds = knownMediaIds, fallbackDate = today),
        isLoading = isLoading,
    ).presentationDays().associate { it.date to it.rows }
    return days.associateWith { presented[it].orEmpty() }
}

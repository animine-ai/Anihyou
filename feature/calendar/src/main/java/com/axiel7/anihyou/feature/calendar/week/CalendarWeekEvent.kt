package com.axiel7.anihyou.feature.calendar.week

import androidx.compose.runtime.Immutable
import com.axiel7.anihyou.core.base.event.UiEvent
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import java.time.LocalDate

@Immutable
interface CalendarWeekEvent : UiEvent {
    fun selectDay(date: LocalDate)
    fun previousWeek()
    fun nextWeek()
    /** Back to the week of today with today selected. */
    fun goToToday()
    fun onUpdateListEntry(newListEntry: BasicMediaListEntry?)
    fun selectItem(value: ExploreMedia?)
    /** Pull to refresh: AniList asks the network again for the shown week and the active source is refreshed. */
    fun refresh()
}

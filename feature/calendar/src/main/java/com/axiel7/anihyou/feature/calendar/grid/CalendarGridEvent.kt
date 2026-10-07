package com.axiel7.anihyou.feature.calendar.grid

import androidx.compose.runtime.Immutable
import com.axiel7.anihyou.core.base.event.PagedEvent
import com.axiel7.anihyou.core.base.event.UiEvent
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia

@Immutable
interface CalendarGridEvent : UiEvent, PagedEvent {
    fun onUpdateListEntry(newListEntry: BasicMediaListEntry?)
    fun selectItem(value: ExploreMedia?)
    /** Pull to refresh of the tab: AniList asks the network again for this day and the active source is refreshed. */
    fun refresh()
}

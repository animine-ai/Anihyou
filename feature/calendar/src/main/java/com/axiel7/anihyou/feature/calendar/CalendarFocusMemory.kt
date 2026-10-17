package com.axiel7.anihyou.feature.calendar

import java.time.LocalDate

/**
 * What the calendar remembers for as long as the app process lives. The calendar focuses today when the app is opened, not
 * every time the screen comes back (a tab change, a sub menu, a style change): later visits stay where the user was.
 * A new process starts empty, so opening the app focuses today again.
 */
internal object CalendarFocusMemory {
    /** The row the list calendar showed at the top: its key and how far the user had scrolled into it. */
    data class ListAnchor(val key: String, val offset: Int)

    /** Whether the list calendar has focused today in this process. */
    @Volatile var todayFocused: Boolean = false

    @Volatile var listAnchor: ListAnchor? = null

    /** The weekday tab of the tab calendar the user was on, 0 is Monday. */
    @Volatile var tabsPage: Int? = null

    /** The day the week calendar showed; null means today. */
    @Volatile var weekSelected: LocalDate? = null

    fun reset() {
        todayFocused = false
        listAnchor = null
        tabsPage = null
        weekSelected = null
    }
}

package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.core.model.CalendarStyle
import com.axiel7.anihyou.core.model.ListStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which calendar style is shown: the stored choice, or what the older two-way switch said. */
class CalendarStyleTest {
    @Test fun aStoredStyleWins() {
        CalendarStyle.entries.forEach { style ->
            assertEquals(style, CalendarStyle.from(style.name, ListStyle.GRID))
            assertEquals(style, CalendarStyle.from(style.name, ListStyle.STANDARD))
        }
    }

    @Test fun withoutAChoiceTheOlderSwitchDecides() {
        assertEquals(CalendarStyle.TABS, CalendarStyle.from("", ListStyle.GRID))
        assertEquals(CalendarStyle.LIST, CalendarStyle.from("", ListStyle.STANDARD))
        assertEquals(CalendarStyle.LIST, CalendarStyle.from(null, null))
    }

    @Test fun anUnknownStoredNameFallsBackLikeNoChoice() {
        assertEquals(CalendarStyle.TABS, CalendarStyle.from("FUTURE_STYLE", ListStyle.GRID))
        assertEquals(CalendarStyle.LIST, CalendarStyle.from("FUTURE_STYLE", ListStyle.STANDARD))
    }

    @Test fun everyStyleHasItsOwnLabel() {
        assertEquals(CalendarStyle.entries.size, CalendarStyle.entries.map { it.stringRes }.toSet().size)
    }
}

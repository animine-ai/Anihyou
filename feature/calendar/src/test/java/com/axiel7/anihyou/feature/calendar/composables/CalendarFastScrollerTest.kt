package com.axiel7.anihyou.feature.calendar.composables

import org.junit.Assert.assertEquals
import org.junit.Test

/** The arithmetic of the calendar's edge scroller: which day is under the finger and where the list has to jump to. */
class CalendarFastScrollerTest {
    @Test fun theFingerAtTheTopOfTheTrackIsTheFirstDayAndAtTheBottomTheLast() {
        assertEquals(0, dayIndexAt(0f, 1000f, 30))
        assertEquals(29, dayIndexAt(999f, 1000f, 30))
        assertEquals(15, dayIndexAt(500f, 1000f, 30))
    }

    @Test fun aFingerBeyondTheTrackStaysOnTheFirstOrTheLastDay() {
        assertEquals(0, dayIndexAt(-120f, 1000f, 30))
        assertEquals(29, dayIndexAt(5000f, 1000f, 30))
    }

    @Test fun anEmptyTrackOrNoDaysGiveTheFirstIndex() {
        assertEquals(0, dayIndexAt(40f, 0f, 30))
        assertEquals(0, dayIndexAt(40f, 1000f, 0))
    }

    @Test fun everyDayStartsAtItsHeaderAfterTheRowsOfTheDaysBeforeIt() {
        // Three days with 2, 0 and 3 rows: a header and the rows each.
        assertEquals(listOf(0, 3, 4), dayStartItemIndices(listOf(2, 0, 3)))
        assertEquals(emptyList<Int>(), dayStartItemIndices(emptyList()))
    }

    @Test fun anItemBelongsToTheLastDayThatStartsAtOrBeforeIt() {
        val starts = listOf(0, 3, 4)
        assertEquals(0, dayOfItemIndex(starts, 0))
        assertEquals(0, dayOfItemIndex(starts, 2))
        assertEquals(1, dayOfItemIndex(starts, 3))
        assertEquals(2, dayOfItemIndex(starts, 4))
        assertEquals(2, dayOfItemIndex(starts, 99))
        assertEquals(0, dayOfItemIndex(emptyList(), 5))
    }

    @Test fun everyFingerPositionMapsToAnItemThatMapsBackToThatDay() {
        val starts = dayStartItemIndices(List(30) { it % 4 })
        repeat(30) { day -> assertEquals(day, dayOfItemIndex(starts, starts[day])) }
    }
}

package com.axiel7.anihyou.core.ui.common.navigation

import com.axiel7.anihyou.core.model.CurrentListType
import com.axiel7.anihyou.core.ui.common.BottomDestination
import com.axiel7.anihyou.core.ui.common.BottomDestination.Companion.isBottomDestination
import com.axiel7.anihyou.core.ui.common.BottomDestination.Companion.showsBottomBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom bar rule for every route of the main navigation catalog, written down as data. */
class MainNavigationChromeTest {
    @Test fun originalRootsAndTheCalendarAndCurrentListsKeepTheBottomBar() {
        listOf(Route.Home, Route.AnimeTab, Route.MangaTab, Route.Explore, Route.Profile, Route.CalendarMain).forEach {
            assertTrue(it.toString(), it.showsBottomBar())
        }
        CurrentListType.entries.forEach {
            assertTrue(it.name, Route.CurrentListMain(it).showsBottomBar())
        }
    }

    @Test fun everyPromotedChartAndSeasonDestinationHidesTheBottomBarButStaysAMainDestination() {
        val charts = BottomDestination.catalog.map { it.route }.filterIsInstance<Route.ChartMain>()
        val seasons = BottomDestination.catalog.map { it.route }.filterIsInstance<Route.SeasonMain>()
        assertTrue("the catalog holds charts", charts.isNotEmpty())
        assertEquals(setOf(false, true), seasons.map { it.next }.toSet())
        (charts + seasons).forEach {
            assertTrue("$it is a main destination", it.isBottomDestination())
            assertFalse("$it hides the bottom bar", it.showsBottomBar())
        }
    }

    @Test fun nestedPagesNeverShowTheBottomBar() {
        listOf(Route.Calendar, Route.Settings, Route.MediaDetails(1), Route.MediaChartList("TOP_ANIME")).forEach {
            assertFalse(it.toString(), it.showsBottomBar())
        }
    }

    @Test fun everyCatalogRouteIsClassifiedExactlyOnce() {
        val shown = BottomDestination.catalog.map { it.route }.filter { it.showsBottomBar() }
        val hidden = BottomDestination.catalog.map { it.route }.filterNot { it.showsBottomBar() }
        assertEquals(BottomDestination.catalog.size, shown.size + hidden.size)
        assertTrue(hidden.all { it is Route.ChartMain || it is Route.SeasonMain })
    }
}

package com.axiel7.anihyou.core.ui.common.navigation

import com.axiel7.anihyou.core.ui.common.BottomDestination
import org.junit.Assert.*
import org.junit.Test

class MainNavigationConfigTest {
    @Test fun defaultOrderAndProfileAreExact() {
        assertEquals(listOf("home", "anime", "manga", "explore", "calendar"), MainNavigationConfig().visibleIds)
        assertFalse(MainNavigationResolver.destinations(MainNavigationConfig()).any { it.route == Route.Profile })
        assertEquals(Route.CalendarMain, MainNavigationResolver.destinations(MainNavigationConfig()).last().route)
    }

    @Test fun homeCanMoveToLastAndOtherTabsCanMoveToFirst() {
        val moved = MainNavigationConfig().move("home", 4)
        assertEquals(listOf("anime", "manga", "explore", "calendar", "home"), moved.visibleIds)
        assertEquals("calendar", moved.move("calendar", -3).visibleIds.first())
        assertTrue(moved.hide("home").visibleIds.contains("home"))
    }

    @Test fun hideShowAndShortcutsPreserveOrderAcrossCodec() {
        val config = MainNavigationConfig().hide("manga").hide("anime").show("current_behind").show("season_next")
        assertEquals(config, MainNavigationConfigCodec.decode(MainNavigationConfigCodec.encode(config)))
        assertTrue(config.visibleIds.containsAll(listOf("current_behind", "season_next")))
        assertEquals(5, config.visibleIds.size)
        assertEquals(config, config.show("profile"))
    }

    @Test fun migrationRetainsCustomOrderButReplacesOldDefault() {
        assertEquals(MainNavigationConfig(), MainNavigationConfigCodec.decode("v3;home:1,anime:1,manga:1,profile:1,explore:1"))
        assertEquals(listOf("calendar", "home", "profile"), MainNavigationConfigCodec.decode("v4;calendar:1,home:1,profile:1").visibleIds)
        assertEquals(MainNavigationConfig(), MainNavigationConfigCodec.decode("v99;profile"))
    }

    @Test fun unknownOrDetailIdsCannotBePromotedAndDuplicatesAreRemoved() {
        assertEquals(listOf("home", "calendar"), MainNavigationConfig(listOf("media/1", "settings", "home", "home", "calendar")).normalized().visibleIds)
        assertFalse(MainNavigationResolver.allRoutes.contains(Route.Calendar))
        assertFalse(MainNavigationResolver.allRoutes.contains(Route.Settings))
        assertFalse(MainNavigationResolver.allRoutes.contains(Route.MediaDetails(1)))
    }

    @Test fun universeStaysStableAcrossAllEditsAndShortcutIdentitiesAreDistinct() {
        val universe = MainNavigationResolver.allRoutes
        val edited = MainNavigationConfig().hide("anime").hide("manga").show("season_current").show("season_next")
        assertTrue(universe.containsAll(MainNavigationResolver.destinations(edited).map { it.route }))
        assertEquals(BottomDestination.catalog.size, universe.size)
        assertTrue(universe.contains(Route.SeasonMain(false)))
        assertTrue(universe.contains(Route.SeasonMain(true)))
        assertTrue(universe.contains(Route.ChartMain("TOP_ANIME")))
        assertTrue(universe.contains(Route.ChartMain("TOP_MANGA")))
        assertSame(universe, MainNavigationResolver.allRoutes)
    }

    @Test fun hiddenActiveFallsBackToHomeAndOtherRootsArePreserved() {
        val config = MainNavigationConfig().hide("anime")
        assertEquals(Route.Home, MainNavigationResolver.visibleRoot(Route.AnimeTab, config))
        assertEquals(Route.CalendarMain, MainNavigationResolver.visibleRoot(Route.CalendarMain, config))
    }
}

package com.axiel7.anihyou

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.settings.MainNavigationEditor
import com.axiel7.anihyou.feature.settings.source.ExtensionCenterMenu
import com.axiel7.anihyou.feature.settings.source.ExtensionCenterPage
import androidx.activity.ComponentActivity
import com.axiel7.anihyou.ui.screens.main.MainNavigation
import com.axiel7.anihyou.core.ui.common.BottomDestination.Companion.isBottomDestination
import com.axiel7.anihyou.core.model.HomeTab
import com.axiel7.anihyou.core.model.NovelTab
import com.axiel7.anihyou.core.model.ExploreTab
import com.axiel7.anihyou.core.model.Theme
import com.materialkolor.PaletteStyle
import com.axiel7.anihyou.ui.screens.main.composables.MainBottomNavBar
import com.axiel7.anihyou.ui.screens.main.composables.MainNavigationRail
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationProductComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeActionsHaveAccessibleLabelsAndNavigateWithoutAProfileMainTab() {
        lateinit var state: NavigationState
        lateinit var navigator: Navigator
        var loggedIn by mutableStateOf(true)
        composeRule.setContent {
            state = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            navigator = remember(state) { Navigator(state) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme {
                    com.axiel7.anihyou.feature.home.HomeView(loggedIn, HomeTab.CURRENT)
                }
            }
        }
        assertFalse(MainNavigationConfig().visibleIds.contains("profile"))
        composeRule.onNodeWithTag("home-notifications").assertContentDescriptionEquals(composeRule.activity.getString(
            com.axiel7.anihyou.core.resources.R.string.notifications)).performClick()
        composeRule.runOnIdle { assertTrue(state.getCurrentRoute() is Route.Notifications); navigator.goBack() }
        composeRule.onNodeWithTag("home-postponements").assertContentDescriptionEquals(composeRule.activity.getString(
            com.axiel7.anihyou.core.resources.R.string.postponements)).performClick()
        composeRule.runOnIdle { assertEquals(Route.Postponements, state.getCurrentRoute()); navigator.goBack() }
        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.runOnIdle { assertEquals(Route.Settings, state.getCurrentRoute()); navigator.goBack() }
        composeRule.onNodeWithTag("home-profile").performClick()
        composeRule.runOnIdle { assertEquals(Route.OwnProfile, state.getCurrentRoute()); navigator.goBack(); loggedIn = false }
        composeRule.onNodeWithTag("home-notifications").assertDoesNotExist()
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("home-profile").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(Route.OwnProfile, state.getCurrentRoute()) }
    }

    @Test fun productionCalendarRootAndNestedHostHaveDifferentChromeAndRetainTheirStacks() {
        lateinit var state: NavigationState
        lateinit var navigator: Navigator
        composeRule.setContent {
            state = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            navigator = remember(state) { Navigator(state) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme {
                    Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                        MainNavigationRail(navigator, {})
                        Scaffold(modifier = androidx.compose.ui.Modifier.weight(1f), bottomBar = {
                            MainBottomNavBar(state.topLevelRoute, state.getCurrentRoute()?.isBottomDestination() == true, {})
                        }) { padding ->
                            MainNavigation(navigator, isCompactScreen = true, isLoggedIn = false,
                                homeTab = HomeTab.CURRENT, novelTab = NovelTab.MANGA,
                                exploreTab = ExploreTab.ANIME, deepLink = null, theme = Theme.LIGHT,
                                blackColors = false, paletteStyle = PaletteStyle.TonalSpot, padding = padding)
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { navigator.navigate(Route.CalendarMain) }
        composeRule.onNodeWithTag("CalendarTab").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("rail-calendar").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(
            com.axiel7.anihyou.core.resources.R.string.action_back)).assertDoesNotExist()
        composeRule.runOnIdle { navigator.navigate(Route.Calendar) }
        composeRule.onNodeWithTag("CalendarTab").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(
            com.axiel7.anihyou.core.resources.R.string.action_back)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(Route.Calendar, state.getCurrentRoute())
            navigator.navigate(Route.Home)
            navigator.navigate(Route.CalendarMain)
            assertEquals(Route.Calendar, state.getCurrentRoute())
            navigator.goBack()
        }
        composeRule.onNodeWithTag("CalendarTab").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithTag("rail-calendar").assertIsDisplayed().assertIsSelected()
        composeRule.runOnIdle {
            assertEquals(Route.CalendarMain, state.getCurrentRoute())
            navigator.goBack()
            assertEquals(Route.Home, state.topLevelRoute)
        }
    }

    @Test fun bottomAndRailUseSameOrderAndEditsRetainEveryBackstack() {
        var config by mutableStateOf(MainNavigationConfig())
        lateinit var state: NavigationState
        lateinit var navigator: Navigator
        var savedCalendarStack: Any? = null
        var savedAnimeStack: Any? = null
        composeRule.setContent {
            state = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            navigator = remember(state) { Navigator(state) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme {
                    Row {
                        MainNavigationRail(navigator, {}, destinations = MainNavigationResolver.destinations(config))
                        Column {
                            MainBottomNavBar(state.topLevelRoute, true, {}, MainNavigationResolver.destinations(config))
                            MainNavigationEditor(config, { transform -> config = transform(config) })
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle {
            navigator.navigate(Route.CalendarMain)
            navigator.navigate(Route.Calendar)
            val nestedStack = state.backStacks.getValue(Route.CalendarMain)
            val animeStack = state.backStacks.getValue(Route.AnimeTab)
            savedCalendarStack = nestedStack
            savedAnimeStack = animeStack
            config = config.move("home", 4).hide("manga").show("profile")
            assertSame(nestedStack, state.backStacks.getValue(Route.CalendarMain))
            assertSame(animeStack, state.backStacks.getValue(Route.AnimeTab))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rail-calendar").assertIsSelected()
        composeRule.onNodeWithTag("CalendarTab").assertIsSelected()
        composeRule.runOnIdle {
            assertSame(savedCalendarStack, state.backStacks.getValue(Route.CalendarMain))
            assertSame(savedAnimeStack, state.backStacks.getValue(Route.AnimeTab))
            val calendarStack = state.backStacks.getValue(Route.CalendarMain)
            navigator.navigate(Route.Home)
            navigator.navigate(Route.CalendarMain)
            assertSame(calendarStack, state.backStacks.getValue(Route.CalendarMain))
            assertEquals(Route.Calendar, state.getCurrentRoute())
            navigator.goBack()
            assertEquals(Route.CalendarMain, state.getCurrentRoute())
            navigator.goBack()
            assertEquals(Route.Home, state.topLevelRoute)
        }
    }

    @Test fun configPersistsAndExtensionCenterHasExactlyFiveFlatPages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MainNavigationConfigStore.get(context)
        try {
            assertTrue(store.update { MainNavigationConfig().move("home", 4).hide("anime").show("profile") })
            val persisted = context.getSharedPreferences("main-navigation", Context.MODE_PRIVATE).getString("config", null)
            assertEquals(store.config.value, MainNavigationConfigCodec.decode(persisted))
            var selected: ExtensionCenterPage? = null
            composeRule.setContent { MaterialTheme { Column { ExtensionCenterMenu { selected = it } } } }
            for (page in ExtensionCenterPage.entries) {
                composeRule.onNodeWithTag("extension-center-" + page.id).assertIsDisplayed().performClick()
                composeRule.runOnIdle { assertEquals(page, selected) }
            }
            assertEquals(5, ExtensionCenterPage.entries.size)
        } finally { store.update { MainNavigationConfig() } }
    }

    @Test fun mainNavigationEditorNamesEveryControlAndKeepsTheFiveTabLimit() {
        var config by mutableStateOf(MainNavigationConfig())
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MainNavigationEditor(config, { transform -> config = transform(config) })
                }
            }
        }
        val anime = composeRule.activity.getString(com.axiel7.anihyou.core.resources.R.string.anime)
        // A screen reader reads the tab name with the switch and with both move buttons, not a bare switch or an arrow glyph.
        composeRule.onNodeWithTag("main-visible-anime").assertContentDescriptionEquals(anime).assertIsOn()
        composeRule.onNodeWithTag("main-move-up-anime").assert(hasContentDescription(anime, substring = true))
        composeRule.onNodeWithTag("main-move-down-anime").assert(hasContentDescription(anime, substring = true))
        composeRule.onNodeWithTag("main-visible-home").assertIsNotEnabled()
        composeRule.onNodeWithTag("main-move-up-home").assertIsNotEnabled()

        // Five tabs are in the bar: another tab cannot be switched on until one is hidden.
        composeRule.onNodeWithTag("main-visible-profile").performScrollTo().assertIsNotEnabled().assertIsOff()
        composeRule.onNodeWithTag("main-visible-anime").performClick()
        composeRule.runOnIdle { assertFalse(config.normalized().visibleIds.contains("anime")) }
        composeRule.onNodeWithTag("main-visible-profile").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertTrue(config.normalized().visibleIds.contains("profile"))
            assertEquals(5, config.normalized().visibleIds.size)
        }

        composeRule.onNodeWithTag("main-reset").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(MainNavigationConfig().normalized().visibleIds, config.normalized().visibleIds) }
    }
}

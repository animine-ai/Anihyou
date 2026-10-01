package com.axiel7.anihyou

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
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
import com.axiel7.anihyou.ui.screens.main.MainActivity
import com.axiel7.anihyou.ui.screens.main.composables.MainBottomNavBar
import com.axiel7.anihyou.ui.screens.main.composables.MainNavigationRail
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNavigationProductComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun calendarMainKeepsChromeAndPredictiveBackReturnsHome() {
        composeRule.onNodeWithTag("HomeTab").performClick()
        composeRule.onNodeWithTag("ProfileTab").assertDoesNotExist()
        composeRule.onNodeWithTag("CalendarTab").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("CalendarTab").assertIsSelected()
        composeRule.onNodeWithTag("HomeTab").assertIsDisplayed()
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            composeRule.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(150f, 200f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        composeRule.onNodeWithTag("CalendarTab").assertIsSelected()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("HomeTab").assertIsSelected()
        composeRule.onNodeWithTag("home-settings").assertIsDisplayed()
        composeRule.onNodeWithTag("home-profile").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ProfileTab").assertDoesNotExist()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("HomeTab").assertIsSelected()
    }

    @Test fun bottomAndRailUseSameOrderAndEditsRetainEveryBackstack() {
        var config by mutableStateOf(MainNavigationConfig())
        lateinit var state: NavigationState
        lateinit var navigator: Navigator
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
            config = config.move("home", 4).hide("manga").show("profile")
            assertSame(nestedStack, state.backStacks.getValue(Route.CalendarMain))
            assertSame(animeStack, state.backStacks.getValue(Route.AnimeTab))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("rail-calendar").assertIsSelected()
        composeRule.onNodeWithTag("CalendarTab").assertIsSelected()
        composeRule.runOnIdle {
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
}

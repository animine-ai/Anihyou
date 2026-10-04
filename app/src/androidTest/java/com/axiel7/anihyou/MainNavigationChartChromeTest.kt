package com.axiel7.anihyou

import android.content.Context
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.ui.common.navigation.MainNavigationConfig
import com.axiel7.anihyou.core.ui.common.navigation.MainNavigationConfigStore
import com.axiel7.anihyou.ui.screens.main.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A promoted chart destination is a main destination that owns the full height: the bottom bar is hidden on it and back
 * leads to Home, where the bar is shown again. The chart data itself is not part of this test.
 */
@RunWith(AndroidJUnit4::class)
class MainNavigationChartChromeTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun aPromotedChartHidesTheBottomBarAndBackLeadsToHomeWithTheBar() {
        val store = MainNavigationConfigStore.get(ApplicationProvider.getApplicationContext<Context>())
        assertTrue(store.update { MainNavigationConfig().hide("manga").show("chart_top_anime") })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                composeRule.onNodeWithTag("HomeTab").assertIsDisplayed().assertIsSelected()
                composeRule.onNodeWithTag("MainTab-chart_top_anime").assertIsDisplayed().performClick()
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithTag("HomeTab").fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNodeWithTag("MainTab-chart_top_anime").assertDoesNotExist()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                composeRule.waitUntil(timeoutMillis = 10_000) {
                    composeRule.onAllNodesWithTag("HomeTab").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("HomeTab").assertIsDisplayed().assertIsSelected()
            }
        } finally {
            store.update { MainNavigationConfig() }
        }
    }
}

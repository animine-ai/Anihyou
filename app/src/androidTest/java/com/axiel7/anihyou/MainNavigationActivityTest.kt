package com.axiel7.anihyou

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.ui.screens.main.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Activity proof; component harnesses use an initially empty ComponentActivity. */
@RunWith(AndroidJUnit4::class)
class MainNavigationActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun calendarMainKeepsChromeAndPredictiveBackReturnsHome() {
        composeRule.onNodeWithTag("HomeTab").performClick()
        composeRule.onNodeWithTag("ProfileTab").assertDoesNotExist()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("CalendarTab").assertIsDisplayed().performClick()
            composeRule.mainClock.advanceTimeBy(32)
            repeat(6) {
                composeRule.onNodeWithTag("HomeTab").assertIsDisplayed()
                composeRule.onNodeWithTag("CalendarTab").assertIsDisplayed().assertIsSelected()
                composeRule.mainClock.advanceTimeBy(50)
            }
        } finally { composeRule.mainClock.autoAdvance = true }
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
        composeRule.onNodeWithTag("home-settings").assertContentDescriptionEquals(
            composeRule.activity.getString(com.axiel7.anihyou.core.resources.R.string.settings))
        composeRule.onNodeWithTag("home-profile").assertContentDescriptionEquals(
            composeRule.activity.getString(com.axiel7.anihyou.core.resources.R.string.profile))
        composeRule.onNodeWithTag("home-profile").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ProfileTab").assertDoesNotExist()
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithTag("HomeTab").assertIsSelected()
    }

}

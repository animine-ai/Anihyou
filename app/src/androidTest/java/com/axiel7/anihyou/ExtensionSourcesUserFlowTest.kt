package com.axiel7.anihyou

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.feature.settings.R as SettingsR
import com.axiel7.anihyou.ui.screens.main.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionSourcesUserFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cleanInstall_offersTheAddFlowWithoutAnyPreinstalledSourceOrConsent() {
        composeRule.onNodeWithTag("HomeTab").performClick()
        composeRule.onNodeWithTag("ProfileTab").assertDoesNotExist()
        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.onNodeWithText(text(SettingsR.string.extension_center_title)).performScrollTo().performClick()

        // Manually accepted first trust makes adding possible: the "unavailable" notice must be gone.
        composeRule.onNodeWithTag("extension-center-manage").performClick()
        awaitTag("extension-source-url")
        composeRule.onAllNodesWithTag("extension-trust-unavailable").assertCountEquals(0)
        composeRule.onNodeWithText(text(SettingsR.string.extension_sources_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(SettingsR.string.extension_sources_empty))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("extension-source-add").assertExists()
        // Nothing is installed, accepted or asked before the user enters a source and decides.
        composeRule.onAllNodesWithTag("extension-trust-dialog").assertCountEquals(0)
        awaitNoSourceCards()
    }

    // Repository IO completion is outside Compose idleness; await its published result.
    private fun awaitText(expected: String, scroll: Boolean = true) = composeRule.run {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(expected).let { node -> if (scroll) node.performScrollTo() else node }
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoSourceCards() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("extension-source-card").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun text(resourceId: Int, vararg arguments: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resourceId, *arguments)
}

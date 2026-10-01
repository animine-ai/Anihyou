package com.axiel7.anihyou

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
    fun cleanInstall_addDuplicateRejectInvalid_refreshFailClosed_andRemove() {
        composeRule.onNodeWithTag("ProfileTab").assertDoesNotExist()
        composeRule.onNodeWithTag("home-settings").performClick()
        composeRule.onNodeWithText(text(SettingsR.string.extension_center_title)).performScrollTo().performClick()
        composeRule.onNodeWithTag("extension-center-manage").performClick()

        composeRule.onNodeWithText(text(SettingsR.string.extension_sources_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(SettingsR.string.extension_sources_empty))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("extension-source-add").assertIsNotEnabled()

        composeRule.onNodeWithTag("extension-source-url")
            .performTextInput("http://example.org/repository")
        composeRule.onNodeWithTag("extension-source-url").performImeAction()
        awaitText(text(SettingsR.string.extension_sources_invalid_url))
            .assertIsDisplayed()
        awaitText(text(SettingsR.string.extension_sources_empty))
            .assertIsDisplayed()
        awaitNoSourceCards()

        val sourceUrl = "https://example.org/repository"
        composeRule.onNodeWithTag("extension-source-url").performTextClearance()
        composeRule.onNodeWithTag("extension-source-url").performTextInput(sourceUrl)
        composeRule.onNodeWithTag("extension-source-url").performImeAction()
        awaitText(text(SettingsR.string.extension_sources_added)).assertIsDisplayed()
        awaitTag("extension-source-card")
        composeRule.onNodeWithTag("extension-source-card").performScrollTo().assertIsDisplayed()
        // Adding schedules a background refresh, so Added is only a transient status.
        // No production trust is provisioned in this clean-install test.
        val trustUnavailable = text(
            SettingsR.string.extension_sources_status,
            text(SettingsR.string.extension_sources_status_trust_unavailable),
        )
        awaitText(trustUnavailable)
        composeRule.onNodeWithTag("extension-source-status")
            .assertTextEquals(trustUnavailable)

        composeRule.onNodeWithTag("extension-source-url").performTextInput(sourceUrl)
        composeRule.onNodeWithTag("extension-source-url").performImeAction()
        awaitText(text(SettingsR.string.extension_sources_duplicate)).assertIsDisplayed()

        composeRule.onNodeWithTag("extension-source-refresh").performScrollTo().performClick()
        awaitText(trustUnavailable)

        composeRule.onNodeWithTag("extension-source-enabled").assertDoesNotExist()
        composeRule.onNodeWithTag("extension-source-remove").performScrollTo().performClick()
        awaitText(text(SettingsR.string.extension_sources_empty)).assertIsDisplayed()
        awaitNoSourceCards()
    }

    // Repository IO completion is outside Compose idleness; await its published result.
    private fun awaitText(expected: String) = composeRule.run {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(expected).performScrollTo()
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

package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.axiel7.anihyou.feature.settings.source.UnverifiedSourceDialog
import com.axiel7.anihyou.release.core.source.UnverifiedSourcePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The explicit first-trust dialog: it names what was received and acts only on the two explicit buttons. */
@RunWith(AndroidJUnit4::class)
class UnverifiedSourceDialogComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preview = UnverifiedSourcePreview(
        url = "https://raw.githubusercontent.com/example/catalog/preview", repositoryId = "example.repository",
        rootFingerprint = "0123456789abcdef".repeat(4), publisherIds = listOf("example.publisher"),
        capabilities = listOf("CALENDAR", "RECENT"), hosts = listOf("aniworld.to"),
    )

    @Test fun showsTheReceivedIdentityAndOnlyTheTwoButtonsAct() {
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            MaterialTheme { UnverifiedSourceDialog(preview, busy = false, onConfirm = { confirmed++ }, onCancel = { cancelled++ }) }
        }
        composeRule.onNodeWithTag("extension-trust-dialog").assertIsDisplayed()
        composeRule.onNode(hasText(context.getString(com.axiel7.anihyou.feature.settings.R.string.extension_trust_title))).assertIsDisplayed()
        composeRule.onNode(hasText(context.getString(com.axiel7.anihyou.feature.settings.R.string.extension_trust_warning))).assertIsDisplayed()
        composeRule.onNode(hasText(preview.url, substring = true)).assertIsDisplayed()
        composeRule.onNodeWithTag("extension-trust-fingerprint").assertIsDisplayed()
        assertEquals("nothing acts before a button is tapped", 0, confirmed + cancelled)
        composeRule.onNodeWithTag("extension-trust-cancel").assertIsEnabled().performClick()
        assertEquals(1, cancelled)
        assertEquals(0, confirmed)
        composeRule.onNodeWithTag("extension-trust-confirm").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
}

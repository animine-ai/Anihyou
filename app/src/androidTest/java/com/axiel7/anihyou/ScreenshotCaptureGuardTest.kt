package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the invalid API 24 "diagnostics.png" that showed the Android launcher onboarding card
 * ("Welcome / Wallpapers, widgets, & settings") while every assertion of the test passed.
 *
 * The first five tests pin the decision on the exact facts that were wrong in that run. The last test drives the
 * real device to the home screen and requires the live capture path to refuse a picture instead of storing it.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureGuardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = "com.axiel7.anihyou.debug"

    @Test
    fun launcherOnboardingScreenIsNotTheAppScreen() {
        val launcher = ForegroundSnapshot(app, "com.android.launcher3", activityResumed = true, windowFocused = true,
            windowPackages = listOf("com.android.launcher3"))
        val problems = ScreenshotForegroundPolicy.violations(launcher)
        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().contains("com.android.launcher3"))
    }

    @Test
    fun anotherAppOrAnOverlayWindowIsNotTheAppScreen() {
        for (foreign in listOf("com.android.systemui", "android", "com.google.android.apps.nexuslauncher", null)) {
            val snapshot = ForegroundSnapshot(app, foreign, activityResumed = true, windowFocused = true)
            assertFalse("foreground $foreign must be rejected", ScreenshotForegroundPolicy.violations(snapshot).isEmpty())
        }
    }

    @Test
    fun anAppWindowThatIsPausedIsRejected() {
        assertEquals(listOf("activity is not RESUMED"),
            ScreenshotForegroundPolicy.violations(ForegroundSnapshot(app, app, activityResumed = false, windowFocused = true)))
    }

    @Test
    fun anOwnDialogInFrontOfTheActivityIsStillTheAppScreen() {
        // The rollback confirmation is an AlertDialog window: the activity window has no input focus, the app package
        // still owns the active window. This exact state made the first guard refuse a valid screenshot.
        val dialog = ForegroundSnapshot(app, app, activityResumed = true, windowFocused = false, windowPackages = listOf(app, app))
        assertTrue(ScreenshotForegroundPolicy.violations(dialog).isEmpty())
        assertTrue(ScreenshotForegroundPolicy.appDialogInFront(dialog))
        // The same missing focus with a foreign active window is not an app dialog and stays refused.
        val foreign = ForegroundSnapshot(app, "com.android.launcher3", activityResumed = true, windowFocused = false)
        assertFalse(ScreenshotForegroundPolicy.violations(foreign).isEmpty())
        assertFalse(ScreenshotForegroundPolicy.appDialogInFront(foreign))
    }

    @Test
    fun theFocusedResumedAppWindowIsAccepted() {
        val snapshot = ForegroundSnapshot(app, app, activityResumed = true, windowFocused = true, windowPackages = listOf(app))
        assertTrue(ScreenshotForegroundPolicy.violations(snapshot).isEmpty())
    }

    @Test
    fun liveCaptureStoresTheAppScreenAndRefusesTheHomeScreen() {
        composeRule.setContent { MaterialTheme { Text("EP07 capture guard marker", Modifier.testTag("guard-marker")) } }
        composeRule.onNodeWithTag("guard-marker").assertIsDisplayed()
        val directory = File(composeRule.activity.getExternalFilesDir(null), "ep07-guard").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }

        // Positive: the real foreground is the app, so the picture and its capture report are stored.
        composeRule.storeVerifiedScreenshot(composeRule.activity, directory, "guard-positive") {
            composeRule.onNodeWithTag("guard-marker").assertIsDisplayed()
        }
        assertTrue(File(directory, "guard-positive.png").length() > 0)
        val report = File(directory, "guard-positive.capture.txt").readText()
        assertTrue(report, report.contains("activeWindowPackageBefore=${composeRule.activity.packageName}"))
        assertTrue(report, report.contains("activityResumed=true/true"))
        assertTrue(report, report.contains("appDialogInFront=false/false"))

        // Negative: show the real home screen. The OS must report a different active window, and the capture
        // must refuse to produce any file, in bounded time.
        assertTrue("the accessibility stream refused the home action", pressHomeThroughUiAutomation())
        val deadline = System.currentTimeMillis() + 10_000
        val appPackage = composeRule.activity.packageName
        var foreground = readForegroundSnapshot(composeRule.activity)
        while (foreground.activeWindowPackage == appPackage && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            foreground = readForegroundSnapshot(composeRule.activity)
        }
        assertFalse("the home screen did not replace the app: $foreground", foreground.activeWindowPackage == appPackage)

        val started = System.currentTimeMillis()
        var refused: ScreenshotNotOfAppException? = null
        try {
            composeRule.storeVerifiedScreenshot(composeRule.activity, directory, "guard-negative") {
                composeRule.onNodeWithTag("guard-marker").assertIsDisplayed()
            }
        } catch (error: ScreenshotNotOfAppException) {
            refused = error
        }
        assertNotNull("a screenshot of the home screen was accepted as an app screenshot", refused)
        assertTrue("the refusal must be bounded", System.currentTimeMillis() - started < 20_000)
        assertFalse(File(directory, "guard-negative.png").exists())
        assertFalse(File(directory, "guard-negative.capture.txt").exists())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}

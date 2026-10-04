package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.axiel7.anihyou.feature.settings.source.schedule.ExtensionRefreshScheduleEvent
import com.axiel7.anihyou.feature.settings.source.schedule.ExtensionRefreshScheduleScreen
import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering and interaction evidence of the update schedule page; persistence and slots are proven elsewhere. */
@RunWith(AndroidJUnit4::class)
class ExtensionRefreshScheduleComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class Recording : ExtensionRefreshScheduleEvent {
        val intervals = mutableListOf<ExtensionRefreshInterval>()
        val anchors = mutableListOf<Int>()
        var resets = 0
        override fun setInterval(interval: ExtensionRefreshInterval) { intervals += interval }
        override fun setAnchor(minuteOfDay: Int) { anchors += minuteOfDay }
        override fun reset() { resets++ }
    }

    private val event = Recording()

    private fun show(schedule: ExtensionRefreshSchedule) =
        composeRule.setContent { MaterialTheme { ExtensionRefreshScheduleScreen(schedule, event) } }

    private fun capture(name: String, check: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.storeVerifiedScreenshot(composeRule.activity,
            File(requireNotNull(context.getExternalFilesDir(null)), "ep07-guard/schedule-ui"), name, check)
    }

    private fun hours(count: Int) = composeRule.activity.resources.getQuantityString(
        com.axiel7.anihyou.feature.settings.R.plurals.extension_schedule_every_hours, count, count)

    @Test fun defaultIsDailyAtOneWithANextRunAndNoFiveHourChoice() {
        show(ExtensionRefreshSchedule())
        composeRule.onNodeWithTag("extension-schedule-interval").assertTextContains(hours(24), substring = true)
        composeRule.onNodeWithTag("extension-schedule-start").assertTextContains("01:00", substring = true)
        composeRule.onNodeWithTag("extension-schedule-next").assertIsDisplayed()
        capture("schedule-default") { composeRule.onNodeWithTag("extension-schedule-next").assertIsDisplayed() }
        // Five hours does not repeat on a fixed daily grid and is deliberately not offered.
        composeRule.onNodeWithTag("extension-schedule-interval").performClick()
        composeRule.onNodeWithText(hours(5)).assertDoesNotExist()
        composeRule.onNodeWithText(hours(6)).assertIsDisplayed()
        assertEquals(emptyList<ExtensionRefreshInterval>(), event.intervals)
    }

    @Test fun oneHourReadsInTheSingularNotAsOneHours() {
        show(ExtensionRefreshSchedule(interval = ExtensionRefreshInterval.HOURS_1))
        val label = hours(1)
        assertEquals(false, label.contains("1"))
        composeRule.onNodeWithTag("extension-schedule-interval").assertTextContains(label, substring = true)
    }

    @Test fun choosingAnIntervalAndResettingAreReportedWithoutChangingAnythingElse() {
        show(ExtensionRefreshSchedule(anchorMinuteOfDay = 7 * 60 + 30, interval = ExtensionRefreshInterval.HOURS_6))
        composeRule.onNodeWithTag("extension-schedule-interval").assertTextContains(hours(6), substring = true)
        composeRule.onNodeWithTag("extension-schedule-start").assertTextContains("07:30", substring = true)
        composeRule.onNodeWithTag("extension-schedule-interval").performClick()
        composeRule.onNodeWithText(hours(2)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(
            com.axiel7.anihyou.core.resources.R.string.close)).performClick()
        composeRule.onNodeWithTag("extension-schedule-reset").performScrollTo().performClick()
        assertEquals(listOf(ExtensionRefreshInterval.HOURS_2), event.intervals)
        assertEquals(1, event.resets)
        assertEquals(emptyList<Int>(), event.anchors)
    }

    @Test fun startTimeDialogReportsTheConfirmedMinuteOfDayOnly() {
        show(ExtensionRefreshSchedule(anchorMinuteOfDay = 5 * 60 + 15))
        composeRule.onNodeWithTag("extension-schedule-start").performScrollTo().performClick()
        composeRule.onNodeWithTag("extension-schedule-start-confirm").assertIsDisplayed().performClick()
        assertEquals(listOf(5 * 60 + 15), event.anchors)
        assertEquals(emptyList<ExtensionRefreshInterval>(), event.intervals)
    }
}

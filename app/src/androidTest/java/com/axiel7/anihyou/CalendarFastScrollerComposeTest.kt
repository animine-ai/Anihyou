package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.calendar.*
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.model.ProviderId
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The edge strip of the calendar list: a finger held at its end jumps the list to the last day, at its start to the first. */
@RunWith(AndroidJUnit4::class)
class CalendarFastScrollerComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.of(2026, 10, 2)

    private fun row(date: LocalDate, number: Int) = ReleaseUiCalendarItem(
        mediaId = null,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/example"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
        installment = Installment.Episode(number), forecastAt = null, confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = date, sourceRoot = "https://aniworld.to", revision = 1,
    )

    @Test fun holdingTheEdgeAndMovingJumpsToTheDayUnderTheFinger() {
        val days = (0 until 30).map { today.plusDays(it.toLong()) }
        val state = CalendarUiState(
            today = today, day = today.atStartOfDay(), isLoading = false, hasNextPage = false,
            providerRowsByDate = days.associateWith { date -> (1..3).map { row(date, it) } },
            todayFirstItemIndex = 0, todayAnchorReady = true,
        )
        composeRule.setContent {
            val navigation = rememberNavigationState(Route.Calendar, MainNavigationResolver.allRoutes)
            val navigator = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme(colorScheme = darkColorScheme()) { CalendarViewContent(isLoggedIn = false, uiState = state, event = null) }
            }
        }
        composeRule.waitForIdle()
        val strip = composeRule.onNodeWithTag("calendar-scroller")
        strip.assertExists()
        strip.performTouchInput { down(Offset(centerX, height - 4f)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar-day-${days.last()}").assertIsDisplayed()
        strip.performTouchInput { moveTo(Offset(centerX, 4f)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar-day-${days.first()}").assertIsDisplayed()
        strip.performTouchInput { up() }
    }

    private fun showCalendar(days: List<LocalDate>) {
        val state = CalendarUiState(
            today = today, day = today.atStartOfDay(), isLoading = false, hasNextPage = false,
            providerRowsByDate = days.associateWith { date -> (1..3).map { row(date, it) } },
            todayFirstItemIndex = 0, todayAnchorReady = true,
        )
        composeRule.setContent {
            val navigation = rememberNavigationState(Route.Calendar, MainNavigationResolver.allRoutes)
            val navigator = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme(colorScheme = darkColorScheme()) { CalendarViewContent(isLoggedIn = false, uiState = state, event = null) }
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun theStripKeepsItsDistanceAndHoldingItShowsADotForEveryDayAndTheDate() {
        val days = (0 until 30).map { today.plusDays(it.toLong()) }
        showCalendar(days)
        val strip = composeRule.onNodeWithTag("calendar-scroller")
        val gap = 72f * composeRule.activity.resources.displayMetrics.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = strip.fetchSemanticsNode().boundsInRoot
        assertTrue("strip starts ${bounds.top} px down, gap is $gap", bounds.top >= gap - 1f)
        assertTrue("strip ends ${root.bottom - bounds.bottom} px above the bottom, gap is $gap", root.bottom - bounds.bottom >= gap - 1f)

        val track = composeRule.onNodeWithTag("calendar-scroller-track")
        track.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "handle"))
        composeRule.onNodeWithTag("calendar-scroller-bubble").assertDoesNotExist()
        strip.performTouchInput { down(Offset(centerX, height / 2f)) }
        composeRule.waitForIdle()
        track.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "dots:30"))
        composeRule.onNodeWithTag("calendar-scroller-bubble").assertExists()
        strip.performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar-scroller-bubble").assertDoesNotExist()
    }
}

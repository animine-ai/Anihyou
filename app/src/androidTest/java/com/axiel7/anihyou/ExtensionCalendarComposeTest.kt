package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.calendar.*
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.*
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionCalendarComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val today = LocalDate.of(2026, 10, 2)

    @Test fun listStartsAtEmptyTodayAndTodayButtonReturnsFromHistory() = todayAnchor(ListStyle.STANDARD, "calendar-list")
    @Test fun gridStartsAtEmptyTodayAndTodayButtonReturnsFromHistory() = todayAnchor(ListStyle.GRID, "calendar-grid")

    private fun todayAnchor(style: ListStyle, listTag: String) {
        val past = today.minusDays(14)
        val rows = (1..12).map { number -> row(past, number) }
        var state by mutableStateOf(CalendarUiState(
            today = today, day = today.atStartOfDay(), listStyle = style, isLoading = false,
            providerRowsByDate = mapOf(past to rows, today.plusDays(1) to listOf(row(today.plusDays(1), 13))),
            todayFirstItemIndex = rows.size + 1, todayAnchorReady = true,
        ))
        composeRule.setContent {
            val navigation = rememberNavigationState(Route.Calendar, MainNavigationResolver.allRoutes)
            val navigator = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme { CalendarViewContent(isLoggedIn = false, uiState = state, event = null) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar-day-$today").assertIsDisplayed()
        // A completed startup scroll is a one-time action. Later refreshes retain the user's position.
        composeRule.runOnIdle { state = state.copy(autoScrollToToday = false) }
        composeRule.onNodeWithTag(listTag).performScrollToIndex(0)
        composeRule.onNodeWithTag("calendar-day-$past").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(providerRowsByDate = state.providerRowsByDate +
                (past to rows.map { it.copy(revision = 2) }))
        }
        composeRule.onNodeWithTag("calendar-day-$past").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.jump_to_today))
            .assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("calendar-day-$today").assertIsDisplayed()
        composeRule.onNodeWithTag(listTag).performScrollToIndex(rows.size + 2)
        composeRule.onNodeWithTag("calendar-day-${today.plusDays(1)}").assertIsDisplayed()
    }

    @Test fun safePostponementOpensMappedDetailsAndUnassignedRowCannotNavigate() {
        val mapped = notice("Mapped provider title", "mapped-series", 7)
        val unassigned = notice("Unassigned provider title", "unassigned-series", null)
        lateinit var navigation: NavigationState
        lateinit var navigator: Navigator
        composeRule.setContent {
            navigation = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            navigator = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme {
                    PostponementsViewContent(PostponementsUiState(
                        notices = listOf(mapped, unassigned),
                        metadata = mapOf(7 to PostponementMediaMetadata("Verified anime title", null, false)),
                    ))
                }
            }
        }
        composeRule.onNodeWithText("Verified anime title").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(Route.MediaDetails(id = 7), navigation.getCurrentRoute())
            navigator.goBack()
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.postponements_unassigned)).assertIsDisplayed()
        composeRule.onNodeWithText("Unassigned provider title").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertEquals(Route.Home, navigation.getCurrentRoute()) }
    }

    private fun row(date: LocalDate, number: Int) = ReleaseUiCalendarItem(
        mediaId = null,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/example"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
        installment = Installment.Episode(number), forecastAt = null, confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = date,
        sourceRoot = "https://aniworld.to", revision = 1,
    )

    private fun notice(title: String, slug: String, id: Int?) = ExtensionPostponementNotice(
        title, 1, 1, "1", ObservationTrack.DE_SUB, ObservationScheduleMarker.POSTPONED,
        "New schedule", slug, id,
    )
}

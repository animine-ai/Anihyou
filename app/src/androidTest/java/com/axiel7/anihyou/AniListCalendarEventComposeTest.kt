package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.model.media.exampleBasicMediaDetails
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.common.utils.DateUtils.timestampToTimeString
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.calendar.*
import java.time.Instant
import java.time.LocalDate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic schedule values test the reported class of bug; they are not a claim about live Secret Saint schedules. */
@RunWith(AndroidJUnit4::class)
class AniListCalendarEventComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun listKeepsEpisodesOneAndTwoAndNeverUsesTheNextWeekLabel() = calendar(ListStyle.STANDARD)
    @Test fun gridKeepsEpisodesOneAndTwoAndNeverUsesTheNextWeekLabel() = calendar(ListStyle.GRID)

    /**
     * Cold start: yesterday arrives first, today's rows later. The scroll to today is clamped while nothing follows today's
     * header, so the focus has to be applied again when today's rows arrive; otherwise the list stays on yesterday's tail.
     */
    @Test fun todayStaysFocusedWhenItsRowsArriveAfterTheFirstFocus() {
        val today = LocalDate.of(2026, 10, 4)
        fun media(id: Int, title: String) = ExploreMedia(
            __typename = "Media", id = id, basicMediaDetails = exampleBasicMediaDetails.copy(id = id,
                title = exampleBasicMediaDetails.title!!.copy(userPreferred = title)),
            status = MediaStatus.RELEASING, coverImage = null, popularity = 1, bannerImage = null,
            averageScore = null, genres = emptyList(), mediaListEntry = null, nextAiringEpisode = null,
        )
        fun events(prefix: String, firstId: Int, at: Instant) = (1..12).map { n ->
            CalendarAiringEvent(firstId + n, 1, at.epochSecond.toInt(), media(firstId + n, "$prefix $n"))
        }
        val yesterdayEvents = events("Past", 1000, Instant.parse("2026-10-03T13:00:00Z"))
        val todayEvents = events("Today", 2000, Instant.parse("2026-10-04T13:00:00Z"))
        var state by mutableStateOf(CalendarUiState(
            today = today, day = today.atStartOfDay(), weeklyAnime = mutableMapOf(today.minusDays(1) to yesterdayEvents),
            listStyle = ListStyle.STANDARD, isLoading = false, hasNextPage = false,
            todayFirstItemIndex = 13, todayAnchorReady = true, autoScrollToToday = true,
        ))
        lateinit var navigation: NavigationState
        lateinit var navigator: Navigator
        rule.setContent {
            navigation = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            navigator = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                AniHyouTheme(darkTheme = false, dynamicColor = false) { CalendarViewContent(false, state, null) }
            }
        }
        rule.waitForIdle()
        state = state.copy(weeklyAnime = mutableMapOf(today.minusDays(1) to yesterdayEvents, today to todayEvents))
        rule.waitForIdle()
        rule.onNodeWithText("Today 1").assertIsDisplayed()
        rule.onAllNodesWithText("Past", substring = true).assertCountEquals(0)
    }
    private fun calendar(style: ListStyle) {
        val timestamp = Instant.parse("2026-10-03T13:00:00Z").epochSecond.toInt()
        val tomorrowTimestamp = Instant.parse("2026-10-04T14:00:00Z").epochSecond.toInt()
        fun media(id: Int, title: String, nextTime: Int) = ExploreMedia(
            __typename = "Media", id = id, basicMediaDetails = exampleBasicMediaDetails.copy(id = id,
                title = exampleBasicMediaDetails.title!!.copy(userPreferred = title)),
            status = MediaStatus.RELEASING, coverImage = null, popularity = 1, bannerImage = null,
            averageScore = null, genres = emptyList(), mediaListEntry = null,
            nextAiringEpisode = ExploreMedia.NextAiringEpisode(__typename = "AiringSchedule", id = id + 1000,
                episode = 3, airingAt = nextTime, timeUntilAiring = 604800),
        )
        val saint = media(187402, "A Tale of the Secret Saint", timestamp + 604800)
        val other = media(42, "Another scheduled title", tomorrowTimestamp + 604800)
        val first = CalendarAiringEvent(100, 1, timestamp, saint)
        val second = CalendarAiringEvent(101, 2, timestamp, saint)
        val third = CalendarAiringEvent(102, 1, tomorrowTimestamp, other)
        val day = first.localDate()
        lateinit var navigation: NavigationState
        lateinit var navigator: Navigator
        rule.setContent {
            navigation = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            navigator = remember(navigation) { Navigator(navigation) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                AniHyouTheme(darkTheme = false, dynamicColor = false) { CalendarViewContent(false, CalendarUiState(today = third.localDate(), day = third.localDate().atStartOfDay(),
                    weeklyAnime = mutableMapOf(day to listOf(first, second, second), third.localDate() to listOf(third)),
                    listStyle = style, isLoading = false, autoScrollToToday = false), null) }
            }
        }
        val time = timestamp.toLong().timestampToTimeString()!!
        for (episode in listOf(1,2)) rule.onNodeWithText(rule.activity.getString(
            com.axiel7.anihyou.core.resources.R.string.episode_airing_at, episode, time)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(com.axiel7.anihyou.core.resources.R.string.episode_airing_at, 3, time))
            .assertDoesNotExist()
        rule.onAllNodesWithText("A Tale of the Secret Saint").assertCountEquals(2)
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "anilist-events-${style.name.lowercase()}") {
            rule.onAllNodesWithText("A Tale of the Secret Saint").assertCountEquals(2)
        }
        rule.onAllNodesWithText("A Tale of the Secret Saint")[0].performClick()
        rule.runOnIdle {
            assertEquals(Route.MediaDetails(187402), navigation.getCurrentRoute())
            navigator.goBack()
        }
        rule.onNodeWithTag(if (style == ListStyle.GRID) "calendar-grid" else "calendar-list")
            .performScrollToIndex(3)
        val sundayTime = tomorrowTimestamp.toLong().timestampToTimeString()!!
        val sundayLabel = rule.activity.getString(com.axiel7.anihyou.core.resources.R.string.episode_airing_at, 1, sundayTime)
        rule.onNodeWithTag("calendar-day-${third.localDate()}").assertIsDisplayed()
        rule.onNodeWithText("Another scheduled title").assertIsDisplayed()
        rule.onNodeWithText(sundayLabel).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(com.axiel7.anihyou.core.resources.R.string.episode_airing_at, 3, sundayTime))
            .assertDoesNotExist()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "anilist-next-day-${style.name.lowercase()}") {
            rule.onNodeWithText("Another scheduled title").assertIsDisplayed()
            rule.onNodeWithText(sundayLabel).assertIsDisplayed()
        }
    }
}

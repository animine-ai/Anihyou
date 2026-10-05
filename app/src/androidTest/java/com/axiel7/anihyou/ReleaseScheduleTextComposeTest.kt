package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.composables.media.ReleaseScheduleText
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiFreshness
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the user reads for one anime, rendered by the real text composable on the device. Fixed clock 2026-10-04T12:00:00Z; the
 * inputs contradict AniList on purpose (the source confirmed episode 10 and plans episode 11 in three hours, the user watched
 * episode 8). The clock is moved by the test; the open screen must follow it without any new data.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseScheduleTextComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val start = Instant.parse("2026-10-04T12:00:00Z")
    private val plan = start.plusSeconds(3 * 3_600L)
    private val stream = ReleaseStreamKey(
        ProviderId("source"), SourceSeriesKey("/s/7"), ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB,
    )

    private fun presentation(authority: ReleaseUiAuthority = ReleaseUiAuthority.VALID) = ReleaseUiPresentation(
        mediaId = 7, stream = stream, authority = authority, confirmedThroughEpisode = 10,
        confirmedInstallments = (1..10).map { Installment.Episode(it) },
        // A stored count that disagrees on purpose: the text must derive the count from the progress.
        confirmedPending = 99, nextExpectedInstallment = Installment.Episode(11),
        nextForecast = Forecast(SourceIdentity(stream, Installment.Episode(11)), plan, null, null, ZoneOffset.UTC, false, start),
        freshness = ReleaseUiFreshness.UNKNOWN, sourceRoot = null, revision = 1,
    )

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)
    private fun plural(id: Int, count: Int) = composeRule.activity.resources.getQuantityString(id, count, count)

    private val confirmed get() = string(R.string.release_schedule_confirmed_through, 10)
    private val episode11 get() = string(R.string.release_installment_episode, 11)
    private fun pending(count: Int) = plural(R.plurals.release_schedule_pending, count)
    private fun nextAt(relative: String) = string(R.string.release_schedule_next_at, episode11, relative)

    @Test fun homeCardShowsOneSubBacklogAndKeepsTheProgressButtonInsideTheRow() {
        val example = com.axiel7.anihyou.core.model.media.exampleCommonMediaListEntry
        val media = requireNotNull(example.media)
        val item = example.copy(media = media.copy(basicMediaDetails = media.basicMediaDetails.copy(
            title = media.basicMediaDetails.title?.copy(userPreferred = "Blue Box Season 2"))),
            basicMediaListEntry = example.basicMediaListEntry.copy(progress = 8))
        val sub = presentation()
        val dub = sub.copy(stream = sub.stream.copy(languageTrack = LanguageTrack.DE_DUB), confirmedThroughEpisode = 20)
        composeRule.setContent {
            com.axiel7.anihyou.core.ui.theme.AniHyouTheme(darkTheme = true, dynamicColor = false) {
                com.axiel7.anihyou.feature.home.current.composables.CurrentListItem(
                    item = item, releasePresentations = listOf(dub, sub), isPlusEnabled = true,
                    showLowPriority = false, allPriorityColors = com.axiel7.anihyou.core.ui.composables.media.AllPriorityColors.Default,
                    onClick = {}, onLongClick = {}, onClickPlus = {}, blockPlus = {},
                    modifier = Modifier.width(340.dp).testTag("sub-only-home-row"),
                )
            }
        }
        val pending = plural(R.plurals.num_episodes_behind, 2)
        composeRule.onAllNodesWithText("$pending · SUB").assertCountEquals(1)
        composeRule.onAllNodesWithText("DUB", substring = true).assertCountEquals(0)
        val rowBounds = composeRule.onNodeWithTag("sub-only-home-row").fetchSemanticsNode().boundsInRoot
        val pendingBounds = composeRule.onNodeWithText("$pending · SUB", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val plusBounds = composeRule.onNodeWithText("+1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("backlog and progress must not overlap", pendingBounds.bottom <= plusBounds.top)
        org.junit.Assert.assertTrue("progress stays inside its card", plusBounds.bottom <= rowBounds.bottom)
        val directory = java.io.File(composeRule.activity.getExternalFilesDir(null), "ep07-ui")
        composeRule.storeVerifiedScreenshot(composeRule.activity, directory, "home-sub-only-backlog") {
            composeRule.onNodeWithText("$pending · SUB", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test fun theTextFollowsProgressTheClockAndAnOverduePlanOnAnOpenScreen() {
        val clock = MutableClock(start)
        var progress by mutableStateOf<Int?>(8)
        var authority by mutableStateOf(ReleaseUiAuthority.VALID)
        composeRule.setContent {
            MaterialTheme {
                ReleaseScheduleText(presentation(authority), clock = clock, progress = progress) { Text("anilist-fallback") }
            }
        }
        // The original wording of the app with the data of the source: "N episodes behind" while behind, otherwise "Ep N in ...".
        fun behind(count: Int) = plural(R.plurals.num_episodes_behind, count)
        val nextEpisode = "Ep 11 in"

        // Confirmed 10, progress 8: two are pending, the plan never adds one.
        composeRule.onNodeWithText(behind(2), substring = true).assertIsDisplayed()

        // Progress +1, then caught up: the count follows the progress, no source refresh is involved.
        composeRule.runOnIdle { progress = 9 }
        composeRule.onNodeWithText(behind(1), substring = true).assertIsDisplayed()
        composeRule.runOnIdle { progress = 10 }
        composeRule.onNodeWithText(nextEpisode, substring = true).assertIsDisplayed()
        // Not on the list (logged out or not added): nothing is claimed as pending.
        composeRule.runOnIdle { progress = null }
        composeRule.onNodeWithText(nextEpisode, substring = true).assertIsDisplayed()
        composeRule.runOnIdle { progress = 8 }
        composeRule.onNodeWithText(behind(2), substring = true).assertIsDisplayed()

        // The open screen ages: two hours after the plan it is still shown (the source needs time to confirm) ...
        composeRule.runOnIdle { progress = 10 }
        clock.now = plan.plusSeconds(2 * 3_600L)
        composeRule.mainClock.advanceTimeBy(61_000)
        composeRule.onNodeWithText(nextEpisode, substring = true).assertIsDisplayed()

        // Past grace a valid source must not invent an AniList count or display an elapsed countdown.
        clock.now = plan.plusSeconds(2 * 3_600L + 1)
        composeRule.mainClock.advanceTimeBy(61_000)
        composeRule.onNodeWithText("anilist-fallback").assertDoesNotExist()
        composeRule.onNodeWithText(nextEpisode, substring = true).assertDoesNotExist()
        // Progress still reprojects from confirmed source facts while the plan is overdue.
        composeRule.runOnIdle { progress = 8 }
        composeRule.onNodeWithText(behind(2), substring = true).assertIsDisplayed()

        // A presentation that is not valid never replaces the AniList text.
        composeRule.runOnIdle { progress = 8; clock.now = start; authority = ReleaseUiAuthority.STALE }
        composeRule.mainClock.advanceTimeBy(61_000)
        composeRule.onNodeWithText("anilist-fallback").assertIsDisplayed()
    }
}

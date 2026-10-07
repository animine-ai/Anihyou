package com.axiel7.anihyou

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.axiel7.anihyou.feature.settings.source.matching.*
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering/interaction evidence only. Persistent repository and migration proofs live in release-data. */
@RunWith(AndroidJUnit4::class)
class MatchingManagementComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val source = ExtensionSelectionKey("source-a", "extension-a", "publisher-a", "aniworld")
    private val entry = ManagedMapping("fixture", "revision-7", source, "Signed source A", "Quellen-Anime · Staffel 2",
        "source-series/season-2", "Season 2 · DE_SUB", 42, "AniList target", manual = true)
    private val event = RecordingEvents()
    private fun fixture() = MatchingManagementState(loading = false,
        page = MappingPage(listOf(entry), 125, listOf(MappingSourceFacet(source, "Signed source A", 125))))
    private fun capture(name: String, check: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The existing product CI pulls ep07-guard recursively. Keep this separate from its exact legacy shot set.
        composeRule.storeVerifiedScreenshot(composeRule.activity,
            File(requireNotNull(context.getExternalFilesDir(null)), "ep07-guard/matching-ui"), name, check)
    }

    @Test fun listShowsSourceTitleAsPrimaryAndTargetAndTrackAsSecondary() {
        composeRule.setContent { MaterialTheme { MatchingManagementScreen(fixture(), event) } }
        composeRule.onNodeWithTag("matching-list").performScrollToNode(hasTestTag("mapping-row-fixture"))
        composeRule.onNodeWithText(entry.sourceTitle!!).assertIsDisplayed()
        composeRule.onNodeWithText("AniList target · AniList #42").assertIsDisplayed()
        composeRule.onNodeWithText(entry.partLabel).assertIsDisplayed()
        capture("matching-list") { composeRule.onNodeWithText(entry.sourceTitle!!).assertIsDisplayed() }
        assertEquals(0, event.starts)
    }
    @Test fun sourceConfirmationShowsFullFrozenCountAndRequiresExplicitStart() {
        val snapshot = fixture().copy(query = MappingQuery(source = source),
            confirmation = MappingConfirmation(MappingAction.RESET, MappingScope.Source(source), MappingActionToken("frozen-source", 125)))
        composeRule.setContent { MaterialTheme { MatchingManagementScreen(snapshot, event) } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(com.axiel7.anihyou.feature.settings.R.string.matching_confirm_count, 125)).assertIsDisplayed()
        assertEquals(0, event.starts)
        capture("matching-source-confirmation") { composeRule.onNodeWithTag("matching-confirm-action").assertIsDisplayed() }
        composeRule.onNodeWithTag("matching-confirm-action").performClick()
        assertEquals(1, event.starts)
    }
    @Test fun editorSearchAndSaveRequireAChosenTargetAndKeepSourceIdentityVisible() {
        val snapshot = mutableStateOf(fixture().copy(editor = entry))
        composeRule.setContent { MaterialTheme { MatchingManagementScreen(snapshot.value, event) } }
        composeRule.onNodeWithTag("matching-save-target").assertIsNotEnabled()
        composeRule.onNodeWithText(entry.sourceIdentity).assertIsDisplayed()
        // Capture the dialog before opening the system input-method window.
        capture("matching-editor") { composeRule.onNodeWithTag("mapping-editor").assertIsDisplayed() }
        composeRule.onNodeWithTag("matching-target-search").performTextInput("replacement")
        assertEquals("replacement", event.targetText)
        val target = MappingTarget(55, "Chosen target")
        composeRule.runOnIdle { snapshot.value = snapshot.value.copy(chosenTarget = target, targets = listOf(target)) }
        composeRule.onNodeWithTag("matching-save-target").assertIsEnabled().performClick()
        assertEquals(1, event.corrections)
    }
    @Test fun unknownLegacySourceUsesHonestIdAndNeverSubstitutesAniListTitle() {
        val legacy = entry.copy(id = "legacy", source = null, sourceTitle = null, sourceLabel = "")
        composeRule.setContent { MaterialTheme { MatchingManagementScreen(fixture().copy(page = MappingPage(listOf(legacy), 1)), event) } }
        composeRule.onNodeWithTag("matching-list").performScrollToNode(hasTestTag("mapping-row-legacy"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(com.axiel7.anihyou.feature.settings.R.string.matching_source_id, legacy.sourceIdentity)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(com.axiel7.anihyou.feature.settings.R.string.matching_unknown_source)).assertIsDisplayed()
    }
    @Test fun emptyListInDarkThemeWithLargeTextIsReadableAndDoesNotStartWork() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    MatchingManagementScreen(fixture().copy(page = MappingPage(emptyList(), 0)), event)
                }
            }
        }
        composeRule.onNodeWithTag("matching-list").performScrollToNode(hasTestTag("matching-empty"))
        capture("matching-empty-dark-large-text") { composeRule.onNodeWithTag("matching-empty").assertIsDisplayed() }
        assertEquals(0, event.starts)
    }
    @Test fun searchHidesEveryWideActionAndShowsUnmatchedResultBeyondFirst150() {
        val rows = (1..160).map { UnmatchedSeries(source, "series-$it", 1, "Ordinary $it") } +
            UnmatchedSeries(source, "late", 2, "L’Été", UnmatchedSuggestion(99, "Queen’s return", 0.8))
        val state = fixture().copy(query = MappingQuery("queens ete", source),
            page = MappingPage(emptyList(), 0, fixture().page.sources, 125), unmatched = rows)
        composeRule.setContent { MaterialTheme { MatchingManagementScreen(state, event) } }
        listOf("matching-match-now", "matching-search-now", "matching-reset-all", "matching-rematch-all",
            "matching-reset-source", "matching-rematch-source").forEach { composeRule.onNodeWithTag(it).assertDoesNotExist() }
        composeRule.onNodeWithTag("matching-list").performScrollToNode(hasTestTag("unmatched-row-late-2"))
        capture("matching-search-unmatched") { composeRule.onNodeWithTag("unmatched-row-late-2").assertIsDisplayed() }
        composeRule.onNodeWithTag("unmatched-row-series-1-1").assertDoesNotExist()
    }
    @Test fun searchWithoutResultsHasItsOwnEmptyState() {
        val state = fixture().copy(query = MappingQuery("missing"), page = MappingPage(emptyList(), 0))
        composeRule.setContent { MaterialTheme { MatchingManagementScreen(state, event) } }
        composeRule.onNodeWithTag("matching-no-results").assertIsDisplayed()
        composeRule.onNodeWithTag("matching-empty").assertDoesNotExist()
        capture("matching-no-results") { composeRule.onNodeWithTag("matching-no-results").assertIsDisplayed() }
    }

    @Test fun rulesUpdateShowsLoadingAndResultWithLargeText() {
        val state = mutableStateOf(fixture().copy(episodeRules = EpisodeRulesUpdateStatus()))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { MatchingManagementScreen(state.value, event) }
            }
        }
        composeRule.onNodeWithTag("matching-update-episode-rules").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, event.ruleUpdates)
        composeRule.runOnIdle { state.value = state.value.copy(episodeRules = EpisodeRulesUpdateStatus(checking = true)) }
        composeRule.onNodeWithTag("matching-update-episode-rules").assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(episodeRules = EpisodeRulesUpdateStatus(updated = 2, failed = 1, completed = true)) }
        composeRule.onNodeWithTag("matching-episode-rules-result").performScrollTo().assertIsDisplayed()
        capture("matching-malsync-rules") { composeRule.onNodeWithTag("matching-episode-rules-result").assertIsDisplayed() }
    }

    private class RecordingEvents : MatchingManagementEvent {
        var starts = 0
        var ruleUpdates = 0
        override fun updateEpisodeRules() { ruleUpdates++ }
        var corrections = 0
        var targetText = ""
        override fun search(text: String) {}
        override fun filter(source: ExtensionSelectionKey?) {}
        override fun page(offset: Int) {}
        override fun select(entry: ManagedMapping, selected: Boolean) {}
        override fun clearSelection() {}
        override fun open(entry: ManagedMapping) {}
        override fun closeEditor() {}
        override fun targetQuery(text: String) { targetText = text }
        override fun findTargets(next: Boolean) {}
        override fun choose(target: MappingTarget) {}
        override fun correct() { corrections++ }
        override fun prepare(action: MappingAction, scope: MappingScope) {}
        override fun dismissConfirmation() {}
        override fun confirm() { starts++ }
        override fun cancel() {}
        override fun setOption(key: String, value: String) {}
        override fun askConfigurationReset() {}
        override fun dismissConfigurationReset() {}
        override fun resetConfiguration() {}
    }
}

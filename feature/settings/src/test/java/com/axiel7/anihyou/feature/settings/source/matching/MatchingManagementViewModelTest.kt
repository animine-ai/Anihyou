package com.axiel7.anihyou.feature.settings.source.matching

import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class MatchingManagementViewModelTest {
    private val key = ExtensionSelectionKey("source-a", "extension", "publisher", "aniworld")
    private val entry = ManagedMapping("a", "revision-1", key, "Signed source", "Source anime", "anime/season-2", "Season 2 / DE_SUB", 42)
    private val repository = FakeRepository()
    private var searchCalls = 0
    private val search = MatchingTargetSearch { _, _ -> searchCalls++; MappingTargetPage(listOf(MappingTarget(55, "Correct anime")), false) }
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }
    private fun model(): MatchingManagementViewModel {
        repository.rows.value = MappingPage(listOf(entry), 125, listOf(MappingSourceFacet(key, "Signed source", 125)))
        return MatchingManagementViewModel(repository, search)
    }

    @Test fun episodeRulesUpdateIsSingleFlightAndNeverRematchesBindings() = runTest {
        repository.rulesGate = CompletableDeferred()
        val vm = model()
        vm.updateEpisodeRules(); vm.updateEpisodeRules()
        assertEquals(1, repository.rulesUpdates)
        assertTrue(vm.state.value.busy)
        repository.rules.value = EpisodeRulesUpdateStatus(checking = true)
        assertTrue(vm.state.value.episodeRules.checking)
        repository.rules.value = EpisodeRulesUpdateStatus(updated = 3, failed = 1, completed = true)
        repository.rulesGate!!.complete(Unit)
        assertFalse(vm.state.value.busy)
        assertEquals(3, vm.state.value.episodeRules.updated)
        assertEquals(0, repository.matcherCalls)
        assertTrue(repository.resets.isEmpty())
    }
    @Test fun browseSearchFilterAndPagingNeverInvokeMatcherOrAniListSearch() = runTest {
        val vm = model()
        vm.search("Source anime"); vm.filter(key); vm.page(50)
        assertEquals(MappingQuery("Source anime", key, 50), repository.queries.last())
        assertEquals(0, searchCalls)
        assertEquals(0, repository.matcherCalls)
        assertTrue(repository.captures.isEmpty()); assertTrue(repository.resets.isEmpty())
    }
    @Test fun selectionPreservesCapturedRevisionsAcrossPagesAndRejectsUnrenderedEntry() = runTest {
        val vm = model(); vm.select(entry, true); vm.page(50)
        val next = entry.copy(id = "b", revision = "revision-9")
        repository.rows.value = repository.rows.value.copy(entries = listOf(next))
        vm.select(next, true); vm.select(entry.copy(id = "not-on-page"), true)
        vm.prepare(MappingAction.RESET, MappingScope.Entries(vm.state.value.selected.mapValues { it.value.revision }))
        assertEquals(MappingScope.Entries(mapOf("a" to "revision-1", "b" to "revision-9")), repository.captures.single())
        assertTrue(repository.resets.isEmpty())
    }
    @Test fun sourceAndGlobalActionsRequireConfirmationAndUseFrozenTokenRatherThanSearchResults() = runTest {
        val vm = model()
        vm.prepare(MappingAction.RESET, MappingScope.Source(key))
        assertEquals(125, vm.state.value.confirmation?.token?.count)
        assertTrue(repository.resets.isEmpty())
        val token = vm.state.value.confirmation!!.token
        repository.rows.value = repository.rows.value.copy(total = 999)
        vm.confirm()
        assertEquals(listOf(token), repository.resets)
        vm.prepare(MappingAction.REMATCH, MappingScope.All)
        assertEquals(MappingScope.All, repository.captures.last())
        assertEquals(0, repository.matcherCalls)
        vm.dismissConfirmation(); vm.confirm()
        assertEquals(0, repository.matcherCalls)
    }
    @Test fun filterChangeDiscardsPendingConfirmation() = runTest {
        val vm = model(); vm.prepare(MappingAction.RESET, MappingScope.All)
        vm.filter(key); vm.confirm()
        assertNull(vm.state.value.confirmation); assertTrue(repository.resets.isEmpty())
    }
    @Test fun searchRejectsEverySourceWideActionAndDropsOldSelectionAndCapture() = runTest {
        val vm = model(); vm.select(entry, true); vm.prepare(MappingAction.RESET, MappingScope.All)
        vm.search("Source")
        assertTrue(vm.state.value.selected.isEmpty()); assertNull(vm.state.value.confirmation)
        val before = repository.captures.size
        vm.prepare(MappingAction.RESET, MappingScope.All)
        vm.prepare(MappingAction.REMATCH, MappingScope.Source(key))
        vm.prepare(MappingAction.RESET, MappingScope.Entries(mapOf(entry.id to entry.revision)))
        vm.matchNow(); vm.searchNow(); vm.confirm()
        assertEquals(before, repository.captures.size)
        assertEquals(0, repository.unmatchedRuns); assertTrue(repository.resets.isEmpty())
        vm.select(entry, true)
        vm.prepare(MappingAction.RESET, MappingScope.Entries(mapOf(entry.id to entry.revision)))
        assertEquals(1, vm.state.value.confirmation?.token?.count)
    }
    @Test fun unmatchedSearchFoldsEveryWordAndSuggestionAndAppliesSourceBeforeRenderingCap() = runTest {
        val other = key.copy(sourceId = "source-b")
        repository.unmatched.value = (1..160).map { UnmatchedSeries(key, "series-$it", 1, "Ordinary $it") } +
            UnmatchedSeries(key, "late", 2, "L’Été: King", UnmatchedSuggestion(99, "Queen’s return", 0.8)) +
            UnmatchedSeries(other, "other", 2, "L’Été: King", UnmatchedSuggestion(99, "Queen’s return", 0.8))
        val vm = model(); vm.search("queens ete")
        assertEquals(2, vm.state.value.visibleUnmatched.size)
        vm.filter(key)
        assertEquals(listOf("late"), vm.state.value.visibleUnmatched.map { it.seriesKey })
        assertEquals(161, vm.state.value.scopedUnmatched.size)
        vm.search("ete missing"); assertTrue(vm.state.value.visibleUnmatched.isEmpty())
        assertEquals(0, searchCalls); assertEquals(0, repository.unmatchedRuns)
    }
    @Test fun staleCaptureReturningAfterFilterChangeCannotRestoreOldScope() = runTest {
        repository.captureGate = CompletableDeferred()
        val vm = model(); vm.prepare(MappingAction.RESET, MappingScope.All)
        assertTrue(vm.state.value.preparing)
        vm.search("new-filter"); repository.captureGate!!.complete(MappingActionToken("old", 125))
        assertNull(vm.state.value.confirmation); assertFalse(vm.state.value.preparing)
    }
    @Test fun staleResetKeepsEditorAndSelectionAndReportsConflict() = runTest {
        val vm = model(); vm.select(entry, true); vm.open(entry)
        repository.result = MappingMutationResult.STALE
        vm.prepare(MappingAction.RESET, MappingScope.Entries(mapOf(entry.id to entry.revision))); vm.confirm()
        assertEquals(MatchingNotice.STALE, vm.state.value.notice)
        assertEquals(entry, vm.state.value.editor); assertEquals(entry, vm.state.value.selected[entry.id])
    }
    @Test fun correctionUsesExistingIdentityRevisionAndExplicitSearchSelection() = runTest {
        val vm = model(); vm.open(entry); vm.correct()
        assertTrue(repository.corrections.isEmpty()); assertEquals(0, searchCalls)
        vm.targetQuery("correct anime"); vm.findTargets(); vm.choose(MappingTarget(777, "not-a-search-result")); vm.correct()
        assertTrue(repository.corrections.isEmpty())
        vm.choose(vm.state.value.targets.single()); vm.correct()
        assertEquals(listOf(Triple("a", "revision-1", 55)), repository.corrections)
        assertNull(vm.state.value.editor); assertEquals(1, searchCalls)
    }
    @Test fun changingEditorSearchDropsPreviouslySelectedTarget() = runTest {
        val vm = model(); vm.open(entry); vm.targetQuery("one"); vm.findTargets(); vm.choose(vm.state.value.targets.single())
        vm.targetQuery("different"); vm.correct()
        assertNull(vm.state.value.chosenTarget); assertTrue(repository.corrections.isEmpty())
    }
    @Test fun rematchCancellationKeepsReportedCompletedResultsAndDoesNotResetAnything() = runTest {
        repository.waitRematch = true
        val vm = model(); vm.prepare(MappingAction.REMATCH, MappingScope.All); vm.confirm()
        assertTrue(vm.state.value.busy); assertEquals(1, vm.state.value.progress?.completed)
        vm.cancel()
        assertFalse(vm.state.value.busy); assertTrue(repository.rematchCancelled)
        assertEquals(MatchingNotice.CANCELLED, vm.state.value.notice)
        assertEquals(MappingRematchOutcome.RETAINED, vm.state.value.recentResults.single().outcome)
        assertTrue(repository.resets.isEmpty())
    }
    @Test fun configurationResetNeverCapturesResetsOrRematchesBindings() = runTest {
        repository.options.value = listOf(MatcherOption("known", "Known option", listOf("default", "other"), "other"))
        val vm = model(); vm.resetConfiguration(); assertEquals(0, repository.configResets)
        vm.askConfigurationReset(); vm.resetConfiguration()
        assertEquals(1, repository.configResets); assertTrue(repository.captures.isEmpty()); assertTrue(repository.resets.isEmpty())
        assertEquals(0, repository.matcherCalls)
        vm.setOption("unknown", "other"); assertTrue(repository.optionChanges.isEmpty())
    }
    @Test fun emptyListDoesNotGenerateMappingsOrMetadataRequests() = runTest {
        val vm = model(); repository.rows.value = MappingPage(emptyList(), 0)
        assertTrue(vm.state.value.page.entries.isEmpty()); assertFalse(vm.state.value.loading)
        assertEquals(0, searchCalls); assertEquals(0, repository.matcherCalls)
    }

    private class FakeRepository : MatchingManagementRepository {
        val rows = MutableStateFlow(MappingPage(emptyList(), 0))
        val options = MutableStateFlow<List<MatcherOption>>(emptyList())
        val unmatched = MutableStateFlow<List<UnmatchedSeries>>(emptyList())
        val rules = MutableStateFlow(EpisodeRulesUpdateStatus())
        var rulesGate: CompletableDeferred<Unit>? = null
        var rulesUpdates = 0
        override fun observeEpisodeRulesStatus(): Flow<EpisodeRulesUpdateStatus> = rules
        override suspend fun updateEpisodeRules() { rulesUpdates++; rulesGate?.await() }
        var unmatchedRuns = 0
        val queries = mutableListOf<MappingQuery>()
        val captures = mutableListOf<MappingScope>()
        val resets = mutableListOf<MappingActionToken>()
        val corrections = mutableListOf<Triple<String, String, Int>>()
        val optionChanges = mutableListOf<Pair<String, String>>()
        var configResets = 0
        var matcherCalls = 0
        var result = MappingMutationResult.APPLIED
        var captureGate: CompletableDeferred<MappingActionToken>? = null
        var waitRematch = false
        var rematchCancelled = false
        override fun observePage(query: MappingQuery): Flow<MappingPage> { queries += query; return rows }
        override fun observeUnmatched(): Flow<List<UnmatchedSeries>> = unmatched
        override suspend fun matchUnmatchedNow(): Int { unmatchedRuns++; return 0 }
        override suspend fun searchUnmatchedNow(): Int { unmatchedRuns++; return 0 }
        override suspend fun capture(scope: MappingScope): MappingActionToken {
            captures += scope
            return captureGate?.await() ?: MappingActionToken("frozen-${captures.size}", if (scope is MappingScope.Entries) scope.revisions.size else 125)
        }
        override suspend fun reset(token: MappingActionToken): MappingMutationResult { resets += token; return result }
        override suspend fun correct(id: String, revision: String, mediaId: Int): MappingMutationResult {
            corrections += Triple(id, revision, mediaId); return result
        }
        override fun rematch(token: MappingActionToken): Flow<MappingRematchProgress> = flow {
            matcherCalls++
            try {
                emit(MappingRematchProgress(1, token.count, "a", MappingRematchOutcome.RETAINED))
                if (waitRematch) awaitCancellation()
            } finally { rematchCancelled = waitRematch }
        }
        override suspend fun ensureDetailMapping(mediaId: Int) { matcherCalls++ }
        override fun observeMatcherOptions(): Flow<List<MatcherOption>> = options
        override suspend fun setMatcherOption(key: String, value: String) { optionChanges += key to value }
        override suspend fun resetMatcherOptions() { configResets++ }
    }
}

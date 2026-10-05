package com.axiel7.anihyou.feature.settings.source.matching

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.matching.SearchTitleFolding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MappingAction { RESET, REMATCH }
enum class MatchingNotice { APPLIED, STALE, FAILED, CANCELLED, FINISHED, SELECTION_LIMIT }
data class MappingConfirmation(val action: MappingAction, val scope: MappingScope, val token: MappingActionToken)
data class MatchingManagementState(
    val query: MappingQuery = MappingQuery(),
    val page: MappingPage = MappingPage(emptyList(), 0),
    val loading: Boolean = true,
    val selected: Map<String, ManagedMapping> = emptyMap(),
    val editor: ManagedMapping? = null,
    val targetQuery: String = "",
    val targets: List<MappingTarget> = emptyList(),
    val targetPage: Int = 1,
    val hasMoreTargets: Boolean = false,
    val targetLoading: Boolean = false,
    val chosenTarget: MappingTarget? = null,
    val confirmation: MappingConfirmation? = null,
    val preparing: Boolean = false,
    val busy: Boolean = false,
    val progress: MappingRematchProgress? = null,
    val recentResults: List<MappingRematchProgress> = emptyList(),
    val notice: MatchingNotice? = null,
    val options: List<MatcherOption> = emptyList(),
    val confirmConfigurationReset: Boolean = false,
    /** The series of the active source that no binding covers yet. */
    val unmatched: List<UnmatchedSeries> = emptyList(),
    val unmatchedEditor: UnmatchedSeries? = null,
    /** How many series the last "match now" bound; null before the first run. */
    val lastMatched: Int? = null,
) {
    val searching: Boolean get() = query.text.isNotBlank()
    val sourceFacets: List<MappingSourceFacet> get() = page.sources + unmatched.distinctBy { it.source }
        .filter { series -> page.sources.none { it.key == series.source } }
        .map { MappingSourceFacet(it.source, it.sourceLabel, 0) }
    val scopedUnmatched: List<UnmatchedSeries> get() = unmatched.filter { query.source == null || it.source == query.source }
    val visibleUnmatched: List<UnmatchedSeries> get() = scopedUnmatched.filter {
        SearchTitleFolding.matches(query.text, it.title, it.seriesKey, it.suggestion?.title)
    }
}

interface MatchingManagementEvent {
    fun search(text: String)
    fun filter(source: ExtensionSelectionKey?)
    fun page(offset: Int)
    fun select(entry: ManagedMapping, selected: Boolean)
    fun clearSelection()
    fun open(entry: ManagedMapping)
    fun closeEditor()
    fun targetQuery(text: String)
    fun findTargets(next: Boolean = false)
    fun choose(target: MappingTarget)
    fun correct()
    fun prepare(action: MappingAction, scope: MappingScope)
    fun dismissConfirmation()
    fun confirm()
    fun cancel()
    fun setOption(key: String, value: String)
    fun askConfigurationReset()
    fun dismissConfigurationReset()
    fun resetConfiguration()
    /** Matches the unbound series of the active source automatically, now (AniList calendar and season pools). */
    fun matchNow() {}
    /** Opt-in: also asks AniList for the title of each series that stays open. Slower and heavier on AniList. */
    fun searchNow() {}
    fun openUnmatched(series: UnmatchedSeries) {}
    fun closeUnmatched() {}
    /** Binds the open unbound series to the chosen AniList entry. */
    fun assign() {}
}

/** Membership and revisions are captured before confirmation, never reconstructed from live filters. */
class MatchingManagementViewModel(
    private val repository: MatchingManagementRepository,
    private val targetSearch: MatchingTargetSearch,
) : ViewModel(), MatchingManagementEvent {
    private val mutable = MutableStateFlow(MatchingManagementState())
    val state = mutable.asStateFlow()
    private val query = MutableStateFlow(MappingQuery())
    private var captureJob: Job? = null
    private var actionJob: Job? = null
    private var searchJob: Job? = null
    private var captureGeneration = 0L
    private var searchGeneration = 0L

    init {
        viewModelScope.launch {
            query.collectLatest { value ->
                mutable.update { it.copy(query = value, loading = true) }
                try {
                    repository.observePage(value).collect { page ->
                        mutable.update { it.copy(page = page, loading = false) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutable.update { it.copy(page = MappingPage(emptyList(), 0), loading = false,
                        notice = MatchingNotice.FAILED) }
                }
            }
        }
        viewModelScope.launch {
            try { repository.observeUnmatched().collect { list -> mutable.update { it.copy(unmatched = list) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(unmatched = emptyList()) } }
        }
        viewModelScope.launch {
            try { repository.observeMatcherOptions().collect { options ->
                mutable.update { it.copy(options = options) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = MatchingNotice.FAILED) } }
        }
    }

    override fun search(text: String) = changeQuery(query.value.copy(text = text.take(256), offset = 0))
    override fun filter(source: ExtensionSelectionKey?) = changeQuery(query.value.copy(source = source, offset = 0))
    override fun page(offset: Int) {
        if (state.value.busy) return
        dismissConfirmation()
        query.value = query.value.copy(offset = offset.coerceAtLeast(0))
    }
    private fun changeQuery(value: MappingQuery) {
        if (state.value.busy) return
        dismissConfirmation()
        mutable.update { it.copy(query = value, loading = true, page = MappingPage(emptyList(), 0),
            selected = emptyMap(), notice = null) }
        query.value = value
    }

    override fun select(entry: ManagedMapping, selected: Boolean) {
        if (state.value.busy || state.value.preparing || state.value.confirmation != null) return
        // Only entries actually rendered on this page can be newly selected.
        if (state.value.page.entries.none { it.id == entry.id && it.revision == entry.revision }) return
        if (selected && entry.id !in state.value.selected && state.value.selected.size >= 500) {
            mutable.update { it.copy(notice = MatchingNotice.SELECTION_LIMIT) }
            return
        }
        mutable.update { it.copy(selected = if (selected) it.selected + (entry.id to entry) else it.selected - entry.id) }
    }
    override fun clearSelection() { if (!state.value.busy) mutable.update { it.copy(selected = emptyMap()) } }
    override fun open(entry: ManagedMapping) {
        if (state.value.busy || state.value.preparing) return
        searchJob?.cancel(); searchGeneration++
        mutable.update { it.copy(editor = entry, targetQuery = "", targets = emptyList(),
            targetPage = 1, chosenTarget = null, hasMoreTargets = false, targetLoading = false, notice = null) }
    }
    override fun closeEditor() {
        if (state.value.busy) return
        searchJob?.cancel(); searchGeneration++
        mutable.update { it.copy(editor = null, targets = emptyList(), targetLoading = false, chosenTarget = null) }
    }
    override fun targetQuery(text: String) {
        searchJob?.cancel(); searchGeneration++
        mutable.update { it.copy(targetQuery = text.take(256), targets = emptyList(),
            chosenTarget = null, targetPage = 1, hasMoreTargets = false, targetLoading = false) }
    }
    override fun findTargets(next: Boolean) {
        val snapshot = state.value
        if ((snapshot.editor == null && snapshot.unmatchedEditor == null) || snapshot.busy || snapshot.targetLoading ||
            snapshot.targetQuery.isBlank()) return
        if (next && !snapshot.hasMoreTargets) return
        val page = if (next) snapshot.targetPage + 1 else 1
        val generation = ++searchGeneration
        searchJob?.cancel()
        mutable.update { it.copy(targetLoading = true, targets = emptyList(), chosenTarget = null, notice = null) }
        searchJob = viewModelScope.launch {
            try {
                val result = targetSearch.search(snapshot.targetQuery, page)
                if (generation == searchGeneration) mutable.update { it.copy(
                    targets = result.targets, targetPage = page, hasMoreTargets = result.hasNext,
                    targetLoading = false, chosenTarget = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == searchGeneration) mutable.update {
                it.copy(targetLoading = false, notice = MatchingNotice.FAILED)
            } }
        }
    }
    override fun choose(target: MappingTarget) {
        if (!state.value.busy && target in state.value.targets) mutable.update { it.copy(chosenTarget = target) }
    }
    override fun matchNow() {
        if (state.value.busy || state.value.preparing || state.value.searching || !activeScopeVisible()) return
        mutate {
            val bound = repository.matchUnmatchedNow()
            mutable.update { it.copy(lastMatched = bound, notice = MatchingNotice.FINISHED) }
        }
    }
    override fun searchNow() {
        if (state.value.busy || state.value.preparing || state.value.searching || !activeScopeVisible()) return
        mutate {
            val bound = repository.searchUnmatchedNow()
            mutable.update { it.copy(lastMatched = bound, notice = MatchingNotice.FINISHED) }
        }
    }
    override fun openUnmatched(series: UnmatchedSeries) {
        if (state.value.busy || state.value.preparing) return
        if (series !in state.value.visibleUnmatched) return
        searchJob?.cancel(); searchGeneration++
        // The search starts from the nearest AniList title the matcher saw, else from the title the source gave; the user only has to press find.
        mutable.update { it.copy(unmatchedEditor = series, editor = null, targetQuery = (series.suggestion?.title ?: series.title).take(256), targets = emptyList(),
            targetPage = 1, chosenTarget = null, hasMoreTargets = false, targetLoading = false, notice = null) }
    }
    override fun closeUnmatched() {
        if (state.value.busy) return
        searchJob?.cancel(); searchGeneration++
        mutable.update { it.copy(unmatchedEditor = null, targets = emptyList(), targetLoading = false, chosenTarget = null) }
    }
    override fun assign() {
        val snapshot = state.value
        val series = snapshot.unmatchedEditor ?: return
        val target = snapshot.chosenTarget ?: return
        if (snapshot.busy || snapshot.preparing) return
        mutate {
            val result = repository.assignUnmatched(series, target.id)
            mutable.update { it.copy(notice = result.notice(),
                unmatchedEditor = if (result == MappingMutationResult.APPLIED) null else it.unmatchedEditor) }
        }
    }
    override fun correct() {
        val snapshot = state.value
        val editor = snapshot.editor ?: return
        val target = snapshot.chosenTarget ?: return
        if (snapshot.busy || snapshot.preparing || snapshot.confirmation != null) return
        mutate {
            val result = repository.correct(editor.id, editor.revision, target.id)
            mutable.update { it.copy(notice = result.notice(), editor = if (result == MappingMutationResult.APPLIED) null else it.editor,
                selected = if (result == MappingMutationResult.APPLIED) it.selected - editor.id else it.selected) }
        }
    }
    override fun prepare(action: MappingAction, scope: MappingScope) {
        if (state.value.busy || state.value.preparing) return
        if (state.value.searching) {
            if (scope !is MappingScope.Entries) return
            // A filtered confirmation may only contain explicitly selected entries or the open single editor.
            val allowed = state.value.selected + listOfNotNull(state.value.editor).associateBy { it.id }
            if (scope.revisions.any { (id, revision) -> allowed[id]?.revision != revision }) return
        }
        val generation = ++captureGeneration
        mutable.update { it.copy(preparing = true, confirmation = null, notice = null) }
        captureJob = viewModelScope.launch {
            try {
                val token = repository.capture(scope)
                if (generation == captureGeneration) mutable.update { it.copy(preparing = false,
                    confirmation = MappingConfirmation(action, scope, token)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == captureGeneration) mutable.update {
                it.copy(preparing = false, notice = MatchingNotice.FAILED)
            } }
        }
    }
    override fun dismissConfirmation() {
        captureGeneration++; captureJob?.cancel()
        mutable.update { it.copy(preparing = false, confirmation = null) }
    }
    override fun confirm() {
        val confirmation = state.value.confirmation ?: return
        if (state.value.busy || confirmation.token.count == 0) return
        mutable.update { it.copy(confirmation = null, progress = null, recentResults = emptyList()) }
        mutate {
            when (confirmation.action) {
                MappingAction.RESET -> {
                    val result = repository.reset(confirmation.token)
                    mutable.update { it.copy(notice = result.notice(),
                        selected = if (result == MappingMutationResult.APPLIED) emptyMap() else it.selected,
                        editor = if (result == MappingMutationResult.APPLIED) null else it.editor) }
                }
                MappingAction.REMATCH -> {
                    repository.rematch(confirmation.token).collect { progress ->
                        mutable.update { it.copy(progress = progress,
                            recentResults = if (progress.entryId == null) it.recentResults else
                                (it.recentResults + progress).takeLast(50)) }
                    }
                    mutable.update { it.copy(notice = MatchingNotice.FINISHED) }
                }
            }
        }
    }
    override fun cancel() {
        if (!state.value.busy) return
        actionJob?.cancel()
        mutable.update { it.copy(notice = MatchingNotice.CANCELLED) }
    }
    override fun setOption(key: String, value: String) {
        if (state.value.options.none { it.key == key && value in it.choices }) return
        mutate { repository.setMatcherOption(key, value); mutable.update { it.copy(notice = MatchingNotice.APPLIED) } }
    }
    override fun askConfigurationReset() {
        if (!state.value.busy && state.value.options.isNotEmpty()) mutable.update { it.copy(confirmConfigurationReset = true) }
    }
    override fun dismissConfigurationReset() { mutable.update { it.copy(confirmConfigurationReset = false) } }
    override fun resetConfiguration() {
        if (!state.value.confirmConfigurationReset) return
        mutable.update { it.copy(confirmConfigurationReset = false) }
        mutate { repository.resetMatcherOptions(); mutable.update { it.copy(notice = MatchingNotice.APPLIED) } }
    }
    private fun mutate(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, notice = null) }
        actionJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(notice = MatchingNotice.FAILED) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun activeScopeVisible(): Boolean = state.value.query.source.let { filter ->
        filter == null || state.value.unmatched.any { it.source == filter }
    }
    private fun MappingMutationResult.notice() = when (this) {
        MappingMutationResult.APPLIED -> MatchingNotice.APPLIED
        MappingMutationResult.STALE -> MatchingNotice.STALE
        MappingMutationResult.UNAVAILABLE -> MatchingNotice.FAILED
    }
}

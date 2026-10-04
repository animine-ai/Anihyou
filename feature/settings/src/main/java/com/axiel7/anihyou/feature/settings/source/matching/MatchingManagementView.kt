package com.axiel7.anihyou.feature.settings.source.matching

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.ListPreference
import com.axiel7.anihyou.core.ui.composables.PlainPreference
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.core.ui.composables.preferenceShape
import com.axiel7.anihyou.core.ui.composables.singleShape
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.core.ui.composables.common.SearchPillField
import com.axiel7.anihyou.core.resources.R as CoreR
import kotlinx.collections.immutable.toImmutableList
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.api.*
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext

@Composable
fun MatchingManagementView() {
    val repository = remember { GlobalContext.get().getOrNull<MatchingManagementRepository>() }
    if (repository == null) {
        MatchingManagementUnavailable()
        return
    }
    val searchRepository = koinInject<SearchRepository>()
    val model: MatchingManagementViewModel = viewModel(factory = remember(repository, searchRepository) {
        viewModelFactory { initializer { MatchingManagementViewModel(repository, AniListMatchingTargetSearch(searchRepository)) } }
    })
    val state by model.state.collectAsStateWithLifecycle()
    MatchingManagementScreen(state, model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchingManagementUnavailable() {
    val nav = LocalNavActionManager.current
    DefaultScaffoldWithSmallTopAppBar(title = stringResource(R.string.matching_title),
        navigationIcon = { BackIconButton(nav::goBack) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        Text(stringResource(R.string.matching_unavailable), Modifier.padding(padding).padding(16.dp)
            .testTag("matching-unavailable"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchingManagementScreen(state: MatchingManagementState, event: MatchingManagementEvent) {
    val nav = LocalNavActionManager.current
    // The page uses the language of the rest of the app: the pill search field of the anime and explore tabs, the grouped
    // rounded preference rows of the settings, chips for filters and 24 dp cards for the entries.
    DefaultScaffoldWithSmallTopAppBar(title = stringResource(R.string.matching_title),
        navigationIcon = { BackIconButton(nav::goBack) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("matching-list"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchPillField(value = state.query.text, onValueChange = event::search,
                        placeholder = stringResource(R.string.matching_search), enabled = !state.busy,
                        modifier = Modifier.testTag("matching-search"))
                    SourceFilter(state, event)
                    Text(stringResource(R.string.matching_count, state.page.total, state.selected.size),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    state.notice?.let {
                        Surface(shape = singleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(noticeText(it)), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp).testTag("matching-notice"))
                        }
                    }
                    if (state.loading || state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.busy) {
                        state.progress?.let { Text(stringResource(R.string.matching_progress, it.completed, it.total),
                            style = MaterialTheme.typography.bodyMedium) }
                        OutlinedButton(onClick = event::cancel, modifier = Modifier.testTag("matching-cancel")) {
                            Text(stringResource(R.string.matching_cancel))
                        }
                    }
                }
            }
            item {
                val enabled = !state.busy && !state.preparing && state.confirmation == null
                Column {
                    if (state.selected.isNotEmpty()) {
                        ScopeActions(stringResource(R.string.matching_scope_selected, state.selected.size), enabled,
                            scope = MappingScope.Entries(state.selected.mapValues { it.value.revision }), event = event, tag = "selection",
                            onClear = event::clearSelection)
                    }
                    state.query.source?.let { key ->
                        val facet = state.page.sources.singleOrNull { it.key == key }
                        ScopeActions(stringResource(R.string.matching_scope_source, "${facet?.label ?: key.sourceId} · ${key.sourceId} / ${key.extensionId}"), enabled,
                            scope = MappingScope.Source(key), event = event, tag = "source")
                    }
                    ScopeActions(stringResource(R.string.matching_scope_all), enabled,
                        scope = MappingScope.All, event = event, tag = "all")
                }
            }
            item {
                Column {
                    PreferencesTitle(stringResource(R.string.matching_unmatched_title, state.unmatched.size))
                    PlainPreference(title = stringResource(R.string.matching_match_now), icon = CoreR.drawable.refresh_24,
                        enabled = !state.busy && !state.preparing && state.confirmation == null, isLoading = state.busy,
                        onClick = event::matchNow, shape = singleShape,
                        modifier = Modifier.testTag("matching-match-now"))
                    state.lastMatched?.let {
                        Text(stringResource(R.string.matching_matched_now, it), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("matching-matched-now"))
                    }
                    if (state.unmatched.isEmpty()) Text(stringResource(R.string.matching_unmatched_empty),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("matching-unmatched-empty"))
                }
            }
            items(state.unmatched.take(UNMATCHED_SHOWN), key = { "unmatched-${it.seriesKey}-${it.season}" }) { series ->
                Card(onClick = { event.openUnmatched(series) }, enabled = !state.busy && !state.preparing,
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                        .testTag("unmatched-row-${series.seriesKey}-${series.season}")) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(series.title, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.matching_unmatched_season, series.season),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!state.loading && state.page.entries.isEmpty()) item {
                Text(stringResource(R.string.matching_empty), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("matching-empty"))
            }
            items(state.page.entries, key = { it.id }) { entry ->
                Card(onClick = { event.open(entry) }, enabled = !state.busy && !state.preparing,
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("mapping-row-${entry.id}")) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val selectionLabel = stringResource(R.string.matching_select_entry,
                            entry.sourceTitle?.takeIf { it.isNotBlank() } ?: entry.sourceIdentity,
                            entry.sourceLabel, entry.partLabel)
                        Checkbox(checked = entry.id in state.selected,
                            onCheckedChange = { event.select(entry, it) }, enabled = !state.busy && !state.preparing,
                            modifier = Modifier.testTag("mapping-select-${entry.id}").semantics { contentDescription = selectionLabel })
                        Column(Modifier.weight(1f).padding(end = 8.dp)) { MappingIdentity(entry) }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { event.page(state.query.offset - state.query.limit) },
                        enabled = state.query.offset > 0 && !state.busy && !state.loading,
                        modifier = Modifier.testTag("matching-previous")) { Text(stringResource(R.string.matching_previous)) }
                    TextButton(onClick = { event.page(state.query.offset + state.query.limit) },
                        enabled = state.query.offset + state.page.entries.size < state.page.total && !state.busy && !state.loading,
                        modifier = Modifier.testTag("matching-next")) { Text(stringResource(R.string.matching_next)) }
                }
            }
            if (state.recentResults.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(stringResource(R.string.matching_results), style = MaterialTheme.typography.titleSmall)
                    state.recentResults.forEach { result ->
                        Text("${result.entryId}: ${stringResource(outcomeText(result.outcome))}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Column {
                    PreferencesTitle(stringResource(R.string.matching_configuration))
                    if (state.options.isEmpty()) {
                        Text(stringResource(R.string.matching_no_options), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    val optionCount = state.options.size + if (state.options.isNotEmpty()) 1 else 0
                    state.options.forEachIndexed { index, option ->
                        ListPreference(title = option.label, values = option.choices.toImmutableList(),
                            icon = CoreR.drawable.settings_24,
                            preferenceValue = option.value, onValueChange = { event.setOption(option.key, it) },
                            shape = preferenceShape(index, optionCount))
                    }
                    if (state.options.isNotEmpty()) PlainPreference(title = stringResource(R.string.matching_reset_configuration),
                        icon = CoreR.drawable.refresh_24, enabled = !state.busy, onClick = event::askConfigurationReset,
                        shape = preferenceShape(optionCount - 1, optionCount),
                        modifier = Modifier.testTag("matching-reset-configuration"))
                }
            }
        }
    }
    state.editor?.let { MappingEditor(state, it, event) }
    state.unmatchedEditor?.let { UnmatchedEditor(state, it, event) }
    state.confirmation?.let { confirmation ->
        AlertDialog(onDismissRequest = event::dismissConfirmation,
            title = { Text(stringResource(if (confirmation.action == MappingAction.RESET) R.string.matching_reset else R.string.matching_rematch)) },
            text = { Column {
                Text(scopeText(confirmation.scope, state))
                Text(stringResource(R.string.matching_confirm_count, confirmation.token.count))
                Text(stringResource(if (confirmation.action == MappingAction.RESET) R.string.matching_reset_help else R.string.matching_rematch_help))
            } },
            confirmButton = { TextButton(onClick = event::confirm, enabled = confirmation.token.count > 0,
                modifier = Modifier.testTag("matching-confirm-action")) {
                // Only matching starts something; removing mappings and resetting the options just apply.
                Text(stringResource(if (confirmation.action == MappingAction.REMATCH) R.string.matching_start else R.string.matching_confirm))
            } },
            dismissButton = { TextButton(onClick = event::dismissConfirmation) { Text(stringResource(R.string.matching_cancel)) } })
    }
    if (state.confirmConfigurationReset) AlertDialog(onDismissRequest = event::dismissConfigurationReset,
        title = { Text(stringResource(R.string.matching_reset_configuration)) },
        text = { Text(stringResource(R.string.matching_configuration_reset_help)) },
        confirmButton = { TextButton(onClick = event::resetConfiguration) { Text(stringResource(R.string.matching_confirm)) } },
        dismissButton = { TextButton(onClick = event::dismissConfigurationReset) { Text(stringResource(R.string.matching_cancel)) } })
}

@Composable
private fun SourceFilter(state: MatchingManagementState, event: MatchingManagementEvent) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val label = state.page.sources.singleOrNull { it.key == state.query.source }?.label
            ?: state.query.source?.sourceId ?: stringResource(R.string.matching_all_sources)
        AssistChip(onClick = { expanded = true }, enabled = !state.busy, modifier = Modifier.testTag("matching-source-filter"),
            label = { Text(label) },
            leadingIcon = { Icon(painterResource(CoreR.drawable.filter_list_24), contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            trailingIcon = { Icon(painterResource(CoreR.drawable.arrow_drop_down_24), contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)) })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.matching_all_sources)) }, onClick = { expanded = false; event.filter(null) })
            state.page.sources.forEach { facet ->
                DropdownMenuItem(text = { Text("${facet.label} · ${facet.key.sourceId} / ${facet.key.extensionId} (${facet.count})") }, onClick = { expanded = false; event.filter(facet.key) })
            }
        }
    }
}

@Composable
private fun MappingIdentity(entry: ManagedMapping) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Text(entry.sourceTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.matching_source_id, entry.sourceIdentity),
        style = MaterialTheme.typography.titleMedium)
    Text(entry.sourceLabel.ifBlank { stringResource(R.string.matching_unknown_source) }, style = MaterialTheme.typography.bodySmall, color = secondary)
    if (entry.partLabel.isNotBlank()) Text(entry.partLabel, style = MaterialTheme.typography.bodySmall, color = secondary)
    val target = entry.targetTitle?.takeIf { it.isNotBlank() }?.let { "$it · AniList #${entry.mediaId}" } ?: "AniList #${entry.mediaId}"
    Text(target, style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(if (entry.manual) R.string.matching_manual else R.string.matching_automatic),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ScopeActions(
    label: String, enabled: Boolean, scope: MappingScope, event: MatchingManagementEvent, tag: String,
    onClear: (() -> Unit)? = null,
) {
    // Grouped rounded rows like the original settings pages; removing is marked by the error tint of its icon only.
    val count = if (onClear != null) 3 else 2
    PreferencesTitle(label)
    PlainPreference(title = stringResource(R.string.matching_rematch), icon = CoreR.drawable.refresh_24, enabled = enabled,
        onClick = { event.prepare(MappingAction.REMATCH, scope) }, shape = preferenceShape(0, count),
        modifier = Modifier.testTag("matching-rematch-$tag"))
    PlainPreference(title = stringResource(R.string.matching_reset), icon = CoreR.drawable.delete_24,
        iconTint = MaterialTheme.colorScheme.error, enabled = enabled,
        onClick = { event.prepare(MappingAction.RESET, scope) }, shape = preferenceShape(1, count),
        modifier = Modifier.testTag("matching-reset-$tag"))
    if (onClear != null) PlainPreference(title = stringResource(R.string.matching_clear_selection), icon = CoreR.drawable.close_24,
        enabled = enabled, onClick = onClear, shape = preferenceShape(2, count),
        modifier = Modifier.testTag("matching-clear-selection"))
}

@Composable
private fun MappingEditor(state: MatchingManagementState, entry: ManagedMapping, event: MatchingManagementEvent) {
    AlertDialog(onDismissRequest = event::closeEditor, modifier = Modifier.testTag("mapping-editor"),
        title = { Text(stringResource(R.string.matching_edit)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 440.dp)) {
                item {
                    MappingIdentity(entry)
                    Text(entry.sourceIdentity, style = MaterialTheme.typography.bodySmall)
                    SearchPillField(value = state.targetQuery, onValueChange = event::targetQuery, enabled = !state.busy,
                        placeholder = stringResource(R.string.matching_target_search),
                        onSearch = { if (state.targetQuery.isNotBlank()) event.findTargets(false) },
                        modifier = Modifier.testTag("matching-target-search"))
                    TextButton(onClick = { event.findTargets(false) }, enabled = !state.busy && !state.targetLoading && state.targetQuery.isNotBlank(),
                        modifier = Modifier.testTag("matching-find-targets")) { Text(stringResource(R.string.matching_find)) }
                    if (state.targetLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { Text(stringResource(noticeText(it))) }
                }
                items(state.targets, key = { it.id }) { target ->
                    TextButton(onClick = { event.choose(target) }, enabled = !state.busy,
                        modifier = Modifier.testTag("mapping-target-${target.id}")) {
                        Text("${if (state.chosenTarget == target) "✓ " else ""}${target.title} · #${target.id}")
                    }
                }
                if (state.hasMoreTargets) item {
                    TextButton(onClick = { event.findTargets(true) }, enabled = !state.busy && !state.targetLoading) { Text(stringResource(R.string.matching_next)) }
                }
                item { ScopeActions(stringResource(R.string.matching_scope_entry), !state.busy && !state.preparing,
                    MappingScope.Entries(mapOf(entry.id to entry.revision)), event, "entry") }
            }
        },
        confirmButton = { TextButton(onClick = event::correct, enabled = !state.busy && state.chosenTarget != null,
            modifier = Modifier.testTag("matching-save-target")) { Text(stringResource(R.string.matching_save_target)) } },
        dismissButton = { TextButton(onClick = event::closeEditor, enabled = !state.busy) { Text(stringResource(R.string.matching_close)) } })
}

@Composable
private fun UnmatchedEditor(state: MatchingManagementState, series: UnmatchedSeries, event: MatchingManagementEvent) {
    AlertDialog(onDismissRequest = event::closeUnmatched, modifier = Modifier.testTag("unmatched-editor"),
        title = { Text(stringResource(R.string.matching_assign_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 440.dp)) {
                item {
                    Text(series.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.matching_unmatched_season, series.season),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SearchPillField(value = state.targetQuery, onValueChange = event::targetQuery, enabled = !state.busy,
                        placeholder = stringResource(R.string.matching_target_search),
                        onSearch = { if (state.targetQuery.isNotBlank()) event.findTargets(false) },
                        modifier = Modifier.testTag("matching-target-search"))
                    TextButton(onClick = { event.findTargets(false) }, enabled = !state.busy && !state.targetLoading && state.targetQuery.isNotBlank(),
                        modifier = Modifier.testTag("matching-find-targets")) { Text(stringResource(R.string.matching_find)) }
                    if (state.targetLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.notice?.let { Text(stringResource(noticeText(it))) }
                }
                items(state.targets, key = { it.id }) { target ->
                    TextButton(onClick = { event.choose(target) }, enabled = !state.busy,
                        modifier = Modifier.testTag("mapping-target-${target.id}")) {
                        Text("${if (state.chosenTarget == target) "✓ " else ""}${target.title} · #${target.id}")
                    }
                }
                if (state.hasMoreTargets) item {
                    TextButton(onClick = { event.findTargets(true) }, enabled = !state.busy && !state.targetLoading) { Text(stringResource(R.string.matching_next)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = event::assign, enabled = !state.busy && state.chosenTarget != null,
            modifier = Modifier.testTag("matching-assign-target")) { Text(stringResource(R.string.matching_assign)) } },
        dismissButton = { TextButton(onClick = event::closeUnmatched, enabled = !state.busy) { Text(stringResource(R.string.matching_close)) } })
}

/** The list stays a bounded read; the rest follows as the automatic matching or the user binds series. */
private const val UNMATCHED_SHOWN = 150

@Composable
private fun scopeText(scope: MappingScope, state: MatchingManagementState): String = when (scope) {
    MappingScope.All -> stringResource(R.string.matching_scope_all)
    is MappingScope.Source -> stringResource(R.string.matching_scope_source,
        "${state.page.sources.singleOrNull { it.key == scope.key }?.label ?: scope.key.sourceId} · ${scope.key.sourceId} / ${scope.key.extensionId}")
    is MappingScope.Entries -> stringResource(R.string.matching_scope_selected, scope.revisions.size)
}
private fun noticeText(notice: MatchingNotice) = when (notice) {
    MatchingNotice.APPLIED -> R.string.matching_applied
    MatchingNotice.STALE -> R.string.matching_stale
    MatchingNotice.FAILED -> R.string.matching_failed
    MatchingNotice.CANCELLED -> R.string.matching_cancelled
    MatchingNotice.FINISHED -> R.string.matching_finished
    MatchingNotice.SELECTION_LIMIT -> R.string.matching_selection_limit
}
private fun outcomeText(outcome: MappingRematchOutcome?) = when (outcome) {
    MappingRematchOutcome.REPLACED -> R.string.matching_replaced
    MappingRematchOutcome.RETAINED -> R.string.matching_retained
    MappingRematchOutcome.STALE -> R.string.matching_stale
    else -> R.string.matching_failed
}

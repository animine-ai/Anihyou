package com.axiel7.anihyou.feature.settings.source.matching

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.api.*
import org.koin.compose.koinInject

@Composable
fun MatchingManagementView() {
    val repository = koinInject<MatchingManagementRepository>()
    val searchRepository = koinInject<SearchRepository>()
    val model: MatchingManagementViewModel = viewModel(factory = remember(repository, searchRepository) {
        viewModelFactory { initializer { MatchingManagementViewModel(repository, AniListMatchingTargetSearch(searchRepository)) } }
    })
    val state by model.state.collectAsStateWithLifecycle()
    MatchingManagementScreen(state, model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchingManagementScreen(state: MatchingManagementState, event: MatchingManagementEvent) {
    val nav = LocalNavActionManager.current
    DefaultScaffoldWithSmallTopAppBar(title = stringResource(R.string.matching_title),
        navigationIcon = { BackIconButton(nav::goBack) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("matching-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(state.query.text, event::search, modifier = Modifier.fillMaxWidth().testTag("matching-search"),
                    singleLine = true, label = { Text(stringResource(R.string.matching_search)) }, enabled = !state.busy)
                SourceFilter(state, event)
                Text(stringResource(R.string.matching_count, state.page.total, state.selected.size))
                state.notice?.let { Text(stringResource(noticeText(it)), modifier = Modifier.testTag("matching-notice")) }
                if (state.loading || state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.busy) {
                    state.progress?.let { Text(stringResource(R.string.matching_progress, it.completed, it.total)) }
                    TextButton(onClick = event::cancel, modifier = Modifier.testTag("matching-cancel")) {
                        Text(stringResource(R.string.matching_cancel))
                    }
                }
            }
            item {
                val enabled = !state.busy && !state.preparing && state.confirmation == null
                if (state.selected.isNotEmpty()) {
                    ScopeActions(stringResource(R.string.matching_scope_selected, state.selected.size), enabled,
                        scope = MappingScope.Entries(state.selected.mapValues { it.value.revision }), event = event, tag = "selection")
                    TextButton(onClick = event::clearSelection, enabled = enabled) { Text(stringResource(R.string.matching_clear_selection)) }
                }
                state.query.source?.let { key ->
                    val facet = state.page.sources.singleOrNull { it.key == key }
                    ScopeActions(stringResource(R.string.matching_scope_source, facet?.label ?: key.sourceId), enabled,
                        scope = MappingScope.Source(key), event = event, tag = "source")
                }
                ScopeActions(stringResource(R.string.matching_scope_all), enabled,
                    scope = MappingScope.All, event = event, tag = "all")
            }
            if (!state.loading && state.page.entries.isEmpty()) item {
                Text(stringResource(R.string.matching_empty), modifier = Modifier.testTag("matching-empty"))
            }
            items(state.page.entries, key = { it.id }) { entry ->
                Card(onClick = { event.open(entry) }, enabled = !state.busy && !state.preparing,
                    modifier = Modifier.fillMaxWidth().testTag("mapping-row-${entry.id}")) {
                    Row(Modifier.padding(12.dp)) {
                        Checkbox(checked = entry.id in state.selected,
                            onCheckedChange = { event.select(entry, it) }, enabled = !state.busy && !state.preparing,
                            modifier = Modifier.testTag("mapping-select-${entry.id}"))
                        Column(Modifier.weight(1f)) { MappingIdentity(entry) }
                    }
                }
            }
            item {
                Row {
                    TextButton(onClick = { event.page(state.query.offset - state.query.limit) },
                        enabled = state.query.offset > 0 && !state.busy && !state.loading,
                        modifier = Modifier.testTag("matching-previous")) { Text(stringResource(R.string.matching_previous)) }
                    TextButton(onClick = { event.page(state.query.offset + state.query.limit) },
                        enabled = state.query.offset + state.page.entries.size < state.page.total && !state.busy && !state.loading,
                        modifier = Modifier.testTag("matching-next")) { Text(stringResource(R.string.matching_next)) }
                }
            }
            if (state.recentResults.isNotEmpty()) item {
                Text(stringResource(R.string.matching_results), style = MaterialTheme.typography.titleSmall)
                state.recentResults.forEach { result ->
                    Text("${result.entryId}: ${stringResource(outcomeText(result.outcome))}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                HorizontalDivider()
                Text(stringResource(R.string.matching_configuration), style = MaterialTheme.typography.titleMedium)
                if (state.options.isEmpty()) Text(stringResource(R.string.matching_no_options))
                state.options.forEach { option ->
                    Text(option.label)
                    option.choices.forEach { choice ->
                        Row {
                            RadioButton(selected = choice == option.value, onClick = { event.setOption(option.key, choice) }, enabled = !state.busy)
                            Text(choice, Modifier.padding(top = 12.dp))
                        }
                    }
                }
                if (state.options.isNotEmpty()) TextButton(onClick = event::askConfigurationReset, enabled = !state.busy,
                    modifier = Modifier.testTag("matching-reset-configuration")) { Text(stringResource(R.string.matching_reset_configuration)) }
            }
        }
    }
    state.editor?.let { MappingEditor(state, it, event) }
    state.confirmation?.let { confirmation ->
        AlertDialog(onDismissRequest = event::dismissConfirmation,
            title = { Text(stringResource(if (confirmation.action == MappingAction.RESET) R.string.matching_reset else R.string.matching_rematch)) },
            text = { Column {
                Text(scopeText(confirmation.scope, state))
                Text(stringResource(R.string.matching_confirm_count, confirmation.token.count))
                Text(stringResource(if (confirmation.action == MappingAction.RESET) R.string.matching_reset_help else R.string.matching_rematch_help))
            } },
            confirmButton = { TextButton(onClick = event::confirm, enabled = confirmation.token.count > 0,
                modifier = Modifier.testTag("matching-confirm-action")) { Text(stringResource(R.string.matching_start)) } },
            dismissButton = { TextButton(onClick = event::dismissConfirmation) { Text(stringResource(R.string.matching_cancel)) } })
    }
    if (state.confirmConfigurationReset) AlertDialog(onDismissRequest = event::dismissConfigurationReset,
        title = { Text(stringResource(R.string.matching_reset_configuration)) },
        text = { Text(stringResource(R.string.matching_configuration_reset_help)) },
        confirmButton = { TextButton(onClick = event::resetConfiguration) { Text(stringResource(R.string.matching_start)) } },
        dismissButton = { TextButton(onClick = event::dismissConfigurationReset) { Text(stringResource(R.string.matching_cancel)) } })
}

@Composable
private fun SourceFilter(state: MatchingManagementState, event: MatchingManagementEvent) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val label = state.page.sources.singleOrNull { it.key == state.query.source }?.label
            ?: state.query.source?.sourceId ?: stringResource(R.string.matching_all_sources)
        TextButton(onClick = { expanded = true }, enabled = !state.busy, modifier = Modifier.testTag("matching-source-filter")) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.matching_all_sources)) }, onClick = { expanded = false; event.filter(null) })
            state.page.sources.forEach { facet ->
                DropdownMenuItem(text = { Text("${facet.label} (${facet.count})") }, onClick = { expanded = false; event.filter(facet.key) })
            }
        }
    }
}

@Composable
private fun MappingIdentity(entry: ManagedMapping) {
    Text(entry.sourceTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.matching_source_id, entry.sourceIdentity),
        style = MaterialTheme.typography.titleMedium)
    Text(entry.sourceLabel.ifBlank { stringResource(R.string.matching_unknown_source) }, style = MaterialTheme.typography.bodySmall)
    if (entry.partLabel.isNotBlank()) Text(entry.partLabel, style = MaterialTheme.typography.bodySmall)
    val target = entry.targetTitle?.takeIf { it.isNotBlank() }?.let { "$it · AniList #${entry.mediaId}" } ?: "AniList #${entry.mediaId}"
    Text(target, style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(if (entry.manual) R.string.matching_manual else R.string.matching_automatic), style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeActions(label: String, enabled: Boolean, scope: MappingScope, event: MatchingManagementEvent, tag: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow {
        TextButton(onClick = { event.prepare(MappingAction.REMATCH, scope) }, enabled = enabled,
            modifier = Modifier.testTag("matching-rematch-$tag")) { Text(stringResource(R.string.matching_rematch)) }
        TextButton(onClick = { event.prepare(MappingAction.RESET, scope) }, enabled = enabled,
            modifier = Modifier.testTag("matching-reset-$tag")) { Text(stringResource(R.string.matching_reset)) }
    }
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
                    OutlinedTextField(state.targetQuery, event::targetQuery, enabled = !state.busy,
                        label = { Text(stringResource(R.string.matching_target_search)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("matching-target-search"))
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
private fun scopeText(scope: MappingScope, state: MatchingManagementState): String = when (scope) {
    MappingScope.All -> stringResource(R.string.matching_scope_all)
    is MappingScope.Source -> stringResource(R.string.matching_scope_source,
        state.page.sources.singleOrNull { it.key == scope.key }?.label ?: scope.key.sourceId)
    is MappingScope.Entries -> stringResource(R.string.matching_scope_selected, scope.revisions.size)
}
private fun noticeText(notice: MatchingNotice) = when (notice) {
    MatchingNotice.APPLIED -> R.string.matching_applied
    MatchingNotice.STALE -> R.string.matching_stale
    MatchingNotice.FAILED -> R.string.matching_failed
    MatchingNotice.CANCELLED -> R.string.matching_cancelled
    MatchingNotice.FINISHED -> R.string.matching_finished
}
private fun outcomeText(outcome: MappingRematchOutcome?) = when (outcome) {
    MappingRematchOutcome.REPLACED -> R.string.matching_replaced
    MappingRematchOutcome.RETAINED -> R.string.matching_retained
    MappingRematchOutcome.STALE -> R.string.matching_stale
    else -> R.string.matching_failed
}

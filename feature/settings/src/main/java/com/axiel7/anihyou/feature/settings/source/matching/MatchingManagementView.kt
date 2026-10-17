package com.axiel7.anihyou.feature.settings.source.matching

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.model.media.localized
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.composables.media.MediaItemHorizontal
import kotlinx.coroutines.delay
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
import com.axiel7.anihyou.feature.settings.source.testTagPart
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
    val unmatched = state.visibleUnmatched
    val editing = state.editor
    val assigning = state.unmatchedEditor
    // Picking the AniList entry is a page of its own (search field and posters like Discover), never a popup.
    // The list page uses the language of the rest of the app: the pill search field of the anime and explore tabs, the
    // grouped rounded preference rows of the settings, chips for filters and 24 dp cards for the entries.
    if (editing != null) MappingEditor(state, editing, event)
    else if (assigning != null) UnmatchedEditor(state, assigning, event)
    else DefaultScaffoldWithSmallTopAppBar(title = stringResource(R.string.matching_title),
        navigationIcon = { BackIconButton(nav::goBack) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("matching-list"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchPillField(value = state.query.text, onValueChange = event::search,
                        placeholder = stringResource(R.string.matching_search), enabled = !state.busy,
                        modifier = Modifier.testTag("matching-search"))
                    SourceFilter(state, event)
                    Text(stringResource(R.string.matching_search_count, state.page.total, state.page.scopeTotal, state.selected.size),
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
                Column {
                    PreferencesTitle(stringResource(R.string.malsync_rules))
                    PlainPreference(title = stringResource(R.string.malsync_rules_update),
                        subtitle = stringResource(R.string.malsync_rules_description),
                        icon = CoreR.drawable.refresh_24,
                        enabled = !state.busy && !state.preparing && !state.episodeRules.checking,
                        isLoading = state.episodeRules.checking,
                        onClick = event::updateEpisodeRules, shape = singleShape,
                        modifier = Modifier.testTag("matching-update-episode-rules"))
                    state.episodeRules.checkedAt?.let { timestamp ->
                        Text(stringResource(R.string.malsync_rules_last_checked,
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                                java.text.DateFormat.SHORT).format(java.util.Date(timestamp))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    if (!state.episodeRules.checking && state.episodeRules.completed) {
                        Text(stringResource(R.string.malsync_rules_result,
                            state.episodeRules.updated, state.episodeRules.failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                                .testTag("matching-episode-rules-result"))
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
                    if (!state.searching) state.query.source?.let { key ->
                        val facet = state.sourceFacets.singleOrNull { it.key == key }
                        ScopeActions(stringResource(R.string.matching_scope_source, "${facet?.label ?: key.sourceId} · ${key.sourceId} / ${key.extensionId}"), enabled,
                            scope = MappingScope.Source(key), event = event, tag = "source")
                    }
                    if (!state.searching) ScopeActions(stringResource(R.string.matching_scope_all), enabled,
                        scope = MappingScope.All, event = event, tag = "all")
                }
            }
            if (unmatched.isNotEmpty() || (!state.searching && state.query.source == null)) item {
                Column {
                    PreferencesTitle(stringResource(R.string.matching_unmatched_title, unmatched.size))
                    Text(stringResource(R.string.matching_unmatched_count, unmatched.size, state.scopedUnmatched.size,
                        minOf(unmatched.size, UNMATCHED_SHOWN)), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.searching) {
                    PlainPreference(title = stringResource(R.string.matching_match_now), icon = CoreR.drawable.refresh_24,
                        enabled = !state.busy && !state.preparing && state.confirmation == null, isLoading = state.busy,
                        onClick = event::matchNow, shape = singleShape,
                        modifier = Modifier.testTag("matching-match-now"))
                    // Only on the user's wish: one AniList search per open title is slow, guesses and loads AniList.
                    PlainPreference(title = stringResource(R.string.matching_search_now),
                        subtitle = stringResource(R.string.matching_search_now_summary), icon = CoreR.drawable.search_24,
                        enabled = !state.busy && !state.preparing && state.confirmation == null && state.unmatched.isNotEmpty(),
                        onClick = event::searchNow, shape = singleShape,
                        modifier = Modifier.testTag("matching-search-now"))
                    state.lastMatched?.let {
                        Text(stringResource(R.string.matching_matched_now, it), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("matching-matched-now"))
                    }
                    }
                    if (unmatched.isEmpty()) Text(stringResource(R.string.matching_unmatched_empty),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("matching-unmatched-empty"))
                }
            }
            itemsIndexed(unmatched.take(UNMATCHED_SHOWN), key = { _, series -> "unmatched-${series.source.testTagPart()}-${series.seriesKey}-${series.season}" }) { index, series ->
                Card(onClick = { event.openUnmatched(series) }, enabled = !state.busy && !state.preparing,
                    shape = preferenceShape(index, minOf(unmatched.size, UNMATCHED_SHOWN)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth()
                        .testTag("unmatched-row-${series.seriesKey}-${series.season}")) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(series.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(series.sourceLabel.takeIf { it.isNotBlank() },
                            stringResource(R.string.matching_unmatched_season, series.season)).filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        series.suggestion?.let { suggestion ->
                            Text(stringResource(R.string.matching_unmatched_suggestion, suggestion.title),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("unmatched-suggestion-${series.seriesKey}-${series.season}"))
                        }
                    }
                }
            }
            if (!state.loading && state.page.total == 0 && unmatched.isEmpty()) item {
                Text(if (state.searching) stringResource(R.string.matching_no_results, state.query.text.trim())
                    else stringResource(R.string.matching_empty), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag(if (state.searching) "matching-no-results" else "matching-empty"))
            }
            if (state.page.total > 0) item {
                Column {
                    PreferencesTitle(stringResource(R.string.matching_saved_title))
                    MappingPager(state, event, "top")
                }
            }
            itemsIndexed(state.page.entries, key = { _, entry -> entry.id }) { index, entry ->
                Card(onClick = { event.open(entry) }, enabled = !state.busy && !state.preparing,
                    shape = preferenceShape(index, state.page.entries.size),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth().testTag("mapping-row-${entry.id}")) {
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
            item { MappingPager(state, event, "bottom") }
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

/** Previous and next page of the saved mappings; shown above and below the list, only when there is more than one page. */
@Composable
private fun MappingPager(state: MatchingManagementState, event: MatchingManagementEvent, position: String) {
    val hasPrevious = state.query.offset > 0
    val hasNext = state.query.offset + state.page.entries.size < state.page.total
    if (!hasPrevious && !hasNext) return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { event.page(state.query.offset - state.query.limit) },
            enabled = hasPrevious && !state.busy && !state.loading,
            modifier = Modifier.testTag("matching-previous" + if (position == "top") "-top" else "")) { Text(stringResource(R.string.matching_previous)) }
        TextButton(onClick = { event.page(state.query.offset + state.query.limit) },
            enabled = hasNext && !state.busy && !state.loading,
            modifier = Modifier.testTag("matching-next" + if (position == "top") "-top" else "")) { Text(stringResource(R.string.matching_next)) }
    }
}

@Composable
private fun SourceFilter(state: MatchingManagementState, event: MatchingManagementEvent) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        val label = state.sourceFacets.singleOrNull { it.key == state.query.source }?.label
            ?: state.query.source?.sourceId ?: stringResource(R.string.matching_all_sources)
        AssistChip(onClick = { expanded = true }, enabled = !state.busy, modifier = Modifier.testTag("matching-source-filter"),
            label = { Text(label) },
            leadingIcon = { Icon(painterResource(CoreR.drawable.filter_list_24), contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            trailingIcon = { Icon(painterResource(CoreR.drawable.arrow_drop_down_24), contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)) })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.matching_all_sources)) }, onClick = { expanded = false; event.filter(null) })
            state.sourceFacets.forEach { facet ->
                DropdownMenuItem(text = { Text("${facet.label} · ${facet.key.sourceId} / ${facet.key.extensionId} (${facet.count})") }, onClick = { expanded = false; event.filter(facet.key) })
            }
        }
    }
}

@Composable
private fun MappingIdentity(entry: ManagedMapping) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Text(entry.sourceTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.matching_source_id, entry.sourceIdentity),
        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(entry.targetTitle?.takeIf { it.isNotBlank() }?.let { "$it · AniList #${entry.mediaId}" } ?: "AniList #${entry.mediaId}",
        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    // One short line for everything else: where it comes from, which part, and who decided.
    Text(listOf(entry.sourceLabel.ifBlank { stringResource(R.string.matching_unknown_source) },
        entry.partLabel.takeIf { it.isNotBlank() },
        stringResource(if (entry.manual) R.string.matching_manual else R.string.matching_automatic)).filterNotNull().joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    TargetPickerPage(state, title = stringResource(R.string.matching_edit), tag = "mapping-editor",
        confirmLabel = stringResource(R.string.matching_save_target), confirmTag = "matching-save-target",
        onClose = event::closeEditor, onConfirm = event::correct, event = event,
        header = {
            MappingIdentity(entry)
            Text(entry.sourceIdentity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        footer = {
            ScopeActions(stringResource(R.string.matching_scope_entry), !state.busy && !state.preparing,
                MappingScope.Entries(mapOf(entry.id to entry.revision)), event, "entry")
        })
}

@Composable
private fun UnmatchedEditor(state: MatchingManagementState, series: UnmatchedSeries, event: MatchingManagementEvent) {
    TargetPickerPage(state, title = stringResource(R.string.matching_assign_title), tag = "unmatched-editor",
        confirmLabel = stringResource(R.string.matching_assign), confirmTag = "matching-assign-target",
        onClose = event::closeUnmatched, onConfirm = event::assign, event = event,
        header = {
            Text(series.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(series.sourceLabel.takeIf { it.isNotBlank() },
                stringResource(R.string.matching_unmatched_season, series.season)).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        })
}

/**
 * Manual matching as a page: the pill search field and the poster rows of Discover, without tags and filters. The search
 * starts by itself shortly after typing stops (and at once on the keyboard's search key); choosing a row only marks it,
 * the button at the bottom saves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPickerPage(
    state: MatchingManagementState, title: String, tag: String, confirmLabel: String, confirmTag: String,
    onClose: () -> Unit, onConfirm: () -> Unit, event: MatchingManagementEvent,
    header: @Composable ColumnScope.() -> Unit, footer: @Composable ColumnScope.() -> Unit = {},
) {
    BackHandler(enabled = !state.busy, onBack = onClose)
    LaunchedEffect(state.targetQuery) {
        if (state.targetQuery.isNotBlank() && state.targets.isEmpty() && !state.targetLoading) {
            delay(600)
            event.findTargets(false)
        }
    }
    DefaultScaffoldWithSmallTopAppBar(title = title, modifier = Modifier.testTag(tag),
        navigationIcon = { BackIconButton(onClose) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                header()
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SearchPillField(value = state.targetQuery, onValueChange = event::targetQuery, enabled = !state.busy,
                    placeholder = stringResource(R.string.matching_target_search),
                    onSearch = { if (state.targetQuery.isNotBlank()) event.findTargets(false) },
                    modifier = Modifier.testTag("matching-target-search"))
            }
            if (state.targetLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            state.notice?.let {
                Text(stringResource(noticeText(it)), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("matching-notice"))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("matching-target-list")) {
                items(state.targets, key = { it.id }) { target ->
                    TargetRow(target, chosen = state.chosenTarget == target, enabled = !state.busy,
                        onClick = { event.choose(target) })
                }
                if (state.hasMoreTargets) item {
                    TextButton(onClick = { event.findTargets(true) }, enabled = !state.busy && !state.targetLoading,
                        modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.matching_next)) }
                }
                item { Column { footer() } }
            }
            Button(onClick = onConfirm, enabled = !state.busy && state.chosenTarget != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()
                    .testTag(confirmTag)) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun TargetRow(target: MappingTarget, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val infos = listOfNotNull(target.format?.localized(), target.year?.toString(),
        target.episodes?.takeIf { it > 0 }?.let { pluralStringResource(CoreR.plurals.num_episodes, it, it) },
        "#${target.id}")
    val badge: (@Composable RowScope.() -> Unit)? = if (chosen) {
        { Icon(painterResource(CoreR.drawable.check_24), contentDescription = null, modifier = Modifier.size(18.dp)) }
    } else null
    Surface(color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth()
            .testTag("mapping-target-${target.id}")) {
        MediaItemHorizontal(title = target.title, imageUrl = target.imageUrl, blurImage = false,
            subtitle1 = { Text(infos.joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant) },
            status = null, topBadgeContent = badge, onClick = { if (enabled) onClick() })
    }
}

/** Rendering cap only. The repository supplies the complete unmatched list; filter before this cap. */
private const val UNMATCHED_SHOWN = 150

@Composable
private fun scopeText(scope: MappingScope, state: MatchingManagementState): String = when (scope) {
    MappingScope.All -> stringResource(R.string.matching_scope_all)
    is MappingScope.Source -> stringResource(R.string.matching_scope_source,
        "${state.sourceFacets.singleOrNull { it.key == scope.key }?.label ?: scope.key.sourceId} · ${scope.key.sourceId} / ${scope.key.extensionId}")
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

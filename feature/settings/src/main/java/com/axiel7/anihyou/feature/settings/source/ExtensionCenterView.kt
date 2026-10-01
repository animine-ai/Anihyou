package com.axiel7.anihyou.feature.settings.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.PlainPreference
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.*
import org.koin.compose.viewmodel.koinViewModel

enum class ExtensionCenterPage(val id: String, val title: Int) {
    MANAGE("manage", R.string.extension_center_manage),
    SOURCE("source", R.string.extension_center_source),
    PROVIDERS("providers", R.string.extension_center_providers),
    STATISTICS("statistics", R.string.extension_center_statistics),
    DIAGNOSTICS("diagnostics", R.string.extension_center_diagnostics),
}

@Composable
fun ExtensionCenterView() {
    val nav = LocalNavActionManager.current
    ExtensionCenterScaffold(stringResource(R.string.extension_center_title)) {
        ExtensionCenterMenu { page -> nav.navigate(Route.ExtensionCenterPage(page.id)) }
    }
}

@Composable
fun ExtensionCenterMenu(onPage: (ExtensionCenterPage) -> Unit) {
    ExtensionCenterPage.entries.forEach { page ->
        PlainPreference(title = stringResource(page.title),
            modifier = Modifier.testTag("extension-center-" + page.id),
            onClick = { onPage(page) })
    }
}

@Composable
fun ExtensionCenterPageView(pageId: String) {
    val page = ExtensionCenterPage.entries.firstOrNull { it.id == pageId } ?: ExtensionCenterPage.MANAGE
    val model: ExtensionSourcesViewModel = koinViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.sources, state.productPolicy.generation) {
        if (page == ExtensionCenterPage.STATISTICS || page == ExtensionCenterPage.DIAGNOSTICS) model.refreshDiagnostics()
    }
    ExtensionCenterScaffold(stringResource(page.title)) {
        if (page == ExtensionCenterPage.STATISTICS || page == ExtensionCenterPage.DIAGNOSTICS) {
            TextButton(onClick = model::refreshDiagnostics, modifier = Modifier.testTag("extension-details-refresh")) {
                Text(stringResource(R.string.extension_details_refresh))
            }
        }
        when (page) {
            ExtensionCenterPage.MANAGE -> ExtensionSourcesSettingsSection(state, model)
            ExtensionCenterPage.SOURCE -> ExtensionDataSourcePreferences(state, model)
            ExtensionCenterPage.PROVIDERS -> ExtensionProviderDisplay(state, model)
            ExtensionCenterPage.STATISTICS -> ExtensionStatistics(state)
            ExtensionCenterPage.DIAGNOSTICS -> ExtensionDiagnostics(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionCenterScaffold(title: String, content: @Composable () -> Unit) {
    val nav = LocalNavActionManager.current
    DefaultScaffoldWithSmallTopAppBar(title = title,
        navigationIcon = { BackIconButton(nav::goBack) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) { content() }
    }
}

internal fun installedEntries(state: ExtensionSourcesUiState): List<Pair<ExtensionSelectionKey, SourceExtension>> =
    state.sources.filter { it.enabled }.flatMap { source -> source.extensions.mapNotNull { extension ->
        source.selectionKey(extension)?.takeIf { extension.isUsableInstalled() }?.let { it to extension }
    } }

@Composable
fun ExtensionDataSourcePreferences(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    val entries = installedEntries(state)
    val releaseEntries = entries.filter { (_, e) -> e.capabilities.any { cap ->
        com.axiel7.anihyou.release.core.extension.SourceRole.entries.any { it.name == cap }
    } }
    val active = state.productPolicy.activeReleaseSource
    SelectionOption(stringResource(R.string.extension_sources_no_active_source),
        active !in releaseEntries.map { it.first }, state.canEditProductPolicy,
        { event.selectActiveSource(null) }, "extension-product-active-none")
    releaseEntries.forEach { (key, extension) ->
        SelectionOption(extension.displayName, active == key, state.canEditProductPolicy,
            { event.selectActiveSource(key) }, "extension-product-active-" + key.testTagPart())
    }
    entries.forEach { (key, extension) ->
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(extension.displayName)
        ExtensionTrackPreferences(key, extension,
            state.productPolicy.preferences[key] ?: ExtensionPreferences().withGenericDefaults(extension),
            state.canEditProductPolicy, canShowInProviderField = false, event = event)
    }
}

@Composable
fun ExtensionProviderDisplay(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    val entries = installedEntries(state).filter { it.second.supportsNavigation() }
        .sortedWith(compareBy<Pair<ExtensionSelectionKey, SourceExtension>> {
            state.productPolicy.navigationProviderOrder.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i }
        }.thenBy { it.first.sourceId }.thenBy { it.first.extensionId })
    SelectionOption(stringResource(R.string.extension_sources_no_preferred_navigation_provider),
        state.productPolicy.preferredNavigationProvider == null, state.canEditProductPolicy,
        { event.selectNavigationProvider(null) }, "extension-product-navigation-none")
    entries.forEachIndexed { index, (key, extension) ->
        val preferences = state.productPolicy.preferences[key] ?: ExtensionPreferences().withGenericDefaults(extension)
        Text(extension.displayName)
        TrackSwitch(stringResource(R.string.extension_sources_visible_in_provider_field),
            preferences.visibleInProviderField, state.canEditProductPolicy,
            "extension-preference-provider-visible-" + key.testTagPart()) {
            event.setPreferences(key, preferences.copy(visibleInProviderField = it))
        }
        SelectionOption(stringResource(R.string.extension_sources_prefer_navigation_provider),
            state.productPolicy.preferredNavigationProvider == key, state.canEditProductPolicy,
            { event.selectNavigationProvider(key) }, "extension-product-navigation-" + key.testTagPart())
        Row {
            fun move(offset: Int) {
                val keys = entries.map { it.first }.toMutableList()
                keys.removeAt(index); keys.add((index + offset).coerceIn(0, keys.size), key)
                event.setProviderOrder(keys)
            }
            TextButton(onClick = { move(-1) }, enabled = index > 0 && state.canEditProductPolicy,
                modifier = Modifier.testTag("provider-up-" + key.testTagPart())) { Text("↑") }
            TextButton(onClick = { move(1) }, enabled = index < entries.lastIndex && state.canEditProductPolicy,
                modifier = Modifier.testTag("provider-down-" + key.testTagPart())) { Text("↓") }
        }
        HorizontalDivider()
    }
}

@Composable
fun ExtensionStatistics(state: ExtensionSourcesUiState) {
    val key = state.productPolicy.activeReleaseSource
    val extension = installedEntries(state).singleOrNull { it.first == key }?.second
    if (key == null || extension == null) {
        Text(stringResource(R.string.extension_statistics_empty)); return
    }
    Text(extension.displayName)
    Text("Version: " + extension.installedVersion.orEmpty())
    Text("Update: " + if (extension.updateAvailable) stringResource(R.string.extension_update)
        else stringResource(R.string.extension_sources_no_update))
    val values = state.diagnostics[key].orEmpty()
    listOf("Last successful sync", "Freshness", "Release count", "Tracks", "Role health", "Last sync outcome", "Sync duration",
        "Runtime", "Last parse status", "Last navigation status").forEach {
        DiagnosticRow(it, values[it])
    }
}

@Composable
fun ExtensionDiagnostics(state: ExtensionSourcesUiState) {
    val clipboard = LocalClipboardManager.current
    state.sources.flatMap { source -> source.extensions.mapNotNull { extension ->
        source.selectionKey(extension)?.let { it to extension }
    } }.forEach { (key, extension) ->
        val values = mapOf(
            "Extension ID" to key.extensionId, "Provider ID" to key.providerId,
            "Signed displayName" to extension.displayName, "Publisher" to key.publisherId,
            "Version" to extension.installedVersion.orEmpty(), "Package SHA" to extension.installedDigest.orEmpty(),
            "Active selection generation" to state.productPolicy.releaseGeneration.toString()) +
            state.diagnostics[key].orEmpty()
        Text(extension.displayName)
        listOf("Extension ID", "Provider ID", "Signed displayName", "Version", "Repository", "Publisher",
            "Key ID", "Trust status", "Package SHA", "WASM SHA", "Active selection generation",
            "Active package generation", "LKG", "Previous Good", "Capabilities", "Allowed Hosts",
            "Role health", "Last metadata failure", "Last sync outcome", "Last sync failure", "Transport status", "Fuel limit", "Memory limit",
            "Deadline limit", "Cancellation", "Last parse status", "Last navigation status",
            "Rollback", "Quarantine").forEach { DiagnosticRow(it, values[it]) }
        TextButton(onClick = { clipboard.setText(AnnotatedString(
            values.entries.joinToString("\n") { it.key + ": " + it.value })) },
            modifier = Modifier.testTag("diagnostics-copy-" + key.testTagPart())) {
            Text(stringResource(R.string.extension_diagnostics_copy))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String?) {
    Text(label + ": " + value?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty {
        stringResource(R.string.extension_no_measurement)
    })
}

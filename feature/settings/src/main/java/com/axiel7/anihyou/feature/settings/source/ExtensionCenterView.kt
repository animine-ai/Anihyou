package com.axiel7.anihyou.feature.settings.source

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.core.ui.composables.PlainPreference
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.core.ui.composables.SwitchPreference
import com.axiel7.anihyou.core.ui.composables.preferenceShape
import com.axiel7.anihyou.core.ui.composables.singleShape
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.*
import org.koin.compose.viewmodel.koinViewModel

enum class ExtensionCenterPage(val id: String, val title: Int, @DrawableRes val icon: Int) {
    MANAGE("manage", R.string.extension_center_manage, CoreR.drawable.settings_24),
    SOURCE("source", R.string.extension_center_source, CoreR.drawable.rss_feed_24),
    PROVIDERS("providers", R.string.extension_center_providers, CoreR.drawable.play_circle_24),
    STATISTICS("statistics", R.string.extension_center_statistics, CoreR.drawable.bar_chart_24),
    DIAGNOSTICS("diagnostics", R.string.extension_center_diagnostics, CoreR.drawable.info_24),
}

@Composable
fun ExtensionCenterView() {
    val nav = LocalNavActionManager.current
    val model: ExtensionSourcesViewModel = koinViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    ExtensionCenterScaffold(stringResource(R.string.extension_center_title)) {
        if (!state.trustAvailable) ExtensionTrustUnavailableNotice()
        ExtensionCenterMenu { page -> nav.navigate(Route.ExtensionCenterPage(page.id)) }
    }
}

/**
 * Shown when this build has no independently authenticated source identity. The area stays visible and readable,
 * but nothing that can only fail later (add, install, update, rollback) is offered. Local cleanup stays possible.
 */
@Composable
fun ExtensionTrustUnavailableNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("extension-trust-unavailable"),
        shape = singleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.extension_center_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.extension_center_unavailable_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun ExtensionCenterMenu(onPage: (ExtensionCenterPage) -> Unit) {
    listOf(
        R.string.extension_center_management_section to listOf(
            ExtensionCenterPage.MANAGE, ExtensionCenterPage.SOURCE, ExtensionCenterPage.PROVIDERS),
        R.string.extension_center_diagnostics_section to listOf(
            ExtensionCenterPage.STATISTICS, ExtensionCenterPage.DIAGNOSTICS),
    ).forEach { (title, pages) ->
        PreferencesTitle(text = stringResource(title))
        pages.forEachIndexed { index, page ->
            PlainPreference(title = stringResource(page.title), icon = page.icon,
                shape = preferenceShape(index, pages.size),
                modifier = Modifier.testTag("extension-center-" + page.id),
                onClick = { onPage(page) })
        }
    }
}

@Composable
fun ExtensionCenterPageView(pageId: String) {
    if (pageId == "matching") {
        com.axiel7.anihyou.feature.settings.source.matching.MatchingManagementView()
        return
    }
    if (pageId == "schedule") {
        com.axiel7.anihyou.feature.settings.source.schedule.ExtensionRefreshScheduleView()
        return
    }
    val page = ExtensionCenterPage.entries.firstOrNull { it.id == pageId } ?: ExtensionCenterPage.MANAGE
    val model: ExtensionSourcesViewModel = koinViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.sources, state.productPolicy.generation) {
        if (page == ExtensionCenterPage.STATISTICS || page == ExtensionCenterPage.DIAGNOSTICS) model.refreshDiagnostics()
    }
    ExtensionCenterScaffold(stringResource(page.title)) {
        if (!state.trustAvailable) ExtensionTrustUnavailableNotice()
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
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) { content() }
    }
}

internal fun installedEntries(state: ExtensionSourcesUiState): List<Pair<ExtensionSelectionKey, SourceExtension>> =
    state.sources.filter { it.enabled }.flatMap { source -> source.extensions.mapNotNull { extension ->
        source.selectionKey(extension)?.takeIf { extension.isUsableInstalled() }?.let { it to extension }
    } }

@Composable
fun ExtensionDataSourcePreferences(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    val nav = LocalNavActionManager.current
    PlainPreference(title = stringResource(R.string.matching_title), icon = CoreR.drawable.link_24,
        shape = preferenceShape(0, 2), modifier = Modifier.testTag("extension-source-matching"),
        onClick = { nav.navigate(Route.ExtensionCenterPage("matching")) })
    PlainPreference(title = stringResource(R.string.extension_center_schedule), icon = CoreR.drawable.schedule_24,
        shape = preferenceShape(1, 2), modifier = Modifier.testTag("extension-source-schedule"),
        onClick = { nav.navigate(Route.ExtensionCenterPage("schedule")) })
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    val entries = installedEntries(state)
    val canEdit = state.canEditProductPolicy && !state.hasSourceOperationInFlight()
    val releaseEntries = entries.filter { (_, e) -> e.capabilities.any { cap ->
        com.axiel7.anihyou.release.core.extension.SourceRole.entries.any { it.name == cap }
    } }
    val active = state.productPolicy.activeReleaseSource
    PreferencesTitle(text = stringResource(R.string.extension_sources_data_source_heading))
    SelectionOption(stringResource(R.string.extension_sources_no_active_source),
        active !in releaseEntries.map { it.first }, canEdit,
        { event.selectActiveSource(null) }, "extension-product-active-none")
    releaseEntries.forEach { (key, extension) ->
        SelectionOption(extension.displayName, active == key, canEdit,
            { event.selectActiveSource(key) }, "extension-product-active-" + key.testTagPart())
    }
    entries.forEach { (key, extension) ->
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        PreferencesTitle(text = extension.displayName)
        ExtensionTrackPreferences(key, extension,
            state.productPolicy.preferences[key] ?: ExtensionPreferences().withGenericDefaults(extension),
            canEdit, canShowInProviderField = false, event = event)
    }
}

@Composable
fun ExtensionProviderDisplay(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    val canEdit = state.canEditProductPolicy && !state.hasSourceOperationInFlight()
    val entries = installedEntries(state).filter { it.second.supportsNavigation() }
        .sortedWith(compareBy<Pair<ExtensionSelectionKey, SourceExtension>> {
            state.productPolicy.navigationProviderOrder.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i }
        }.thenBy { it.first.sourceId }.thenBy { it.first.extensionId })
    PreferencesTitle(text = stringResource(R.string.extension_provider_visible_section))
    if (entries.isEmpty()) {
        Text(stringResource(R.string.extension_provider_empty), modifier = Modifier.padding(horizontal = 16.dp))
    }
    entries.forEachIndexed { index, (key, extension) ->
        val preferences = state.productPolicy.preferences[key] ?: ExtensionPreferences().withGenericDefaults(extension)
        SwitchPreference(
            title = extension.displayName,
            preferenceValue = preferences.visibleInProviderField,
            icon = CoreR.drawable.play_circle_24,
            enabled = canEdit,
            shape = preferenceShape(index, entries.size),
            modifier = Modifier.testTag("extension-preference-provider-visible-" + key.testTagPart())
                .semantics { toggleableState = if (preferences.visibleInProviderField) ToggleableState.On else ToggleableState.Off
                    role = Role.Switch },
            onValueChange = { event.setPreferences(key, preferences.copy(visibleInProviderField = it)) },
        )
    }
    if (entries.isNotEmpty()) {
        PreferencesTitle(text = stringResource(R.string.extension_provider_preferred_section))
        Text(stringResource(R.string.extension_provider_preferred_explanation),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium)
        SelectionOption(stringResource(R.string.extension_sources_no_preferred_navigation_provider),
            state.productPolicy.preferredNavigationProvider == null, canEdit,
            { event.selectNavigationProvider(null) }, "extension-product-navigation-none")
        entries.forEach { (key, extension) ->
            SelectionOption(extension.displayName, state.productPolicy.preferredNavigationProvider == key, canEdit,
                { event.selectNavigationProvider(key) }, "extension-product-navigation-" + key.testTagPart())
        }
        PreferencesTitle(text = stringResource(R.string.extension_provider_order_section))
        entries.forEachIndexed { index, (key, extension) ->
            Text(extension.displayName, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                fun move(offset: Int) {
                    val keys = entries.map { it.first }.toMutableList()
                    keys.removeAt(index); keys.add((index + offset).coerceIn(0, keys.size), key)
                    event.setProviderOrder(keys)
                }
                val moveUp = stringResource(R.string.extension_provider_move_up, extension.displayName)
                val moveDown = stringResource(R.string.extension_provider_move_down, extension.displayName)
                IconButton(onClick = { move(-1) }, enabled = index > 0 && canEdit,
                    modifier = Modifier.testTag("provider-up-" + key.testTagPart())) {
                    Icon(painterResource(CoreR.drawable.arrow_upward_24), contentDescription = moveUp)
                }
                IconButton(onClick = { move(1) }, enabled = index < entries.lastIndex && canEdit,
                    modifier = Modifier.testTag("provider-down-" + key.testTagPart())) {
                    Icon(painterResource(CoreR.drawable.arrow_downward_24), contentDescription = moveDown)
                }
            }
        }
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
    val values = state.diagnostics[key].orEmpty()
    val source = state.sources.singleOrNull { it.id == key.sourceId }
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_installed_version), extension.installedVersion)
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_latest_version), extension.latestAvailableVersion)
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_update_state),
        stringResource(extensionUpdateStateLabel(extension.updateState)))
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_package_status),
        stringResource(installedPackageStatusLabel(extension)))
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_package_generation),
        extension.packageGeneration.toString())
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_release_sequence),
        extension.installedReleaseSequence?.toString())
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_metadata),
        stringResource(if (extension.metadataFresh) R.string.extension_manage_diagnostic_fresh
            else R.string.extension_manage_diagnostic_stale))
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_yanked),
        stringResource(if (extension.candidateYanked) R.string.extension_manage_diagnostic_yes
            else R.string.extension_manage_diagnostic_no))
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_last_update),
        values["Last Update Check"]?.takeIf { it.isNotBlank() } ?: source?.lastAttemptAt?.toString())
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_update_result), extension.lastUpdateResult)
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_update_failure),
        extension.lastUpdateFailure?.let { stringResource(updateFailureLabel(it)) })
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_previous_good),
        extension.rollbackTarget?.let { "${it.version} · ${it.trustState} · ${it.digest.take(12)}" })
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_failure_code),
        values["Last Update Failure Code"]?.takeIf(::isSafeTechnicalCode)
            ?: extension.lastUpdateTechnicalCode?.takeIf(::isSafeTechnicalCode))
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_last_successful_update), values["Last Successful Update"])
    DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_last_metadata_success), values["Last metadata success"])
    // Update check, successful update, release sequence and rollback availability already have localized rows above.
    // The raw revocation digest list and the other update facts stay in Diagnostics and its copy export.
    listOf("Last successful sync", "Freshness", "Release count", "Tracks", "Role health", "Last sync outcome", "Sync duration",
        "Runtime", "Last parse status", "Last navigation status").forEach {
        DiagnosticRow(it, values[it])
    }
}

@Composable
fun ExtensionDiagnostics(state: ExtensionSourcesUiState) {
    val clipboard = LocalClipboardManager.current
    state.sources.flatMap { source -> source.extensions.mapNotNull { extension ->
        source.selectionKey(extension)?.let { Triple(source, it, extension) }
    } }.forEach { (source, key, extension) ->
        val values = mapOf(
            "Extension ID" to key.extensionId, "Provider ID" to key.providerId,
            "Signed displayName" to extension.displayName, "Publisher" to key.publisherId,
            "Version" to extension.installedVersion.orEmpty(), "Package SHA" to extension.installedDigest.orEmpty(),
            "Active selection generation" to state.productPolicy.releaseGeneration.toString(),
            "Latest authenticated version" to extension.latestAvailableVersion.orEmpty(),
            "Installed package status" to extension.installedStatus.name,
            "Update state" to extension.updateState.name,
            "Package generation" to extension.packageGeneration.toString(),
            "Metadata fresh" to extension.metadataFresh.toString(),
            "Candidate yanked" to extension.candidateYanked.toString(),
            "Last update at" to extension.lastUpdateAt?.toString().orEmpty(),
            "Last update result" to extension.lastUpdateResult.orEmpty(),
            "Last update failure" to extension.lastUpdateFailure?.name.orEmpty(),
            "Previous Good" to extension.rollbackTarget?.let {
                "${it.version}; ${it.trustState}; ${it.digest}"
            }.orEmpty(),
            "Current Version" to extension.installedVersion.orEmpty(),
            "Latest Available" to extension.latestAvailableVersion.orEmpty(),
            "Release Sequence" to extension.installedReleaseSequence?.toString().orEmpty(),
            "Active package generation" to extension.packageGeneration.toString(),
            "Known Good" to extension.installedVersion.takeIf { extension.installedUsable }.orEmpty(),
            "Previous Good Version" to extension.rollbackTarget?.version.orEmpty(),
            "Last Update Check" to source.lastAttemptAt?.toString().orEmpty(),
            "Last Update Result" to extension.lastUpdateResult.orEmpty(),
            "Last Update Failure" to extension.lastUpdateFailure?.name.orEmpty(),
            "Last Update Failure Code" to extension.lastUpdateTechnicalCode?.takeIf(::isSafeTechnicalCode).orEmpty(),
            "Last Successful Update" to "",
            "Rollback Available" to (extension.rollbackTarget != null).toString(),
            "Revocation" to (extension.revoked || extension.installedStatus == InstalledPackageStatus.REVOKED).toString(),
            "Repository metadata freshness" to if (extension.metadataFresh) "FRESH" else "STALE_OR_UNAVAILABLE",
            "Last metadata success" to "",
            "Yanked candidate" to extension.candidateYanked.toString()) +
            state.diagnostics[key].orEmpty()
        val safeValues = safeDiagnosticEntries(values).toMap()
        Text(extension.displayName)
        // Facts that have a localized row below (version, update state, package generation, Known/Previous
        // Good, metadata freshness, yanked, update result) are shown there only, so each value appears once.
        // The copy-to-clipboard export below still carries every raw entry.
        listOf("Extension ID", "Provider ID", "Signed displayName", "Repository", "Publisher",
            "Key ID", "Trust status", "Package SHA", "WASM SHA", "Active selection generation",
            "Last update at", "Capabilities", "Allowed Hosts", "Role health",
            "Last sync outcome", "Runtime", "Last parse status", "Last navigation status", "Fuel limit",
            "Memory limit", "Deadline limit", "Cancellation").forEach { DiagnosticRow(it, safeValues[it]) }
        // Permanent for a source the user accepted without an independent check.
        if (source.manuallyTrusted) DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_trust_class),
            stringResource(R.string.extension_manage_manual_trust))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_installed_version),
            safeValues["Current Version"]?.takeIf { it.isNotBlank() } ?: extension.installedVersion)
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_latest_version),
            safeValues["Latest Available"]?.takeIf { it.isNotBlank() } ?: extension.latestAvailableVersion)
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_release_sequence),
            safeValues["Release Sequence"]?.takeIf { it.isNotBlank() } ?: extension.installedReleaseSequence?.toString())
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_update_state),
            stringResource(extensionUpdateStateLabel(extension.updateState)))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_package_status),
            stringResource(installedPackageStatusLabel(extension)))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_package_generation),
            extension.packageGeneration.toString())
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_known_good),
            safeValues["Known Good"]?.takeIf { it.isNotBlank() } ?: extension.installedVersion.takeIf { extension.installedUsable })
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_previous_good),
            extension.rollbackTarget?.let { "${it.version} · ${it.trustState} · ${it.digest.take(12)}" })
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_last_update),
            safeValues["Last Update Check"]?.takeIf { it.isNotBlank() } ?: source.lastAttemptAt?.toString())
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_update_result),
            safeValues["Last Update Result"]?.takeIf { it.isNotBlank() } ?: extension.lastUpdateResult)
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_update_failure),
            extension.lastUpdateFailure?.let { stringResource(updateFailureLabel(it)) })
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_failure_code),
            safeValues["Last Update Failure Code"]?.takeIf(::isSafeTechnicalCode))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_last_successful_update),
            safeValues["Last Successful Update"])
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_rollback_available),
            stringResource(if (extension.rollbackTarget != null) R.string.extension_manage_diagnostic_yes
                else R.string.extension_manage_diagnostic_no))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_revocation),
            stringResource(if (extension.revoked || extension.installedStatus == InstalledPackageStatus.REVOKED)
                R.string.extension_manage_trust_revoked else R.string.extension_manage_diagnostic_no))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_metadata),
            stringResource(if (extension.metadataFresh) R.string.extension_manage_diagnostic_fresh
                else R.string.extension_manage_diagnostic_stale))
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_last_metadata_success),
            safeValues["Last metadata success"]?.takeIf { it.isNotBlank() })
        DiagnosticRow(stringResource(R.string.extension_manage_diagnostic_yanked),
            stringResource(if (extension.candidateYanked) R.string.extension_manage_diagnostic_yes
                else R.string.extension_manage_diagnostic_no))
        TextButton(onClick = { clipboard.setText(AnnotatedString(
            safeDiagnosticEntries(values).joinToString("\n") { (name, value) -> "$name: $value" })) },
            modifier = Modifier.testTag("diagnostics-copy-" + key.testTagPart())) {
            Text(stringResource(R.string.extension_diagnostics_copy))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
    }
}

private fun ExtensionSourcesUiState.hasSourceOperationInFlight(): Boolean =
    busySourceIds.isNotEmpty() || sources.any { source -> source.extensions.any { it.updateState.isInFlight() } }

private fun isSafeTechnicalCode(value: String): Boolean =
    value.length <= 80 && value.matches(Regex("[A-Za-z0-9_.:-]+"))

internal fun safeDiagnosticEntries(values: Map<String, String>): List<Pair<String, String>> = values.entries
    .asSequence()
    .filterNot { entry ->
        val name = entry.key.lowercase(java.util.Locale.ROOT)
        listOf("token", "secret", "password", "authorization", "private key", "credential", "cookie", "api key")
            .any(name::contains)
    }
    .map { it.key to redactDiagnosticSecrets(it.value.take(512)) }
    .toList()

private fun redactDiagnosticSecrets(value: String): String = value.replace(
    Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"),
) { "${it.groupValues[1]}[redacted]" }.replace(
    Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"),
) { "${it.groupValues[1]}[redacted]@" }.replace(
    Regex("(?i)((?:access[_ -]?token|refresh[_ -]?token|api[_ -]?key|secret|password|authorization|private[_ -]?key|cookie)\\s*[:=]\\s*)[^\\n]+"),
) { "${it.groupValues[1]}[redacted]" }

@Composable
private fun DiagnosticRow(label: String, value: String?) {
    Text(label + ": " + value?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty {
        stringResource(R.string.extension_no_measurement)
    })
}

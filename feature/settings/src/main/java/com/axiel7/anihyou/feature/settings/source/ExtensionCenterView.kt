package com.axiel7.anihyou.feature.settings.source

import android.content.ContextWrapper
import android.content.Context
import android.app.Activity
import com.axiel7.anihyou.release.core.api.NotificationListStatus
import com.axiel7.anihyou.release.core.api.NotificationScopeMode
import com.axiel7.anihyou.release.core.api.ReleaseNotificationScope
import kotlinx.collections.immutable.toImmutableList
import androidx.annotation.DrawableRes
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
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
import androidx.compose.material3.Surface
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
import com.axiel7.anihyou.core.ui.composables.ListPreference
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
    MATCHING("matching", R.string.extension_center_matching, CoreR.drawable.link_24),
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
    if (pageId == "notification-anime") {
        com.axiel7.anihyou.feature.settings.source.notifications.NotificationAnimeView()
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
        if (page == ExtensionCenterPage.DIAGNOSTICS) model.refreshDiagnostics()
    }
    var query by rememberSaveable { mutableStateOf("") }
    val pinned: (@Composable () -> Unit)? = if (page == ExtensionCenterPage.MANAGE) {
        { ExtensionSearchField(query) { query = it } }
    } else null
    ExtensionCenterScaffold(stringResource(page.title), pinned) {
        if (!state.trustAvailable) ExtensionTrustUnavailableNotice()
        if (page == ExtensionCenterPage.DIAGNOSTICS) {
            Spacer(Modifier.height(8.dp))
            PlainPreference(title = stringResource(R.string.extension_details_refresh), icon = CoreR.drawable.refresh_24,
                shape = preferenceShape(0, 2), modifier = Modifier.testTag("extension-details-refresh"),
                onClick = model::refreshDiagnostics)
            PlainPreference(title = stringResource(R.string.extension_releases_refresh_now), icon = CoreR.drawable.refresh_24,
                shape = preferenceShape(1, 2), modifier = Modifier.testTag("extension-releases-refresh-now"),
                onClick = model::refreshReleasesNow)
        }
        when (page) {
            ExtensionCenterPage.MATCHING -> Unit // Handled by the settings-only route above.
            ExtensionCenterPage.MANAGE -> ExtensionSourcesSettingsSection(state, model, pinnedQuery = query)
            ExtensionCenterPage.SOURCE -> ExtensionDataSourcePreferences(state, model)
            ExtensionCenterPage.PROVIDERS -> ExtensionProviderDisplay(state, model)
            ExtensionCenterPage.STATISTICS -> ExtensionStatistics(state, model::refreshReleasesNow)
            ExtensionCenterPage.DIAGNOSTICS -> ExtensionDiagnostics(state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionCenterScaffold(title: String, pinned: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val nav = LocalNavActionManager.current
    DefaultScaffoldWithSmallTopAppBar(title = title,
        navigationIcon = { BackIconButton(nav::goBack) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()) { padding ->
        Column(Modifier.padding(padding)) {
            pinned?.invoke()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
        }
    }
}

internal fun installedEntries(state: ExtensionSourcesUiState): List<Pair<ExtensionSelectionKey, SourceExtension>> =
    state.sources.filter { it.enabled }.flatMap { source -> source.extensions.mapNotNull { extension ->
        source.selectionKey(extension)?.takeIf { extension.isUsableInstalled() }?.let { it to extension }
    } }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ExtensionDataSourcePreferences(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    val nav = LocalNavActionManager.current
    val context = LocalContext.current
    // The switch asks for the permission first and only turns on once it is granted; without it every notification would
    // be dropped silently, which is how release notifications never appeared.
    // (Previews and tests that compose this without an activity have no one to ask: there the switch just switches.)
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context.hasActivity()) {
        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) { granted ->
            if (granted) event.setReleaseNotificationsEnabled(true)
        }
    } else null
    var systemNotificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var ignoringBatteryOptimizations by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemNotificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
    }
    PreferencesTitle(stringResource(R.string.extension_center_management_section))
    PlainPreference(title = stringResource(R.string.extension_center_schedule), icon = CoreR.drawable.schedule_24,
        shape = singleShape, modifier = Modifier.testTag("extension-source-schedule"),
        onClick = { nav.navigate(Route.ExtensionCenterPage("schedule")) })
    state.releaseNotificationsEnabled?.let { enabled ->
        PreferencesTitle(text = stringResource(CoreR.string.notifications))
        SwitchPreference(
            title = stringResource(CoreR.string.release_notifications_enabled),
            subtitle = stringResource(CoreR.string.release_notifications_enabled_summary),
            preferenceValue = enabled,
            icon = CoreR.drawable.notifications_24,
            shape = singleShape,
            modifier = Modifier.testTag("extension-preference-release-notifications"),
            onValueChange = { enable ->
                if (enable && notificationPermission != null && !notificationPermission.status.isGranted) {
                    notificationPermission.launchPermissionRequest()
                } else {
                    event.setReleaseNotificationsEnabled(enable)
                }
            },
        )
        if (enabled) state.notificationScope?.let { scope -> NotificationScopePreferences(scope, event) }
        if (enabled && !systemNotificationsOn) {
            // Switched on here, but Android does not let the app post: say so instead of staying silent.
            PlainPreference(
                title = stringResource(R.string.extension_notifications_off_title),
                subtitle = stringResource(R.string.extension_notifications_off_summary),
                icon = CoreR.drawable.info_24,
                shape = singleShape,
                modifier = Modifier.testTag("extension-notifications-system-off"),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
        if (enabled && !ignoringBatteryOptimizations) {
            // Optional. The hourly checks run without it; the system may only batch them while the phone rests.
            PlainPreference(
                title = stringResource(R.string.extension_background_title),
                subtitle = stringResource(R.string.extension_background_summary),
                icon = CoreR.drawable.schedule_24,
                shape = singleShape,
                modifier = Modifier.testTag("extension-background-optimization"),
                onClick = {
                    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Some devices do not offer the direct dialog; the list of all apps does the same.
                    runCatching { context.startActivity(request) }.onFailure {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                },
            )
        }
    }
    if (state.legacyLaneEnabled) {
        // Left over from earlier builds: it runs beside the sources and decides Behind and countdowns. Off only.
        PreferencesTitle(text = stringResource(R.string.extension_legacy_lane_title))
        SwitchPreference(
            title = stringResource(R.string.extension_legacy_lane_switch),
            subtitle = stringResource(R.string.extension_legacy_lane_summary),
            preferenceValue = true,
            icon = CoreR.drawable.info_24,
            shape = singleShape,
            modifier = Modifier.testTag("extension-legacy-lane"),
            onValueChange = { if (!it) event.disableLegacyLane() },
        )
    }
    val entries = installedEntries(state)
    val canEdit = state.canEditProductPolicy && !state.hasSourceOperationInFlight()
    val releaseEntries = entries.filter { (_, e) -> e.capabilities.any { cap ->
        com.axiel7.anihyou.release.core.extension.SourceRole.entries.any { it.name == cap }
    } }
    val active = state.productPolicy.activeReleaseSource
    PreferencesTitle(text = stringResource(R.string.extension_sources_data_source_heading))
    SelectionOption(stringResource(R.string.extension_sources_no_active_source),
        active !in releaseEntries.map { it.first }, canEdit,
        { event.selectActiveSource(null) }, "extension-product-active-none", preferenceShape(0, releaseEntries.size + 1))
    releaseEntries.forEachIndexed { index, (key, extension) ->
        SelectionOption(extension.displayName, active == key, canEdit,
            { event.selectActiveSource(key) }, "extension-product-active-" + key.testTagPart(), preferenceShape(index + 1, releaseEntries.size + 1))
    }
    entries.forEach { (key, extension) ->
        ExtensionTrackPreferences(key, extension,
            state.productPolicy.preferences[key] ?: ExtensionPreferences().withGenericDefaults(extension),
            canEdit, canShowInProviderField = false, event = event, heading = extension.displayName)
    }
}

@Composable
fun ExtensionProviderDisplay(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    ProviderDisplayEditor(state, event)
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
        PreferencesTitle(extension.displayName)
        val identityKeys = listOf("Extension ID", "Provider ID", "Signed displayName", "Repository", "Publisher",
            "Key ID", "Trust status", "Package SHA", "WASM SHA", "Active selection generation",
            "Last update at", "Capabilities", "Allowed Hosts", "Role health", "Role reports",
            "Last committed sync", "Last sync outcome", "Runtime", "Last parse status", "Last navigation status", "Fuel limit",
            "Memory limit", "Deadline limit", "Cancellation")
        DiagnosticRows(identityKeys.map { it to safeValues[it] })
        PreferencesTitle(stringResource(R.string.extension_manage_diagnostic_package_status))
        val statusRows = (if (source.manuallyTrusted) listOf(stringResource(R.string.extension_manage_diagnostic_trust_class) to
            stringResource(R.string.extension_manage_manual_trust)) else emptyList()) + listOf(
            stringResource(R.string.extension_manage_diagnostic_installed_version) to (safeValues["Current Version"]?.takeIf { it.isNotBlank() } ?: extension.installedVersion),
            stringResource(R.string.extension_manage_diagnostic_latest_version) to (safeValues["Latest Available"]?.takeIf { it.isNotBlank() } ?: extension.latestAvailableVersion),
            stringResource(R.string.extension_manage_diagnostic_release_sequence) to (safeValues["Release Sequence"]?.takeIf { it.isNotBlank() } ?: extension.installedReleaseSequence?.toString()),
            stringResource(R.string.extension_manage_diagnostic_update_state) to (stringResource(extensionUpdateStateLabel(extension.updateState))),
            stringResource(R.string.extension_manage_diagnostic_package_status) to (stringResource(installedPackageStatusLabel(extension))),
            stringResource(R.string.extension_manage_diagnostic_package_generation) to (extension.packageGeneration.toString()),
            stringResource(R.string.extension_manage_diagnostic_known_good) to (safeValues["Known Good"]?.takeIf { it.isNotBlank() } ?: extension.installedVersion.takeIf { extension.installedUsable }),
            stringResource(R.string.extension_manage_diagnostic_previous_good) to (extension.rollbackTarget?.let { "${it.version} · ${it.trustState} · ${it.digest.take(12)}" }),
            stringResource(R.string.extension_manage_diagnostic_last_update) to (safeValues["Last Update Check"]?.takeIf { it.isNotBlank() } ?: source.lastAttemptAt?.toString()),
            stringResource(R.string.extension_manage_diagnostic_update_result) to (safeValues["Last Update Result"]?.takeIf { it.isNotBlank() } ?: extension.lastUpdateResult),
            stringResource(R.string.extension_manage_diagnostic_update_failure) to (extension.lastUpdateFailure?.let { stringResource(updateFailureLabel(it)) }),
            stringResource(R.string.extension_manage_diagnostic_failure_code) to (safeValues["Last Update Failure Code"]?.takeIf(::isSafeTechnicalCode)),
            stringResource(R.string.extension_manage_diagnostic_last_successful_update) to (safeValues["Last Successful Update"]),
            stringResource(R.string.extension_manage_diagnostic_rollback_available) to (stringResource(if (extension.rollbackTarget != null) R.string.extension_manage_diagnostic_yes
                else R.string.extension_manage_diagnostic_no)),
            stringResource(R.string.extension_manage_diagnostic_revocation) to (stringResource(if (extension.revoked || extension.installedStatus == InstalledPackageStatus.REVOKED)
                R.string.extension_manage_trust_revoked else R.string.extension_manage_diagnostic_no)),
            stringResource(R.string.extension_manage_diagnostic_metadata) to (stringResource(if (extension.metadataFresh) R.string.extension_manage_diagnostic_fresh
                else R.string.extension_manage_diagnostic_stale)),
            stringResource(R.string.extension_manage_diagnostic_last_metadata_success) to (safeValues["Last metadata success"]?.takeIf { it.isNotBlank() }),
            stringResource(R.string.extension_manage_diagnostic_yanked) to (stringResource(if (extension.candidateYanked) R.string.extension_manage_diagnostic_yes
                else R.string.extension_manage_diagnostic_no))
        )
        DiagnosticRows(statusRows)
        val packageKeys = setOf("Version", "Latest authenticated version", "Installed package status", "Update state",
            "Package generation", "Metadata fresh", "Candidate yanked", "Last update result", "Last update failure",
            "Previous Good", "Current Version", "Latest Available", "Release Sequence", "Active package generation",
            "Known Good", "Previous Good Version", "Last Update Check", "Last Update Result", "Last Update Failure",
            "Last Update Failure Code", "Last Successful Update", "Rollback Available", "Revocation",
            "Repository metadata freshness", "Last metadata success", "Yanked candidate")
        val otherRows = safeValues.filterKeys { it !in identityKeys && it !in packageKeys }.toSortedMap()
        if (otherRows.isNotEmpty()) {
            PreferencesTitle(stringResource(R.string.extension_diagnostics_processing))
            DiagnosticRows(otherRows.map { it.key to it.value })
        }
        Spacer(Modifier.height(8.dp))
        PlainPreference(title = stringResource(R.string.extension_diagnostics_copy), icon = CoreR.drawable.content_copy_24,
            shape = singleShape, modifier = Modifier.testTag("diagnostics-copy-" + key.testTagPart()),
            onClick = { clipboard.setText(AnnotatedString(
                safeDiagnosticEntries(values).joinToString("\n") { (name, value) -> "$name: $value" })) })
        Spacer(Modifier.height(20.dp))
    }
}

internal fun ExtensionSourcesUiState.hasSourceOperationInFlight(): Boolean =
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
private fun DiagnosticRows(rows: List<Pair<String, String?>>) {
    rows.forEachIndexed { index, (label, value) ->
        Surface(shape = preferenceShape(index, rows.size), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
            Text(label + ": " + value?.takeIf { it.isNotBlank() }.orEmpty().ifEmpty {
                stringResource(R.string.extension_no_measurement)
            }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true


/** What is announced: the anime being watched (default), everything, or the exact statuses the user picks. */
@Composable
private fun NotificationScopePreferences(scope: ReleaseNotificationScope, event: ExtensionSourcesEvent) {
    PreferencesTitle(text = stringResource(R.string.notification_scope_title))
    val customRows = if (scope.mode == NotificationScopeMode.CUSTOM) NotificationListStatus.entries.size + 1 else 0
    val hiddenRow = if (scope.mode == NotificationScopeMode.ALL) 0 else 1
    // The mode, its status rows, the hidden switch, the single-anime page and the AniList switch form one group.
    val count = 1 + customRows + hiddenRow + 3
    val nav = LocalNavActionManager.current
    var index = 0
    ListPreference(
        title = stringResource(R.string.notification_scope_mode),
        icon = CoreR.drawable.filter_list_24,
        values = NotificationScopeMode.entries.toImmutableList(),
        labelForValue = { stringResource(scopeModeLabel(it)) },
        preferenceValue = scope.mode,
        onValueChange = { mode -> event.updateNotificationScope { it.copy(mode = mode) } },
        shape = preferenceShape(index++, count),
        modifier = Modifier.testTag("extension-notification-scope-mode"),
    )
    if (scope.mode == NotificationScopeMode.CUSTOM) {
        NotificationListStatus.entries.forEach { status ->
            SwitchPreference(
                title = stringResource(statusLabel(status)),
                preferenceValue = status in scope.statuses,
                icon = statusIcon(status),
                shape = preferenceShape(index++, count),
                modifier = Modifier.testTag("extension-notification-scope-" + status.name.lowercase()),
                onValueChange = { on ->
                    event.updateNotificationScope { it.copy(statuses = if (on) it.statuses + status else it.statuses - status) }
                },
            )
        }
        SwitchPreference(
            title = stringResource(R.string.notification_scope_not_on_list),
            subtitle = stringResource(R.string.notification_scope_not_on_list_summary),
            preferenceValue = scope.includeNotOnList,
            icon = CoreR.drawable.playlist_add_24,
            shape = preferenceShape(index++, count),
            modifier = Modifier.testTag("extension-notification-scope-not-on-list"),
            onValueChange = { on -> event.updateNotificationScope { it.copy(includeNotOnList = on) } },
        )
    }
    if (hiddenRow == 1) {
        SwitchPreference(
            title = stringResource(R.string.notification_scope_hidden),
            subtitle = stringResource(R.string.notification_scope_hidden_summary),
            preferenceValue = scope.includeHidden,
            icon = CoreR.drawable.visibility_off_24,
            shape = preferenceShape(index++, count),
            modifier = Modifier.testTag("extension-notification-scope-hidden"),
            onValueChange = { on -> event.updateNotificationScope { it.copy(includeHidden = on) } },
        )
    }
    PlainPreference(
        title = stringResource(R.string.notification_anime_title),
        subtitle = stringResource(
            R.string.notification_anime_summary, scope.overrides.count { it.value }, scope.overrides.count { !it.value }),
        icon = CoreR.drawable.list_alt_24,
        shape = preferenceShape(index++, count),
        modifier = Modifier.testTag("extension-notification-anime"),
        onClick = { nav.navigate(Route.ExtensionCenterPage("notification-anime")) },
    )
    SwitchPreference(
        title = stringResource(R.string.notification_anilist_airing),
        subtitle = stringResource(R.string.notification_anilist_airing_summary),
        preferenceValue = scope.alsoAniListAiring,
        icon = CoreR.drawable.notifications_24,
        shape = preferenceShape(index++, count),
        modifier = Modifier.testTag("extension-notification-anilist-airing"),
        onValueChange = { on -> event.updateNotificationScope { it.copy(alsoAniListAiring = on) } },
    )
    SwitchPreference(
        title = stringResource(R.string.notification_calendar_time),
        subtitle = stringResource(R.string.notification_calendar_time_summary),
        preferenceValue = scope.announceByCalendar,
        icon = CoreR.drawable.calendar_month_24,
        shape = preferenceShape(index, count),
        modifier = Modifier.testTag("extension-notification-calendar-time"),
        onValueChange = { on -> event.updateNotificationScope { it.copy(announceByCalendar = on) } },
    )
    Text(
        text = stringResource(when (scope.mode) {
            NotificationScopeMode.WATCHING -> R.string.notification_scope_watching_hint
            NotificationScopeMode.ALL -> R.string.notification_scope_all_hint
            NotificationScopeMode.CUSTOM -> R.string.notification_scope_custom_hint
        }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

private fun scopeModeLabel(mode: NotificationScopeMode) = when (mode) {
    NotificationScopeMode.WATCHING -> R.string.notification_scope_mode_watching
    NotificationScopeMode.ALL -> R.string.notification_scope_mode_all
    NotificationScopeMode.CUSTOM -> R.string.notification_scope_mode_custom
}

private fun statusLabel(status: NotificationListStatus) = when (status) {
    NotificationListStatus.CURRENT -> R.string.notification_status_current
    NotificationListStatus.REPEATING -> R.string.notification_status_repeating
    NotificationListStatus.PLANNING -> R.string.notification_status_planning
    NotificationListStatus.PAUSED -> R.string.notification_status_paused
    NotificationListStatus.COMPLETED -> R.string.notification_status_completed
    NotificationListStatus.DROPPED -> R.string.notification_status_dropped
}

private fun statusIcon(status: NotificationListStatus) = when (status) {
    NotificationListStatus.CURRENT -> CoreR.drawable.play_circle_24
    NotificationListStatus.REPEATING -> CoreR.drawable.refresh_24
    NotificationListStatus.PLANNING -> CoreR.drawable.bookmark_24
    NotificationListStatus.PAUSED -> CoreR.drawable.pause_circle_24
    NotificationListStatus.COMPLETED -> CoreR.drawable.check_circle_24
    NotificationListStatus.DROPPED -> CoreR.drawable.cancel_24
}


private fun Context.hasActivity(): Boolean {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return true
        current = current.baseContext
    }
    return false
}

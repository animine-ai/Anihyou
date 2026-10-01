package com.axiel7.anihyou.feature.settings.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import com.axiel7.anihyou.release.core.source.selectionKey

@Composable
fun ExtensionSourcesSettingsSection(
    uiState: ExtensionSourcesUiState,
    event: ExtensionSourcesEvent,
) {
    PreferencesTitle(text = stringResource(R.string.extension_sources_title))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.extension_sources_auth_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.url,
            onValueChange = event::onUrlChanged,
            modifier = Modifier.fillMaxWidth().testTag("extension-source-url"),
            label = { Text(stringResource(R.string.extension_sources_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { event.addSource() }),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = event::addSource,
            modifier = Modifier.testTag("extension-source-add"),
            enabled = uiState.url.isNotBlank() && !uiState.isAdding,
        ) {
            Text(stringResource(if (uiState.isAdding) R.string.extension_sources_adding else R.string.extension_sources_add))
        }

        uiState.addResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when (result) {
                        is AddExtensionSourceResult.Added -> R.string.extension_sources_added
                        is AddExtensionSourceResult.Duplicate -> R.string.extension_sources_duplicate
                        AddExtensionSourceResult.InvalidUrl -> R.string.extension_sources_invalid_url
                        AddExtensionSourceResult.LimitReached -> R.string.extension_sources_limit_reached
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (uiState.actionFailed) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = event::clearActionFailure) {
                Text(stringResource(R.string.extension_sources_action_failed))
            }
        }

        if (uiState.sources.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.extension_sources_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Spacer(Modifier.height(16.dp))
            PreferencesTitle(text = stringResource(R.string.extension_sources_product_policy_title))
            Text(
                text = stringResource(R.string.extension_sources_single_active_explanation),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            val activeKey = uiState.productPolicy.activeReleaseSource
            val activeKeyIsUsable = activeKey != null && uiState.sources.any { source ->
                source.enabled && source.extensions.any { extension ->
                    source.selectionKey(extension) == activeKey && extension.isUsableInstalled()
                }
            }
            SelectionOption(
                label = stringResource(R.string.extension_sources_no_active_source),
                selected = !activeKeyIsUsable,
                enabled = uiState.canEditProductPolicy,
                onClick = { event.selectActiveSource(null) },
                testTag = "extension-product-active-none",
            )
            val navigationKeys = uiState.sources.flatMap { source ->
                source.extensions.mapNotNull { extension ->
                    source.selectionKey(extension)?.takeIf {
                        source.enabled && extension.isUsableInstalled() && extension.supportsNavigation()
                    }
                }
            }.toSet()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.extension_sources_preferred_navigation_provider),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.extension_sources_navigation_provider_explanation),
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionOption(
                label = stringResource(R.string.extension_sources_no_preferred_navigation_provider),
                selected = uiState.productPolicy.preferredNavigationProvider !in navigationKeys,
                enabled = uiState.canEditProductPolicy,
                onClick = { event.selectNavigationProvider(null) },
                testTag = "extension-product-navigation-none",
            )
            Spacer(Modifier.height(8.dp))
            uiState.sources.forEach { source ->
                ExtensionSourceCard(
                    source = source,
                    policy = uiState.productPolicy,
                    canEditPolicy = uiState.canEditProductPolicy,
                    event = event,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ExtensionSourceCard(
    source: ExtensionSource,
    policy: ExtensionProductPolicy,
    canEditPolicy: Boolean,
    event: ExtensionSourcesEvent,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag("extension-source-card")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(source.url, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.extension_sources_status,
                    stringResource(sourceStatusString(source.status)),
                ),
                modifier = Modifier.testTag("extension-source-status"),
                style = MaterialTheme.typography.bodyMedium,
            )
            source.lastFailure?.let { failure ->
                Text(
                    text = stringResource(
                        R.string.extension_sources_last_failure,
                        stringResource(sourceFailureString(failure)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.extension_sources_enabled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { event.setEnabled(source.id, it) },
                    modifier = Modifier.testTag("extension-source-enabled"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { event.refreshSource(source.id) },
                    modifier = Modifier.testTag("extension-source-refresh"),
                ) {
                    Text(stringResource(R.string.extension_sources_refresh))
                }
                TextButton(
                    onClick = { event.removeSource(source.id) },
                    modifier = Modifier.testTag("extension-source-remove"),
                ) {
                    Text(stringResource(R.string.extension_sources_remove))
                }
            }
            source.extensions.forEach { extension ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SourceExtensionInfo(
                    source = source,
                    sourceEnabled = source.enabled,
                    extension = extension,
                    policy = policy,
                    canEditPolicy = canEditPolicy,
                    event = event,
                )
            }
        }
    }
}

@Composable
private fun SourceExtensionInfo(
    source: ExtensionSource,
    sourceEnabled: Boolean,
    extension: SourceExtension,
    policy: ExtensionProductPolicy,
    canEditPolicy: Boolean,
    event: ExtensionSourcesEvent,
) {
    Text(extension.displayName, style = MaterialTheme.typography.titleSmall)
    Text(
        text = stringResource(R.string.extension_sources_extension_id, extension.extensionId),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.extension_sources_version, extension.version),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(
            R.string.extension_sources_capabilities,
            extension.capabilities.joinToString().ifBlank {
                stringResource(R.string.extension_sources_no_capabilities)
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    val installedVersion = extension.installedVersion
    if (installedVersion != null) {
        Text(
            text = stringResource(R.string.extension_sources_installed, installedVersion),
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            text = stringResource(R.string.extension_sources_not_installed),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = stringResource(
            if (extension.updateAvailable) {
                R.string.extension_sources_update_available
            } else {
                R.string.extension_sources_no_update
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (extension.revoked) {
        Text(
            text = stringResource(R.string.extension_sources_revoked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(
            text = stringResource(R.string.extension_sources_not_revoked),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (extension.activationAllowed && sourceEnabled) {
        TextButton(onClick = { event.activate(source.id, extension.extensionId) }) {
            Text(stringResource(R.string.extension_sources_activate))
        }
    } else {
        Text(
            text = stringResource(R.string.extension_sources_activation_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val key = source.selectionKey(extension)
    if (key != null && extension.isUsableInstalled()) {
        val preferences = policy.preferences[key]
            ?: ExtensionPreferences().withGenericDefaults(extension)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.extension_sources_data_source_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        SelectionOption(
            label = stringResource(R.string.extension_sources_use_active_source),
            selected = sourceEnabled && policy.activeReleaseSource == key,
            enabled = canEditPolicy && sourceEnabled,
            onClick = { event.selectActiveSource(key) },
            testTag = "extension-product-active-${key.testTagPart()}",
        )
        if (extension.supportsNavigation()) {
            Text(
                text = stringResource(R.string.extension_sources_navigation_provider_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            SelectionOption(
                label = stringResource(R.string.extension_sources_prefer_navigation_provider),
                selected = sourceEnabled && policy.preferredNavigationProvider == key,
                enabled = canEditPolicy && sourceEnabled,
                onClick = { event.selectNavigationProvider(key) },
                testTag = "extension-product-navigation-${key.testTagPart()}",
            )
        }
        ExtensionTrackPreferences(
            key = key,
            extension = extension,
            preferences = preferences,
            enabled = canEditPolicy && sourceEnabled,
            canShowInProviderField = extension.capabilities.contains("OVERVIEW_NAVIGATION"),
            event = event,
        )
    }
}

@Composable
private fun ExtensionTrackPreferences(
    key: ExtensionSelectionKey,
    extension: SourceExtension,
    preferences: ExtensionPreferences,
    enabled: Boolean,
    canShowInProviderField: Boolean,
    event: ExtensionSourcesEvent,
) {
    val tracksByKind = extension.supportedTracks
        .filter(::isPreferenceTrack)
        .groupBy(::trackKind)
    val subTracks = tracksByKind[TrackKind.SUB].orEmpty()
    val dubTracks = tracksByKind[TrackKind.DUB].orEmpty()
    val languages = (subTracks + dubTracks)
        .map { it.substringBeforeLast('_').lowercase() }
        .distinct()
        .sorted()

    if (subTracks.isNotEmpty() || dubTracks.isNotEmpty()) {
        Text(
            text = stringResource(R.string.extension_sources_track_settings),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subTracks.isNotEmpty()) {
            TrackSwitch(
                label = stringResource(R.string.extension_sources_sub_enabled),
                checked = subTracks.all { it in preferences.enabledTracks },
                enabled = enabled,
                testTag = "extension-preference-sub-${key.testTagPart()}",
            ) { checked ->
                event.setPreferences(key, preferences.withTrackGroup(subTracks, checked))
            }
        }
        if (dubTracks.isNotEmpty()) {
            TrackSwitch(
                label = stringResource(R.string.extension_sources_dub_enabled),
                checked = dubTracks.all { it in preferences.enabledTracks },
                enabled = enabled,
                testTag = "extension-preference-dub-${key.testTagPart()}",
            ) { checked ->
                event.setPreferences(key, preferences.withTrackGroup(dubTracks, checked))
            }
        }
        if (subTracks.isNotEmpty() && dubTracks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.extension_sources_track_priority),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            TrackPriorityOption(
                label = stringResource(R.string.extension_sources_sub_first),
                selected = preferences.priorityChoice(subTracks, dubTracks) == TrackPriority.SUB_FIRST,
                enabled = enabled,
                testTag = "extension-preference-priority-sub-first-${key.testTagPart()}",
            ) {
                event.setPreferences(key, preferences.withPriority(subTracks, dubTracks, TrackPriority.SUB_FIRST))
            }
            TrackPriorityOption(
                label = stringResource(R.string.extension_sources_dub_first),
                selected = preferences.priorityChoice(subTracks, dubTracks) == TrackPriority.DUB_FIRST,
                enabled = enabled,
                testTag = "extension-preference-priority-dub-first-${key.testTagPart()}",
            ) {
                event.setPreferences(key, preferences.withPriority(subTracks, dubTracks, TrackPriority.DUB_FIRST))
            }
            TrackPriorityOption(
                label = stringResource(R.string.extension_sources_sub_only),
                selected = preferences.priorityChoice(subTracks, dubTracks) == TrackPriority.SUB_ONLY,
                enabled = enabled,
                testTag = "extension-preference-priority-sub-only-${key.testTagPart()}",
            ) {
                event.setPreferences(key, preferences.withPriority(subTracks, dubTracks, TrackPriority.SUB_ONLY))
            }
            TrackPriorityOption(
                label = stringResource(R.string.extension_sources_dub_only),
                selected = preferences.priorityChoice(subTracks, dubTracks) == TrackPriority.DUB_ONLY,
                enabled = enabled,
                testTag = "extension-preference-priority-dub-only-${key.testTagPart()}",
            ) {
                event.setPreferences(key, preferences.withPriority(subTracks, dubTracks, TrackPriority.DUB_ONLY))
            }
        }
        if (languages.size > 1) {
            Text(
                text = stringResource(R.string.extension_sources_language_priority),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            languages.forEach { language ->
                SelectionOption(
                    label = stringResource(R.string.extension_sources_prefer_language, language.uppercase()),
                    selected = preferences.languageOrder.firstOrNull { it in languages } == language,
                    enabled = enabled,
                    onClick = {
                        val reorderedLanguages = listOf(language) +
                            preferences.languageOrder.filter { it != language } +
                            languages.filter { it != language && it !in preferences.languageOrder }
                        event.setPreferences(key, preferences.copy(languageOrder = reorderedLanguages.distinct()))
                    },
                    testTag = "extension-preference-language-$language-${key.testTagPart()}",
                )
            }
        }
    }

    if (canShowInProviderField) {
        TrackSwitch(
            label = stringResource(R.string.extension_sources_visible_in_provider_field),
            checked = preferences.visibleInProviderField,
            enabled = enabled,
            testTag = "extension-preference-provider-visible-${key.testTagPart()}",
        ) { checked ->
            event.setPreferences(key, preferences.copy(visibleInProviderField = checked))
        }
    }
}

@Composable
private fun SelectionOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag(testTag)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TrackPriorityOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    SelectionOption(
        label = label,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        testTag = testTag,
    )
}

@Composable
private fun TrackSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        )
    }
}

private enum class TrackKind { SUB, DUB, OTHER }
private enum class TrackPriority { SUB_FIRST, DUB_FIRST, SUB_ONLY, DUB_ONLY, NONE }

private fun trackKind(track: String): TrackKind = when {
    track.endsWith("_SUB", ignoreCase = true) -> TrackKind.SUB
    track.endsWith("_DUB", ignoreCase = true) -> TrackKind.DUB
    else -> TrackKind.OTHER
}

private fun isPreferenceTrack(track: String): Boolean =
    track != "UNKNOWN" && track.matches(Regex("[A-Za-z0-9_-]+")) &&
        trackKind(track) != TrackKind.OTHER && track.substringBeforeLast('_').isNotBlank()

private fun SourceExtension.isUsableInstalled(): Boolean =
    installedDigest != null && activationAllowed && !revoked

private fun SourceExtension.supportsNavigation(): Boolean =
    capabilities.any { it == "OVERVIEW_NAVIGATION" || it == "EPISODE_NAVIGATION" }

private fun ExtensionSelectionKey.testTagPart(): String =
    listOf(sourceId, extensionId, publisherId, providerId).joinToString("-")

private fun ExtensionPreferences.withGenericDefaults(extension: SourceExtension): ExtensionPreferences {
    val supported = extension.supportedTracks.filter(::isPreferenceTrack)
    val defaultEnabled = supported.toSet()
    val preferred = supported.sortedWith(
        compareBy<String> { if (trackKind(it) == TrackKind.SUB) 0 else 1 }
            .thenBy { it.substringBeforeLast('_') },
    )
    return copy(
        enabledTracks = defaultEnabled,
        preferredTrackOrder = preferred,
        languageOrder = (listOf("de") + supported.map { it.substringBeforeLast('_').lowercase() }).distinct(),
    )
}

private fun ExtensionPreferences.withTrackGroup(tracks: List<String>, enabled: Boolean): ExtensionPreferences {
    val updatedEnabled = if (enabled) enabledTracks + tracks else enabledTracks - tracks.toSet()
    val updatedOrder = (preferredTrackOrder + tracks).distinct()
    return copy(enabledTracks = updatedEnabled, preferredTrackOrder = updatedOrder)
}

private fun ExtensionPreferences.withPriority(
    subTracks: List<String>,
    dubTracks: List<String>,
    priority: TrackPriority,
): ExtensionPreferences {
    val supportedGroups = subTracks.toSet() + dubTracks.toSet()
    val orderedTracks = when (priority) {
        TrackPriority.SUB_FIRST -> subTracks + dubTracks
        TrackPriority.DUB_FIRST -> dubTracks + subTracks
        TrackPriority.SUB_ONLY -> subTracks
        TrackPriority.DUB_ONLY -> dubTracks
        TrackPriority.NONE -> preferredTrackOrder
    }
    val enabled = when (priority) {
        TrackPriority.SUB_FIRST, TrackPriority.DUB_FIRST -> enabledTracks + supportedGroups
        TrackPriority.SUB_ONLY -> (enabledTracks - dubTracks.toSet()) + subTracks
        TrackPriority.DUB_ONLY -> (enabledTracks - subTracks.toSet()) + dubTracks
        TrackPriority.NONE -> enabledTracks
    }
    val nextOrder = (orderedTracks + preferredTrackOrder.filterNot { it in supportedGroups }).distinct()
    return copy(enabledTracks = enabled, preferredTrackOrder = nextOrder)
}

private fun ExtensionPreferences.priorityChoice(subTracks: List<String>, dubTracks: List<String>): TrackPriority {
    val subEnabled = subTracks.any { it in enabledTracks }
    val dubEnabled = dubTracks.any { it in enabledTracks }
    if (subEnabled && !dubEnabled) return TrackPriority.SUB_ONLY
    if (!subEnabled && dubEnabled) return TrackPriority.DUB_ONLY
    if (!subEnabled && !dubEnabled) return TrackPriority.NONE
    val order = preferredTrackOrder.filter { it in enabledTracks && (it in subTracks || it in dubTracks) }
    val first = order.firstOrNull() ?: return TrackPriority.NONE
    return if (first in subTracks) TrackPriority.SUB_FIRST else TrackPriority.DUB_FIRST
}

private fun sourceStatusString(status: ExtensionSourceStatus): Int = when (status) {
    ExtensionSourceStatus.ADDED -> R.string.extension_sources_status_added
    ExtensionSourceStatus.DISABLED -> R.string.extension_sources_status_disabled
    ExtensionSourceStatus.TRUST_UNAVAILABLE -> R.string.extension_sources_status_trust_unavailable
    ExtensionSourceStatus.CURRENT -> R.string.extension_sources_status_current
    ExtensionSourceStatus.UPDATE_AVAILABLE -> R.string.extension_sources_status_update_available_label
    ExtensionSourceStatus.ERROR -> R.string.extension_sources_status_error
    ExtensionSourceStatus.REVOKED -> R.string.extension_sources_status_revoked
}

private fun sourceFailureString(failure: ExtensionSourceFailure): Int = when (failure) {
    ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE -> R.string.extension_sources_failure_authentication
    ExtensionSourceFailure.NETWORK -> R.string.extension_sources_failure_network
    ExtensionSourceFailure.INVALID_METADATA -> R.string.extension_sources_failure_metadata
    ExtensionSourceFailure.INVALID_PACKAGE -> R.string.extension_sources_failure_package
    ExtensionSourceFailure.UNSUPPORTED_RUNTIME -> R.string.extension_sources_failure_runtime
    ExtensionSourceFailure.STORAGE -> R.string.extension_sources_failure_storage
}

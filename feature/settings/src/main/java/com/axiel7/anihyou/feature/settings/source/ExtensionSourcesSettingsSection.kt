package com.axiel7.anihyou.feature.settings.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.core.ui.composables.singleShape
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.UnverifiedSourcePreview
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.ExtensionUpdateFailure
import com.axiel7.anihyou.release.core.source.ExtensionUpdateState
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
        if (uiState.trustAvailable) AddExtensionSourceForm(uiState, event)
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
            uiState.sources.forEach { source ->
                ExtensionSourceCard(
                    source = source,
                    diagnostics = uiState.diagnostics,
                    busySourceIds = uiState.busySourceIds,
                    trustAvailable = uiState.trustAvailable,
                    event = event,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AddExtensionSourceForm(
    uiState: ExtensionSourcesUiState,
    event: ExtensionSourcesEvent,
) {
    Column {
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
            val message = when (result) {
                is AddExtensionSourceResult.Added -> R.string.extension_sources_added
                is AddExtensionSourceResult.Duplicate -> R.string.extension_sources_duplicate
                AddExtensionSourceResult.InvalidUrl -> R.string.extension_sources_invalid_url
                AddExtensionSourceResult.LimitReached -> R.string.extension_sources_limit_reached
                AddExtensionSourceResult.PreviewFailed -> R.string.extension_sources_preview_failed
                // Shown as the decision dialog, never as a message line.
                is AddExtensionSourceResult.NeedsTrustConfirmation -> null
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        uiState.trustPrompt?.let { preview ->
            UnverifiedSourceDialog(preview, busy = uiState.isAdding,
                onConfirm = event::confirmTrust, onCancel = event::cancelTrust)
        }
    }
}

@Composable
private fun ExtensionSourceCard(
    source: ExtensionSource,
    diagnostics: Map<ExtensionSelectionKey, Map<String, String>>,
    busySourceIds: Set<String>,
    trustAvailable: Boolean,
    event: ExtensionSourcesEvent,
    modifier: Modifier = Modifier,
) {
    val sourceBusy = source.id in busySourceIds || source.extensions.any { it.updateState.isInFlight() }
    var showRemoveConfirmation by remember(source.id) { mutableStateOf(false) }
    val refreshLabel = stringResource(if (sourceBusy) R.string.extension_manage_working else R.string.extension_sources_refresh)
    val refreshModifier = if (sourceBusy) Modifier.semantics {
        liveRegion = LiveRegionMode.Polite
        stateDescription = refreshLabel
    } else Modifier
    // The rounded surfaceContainerHigh tile of the original settings rows and cards.
    Card(
        modifier = modifier.fillMaxWidth().testTag("extension-source-card"),
        shape = singleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(repositoryHost(source.url) ?: stringResource(R.string.extension_manage_unknown_repository),
                style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.extension_manage_repository_summary, source.id, source.origin),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.extension_sources_status,
                    stringResource(sourceStatusString(source.status)),
                ),
                modifier = Modifier.testTag("extension-source-status"),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (source.manuallyTrusted) {
                // Permanent, plain wording: this source was accepted by the user, nobody independently verified it.
                Text(
                    text = stringResource(R.string.extension_manage_manual_trust),
                    modifier = Modifier.testTag("extension-source-manual-trust"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
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
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { event.refreshSource(source.id) },
                    modifier = Modifier.testTag("extension-source-refresh"),
                    enabled = !sourceBusy && trustAvailable,
                ) {
                    Text(refreshLabel, modifier = refreshModifier)
                }
                TextButton(
                    onClick = { showRemoveConfirmation = true },
                    modifier = Modifier.testTag("extension-source-remove"),
                    enabled = !sourceBusy,
                ) {
                    Text(stringResource(R.string.extension_sources_remove))
                }
            }
            source.extensions.forEach { extension ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SourceExtensionInfo(
                    source = source,
                    extension = extension,
                    sourceBusy = sourceBusy,
                    diagnostics = source.selectionKey(extension)?.let { diagnostics[it] }.orEmpty(),
                    event = event,
                )
            }
        }
    }
    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            title = { Text(stringResource(R.string.extension_manage_remove_source_title)) },
            text = { Text(stringResource(R.string.extension_manage_remove_source_message, source.id)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirmation = false
                        event.removeSource(source.id)
                    },
                    enabled = !sourceBusy,
                    modifier = Modifier.testTag("extension-source-remove-confirm"),
                ) { Text(stringResource(R.string.extension_sources_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false },
                    modifier = Modifier.testTag("extension-source-remove-cancel")) {
                    Text(stringResource(R.string.extension_manage_cancel))
                }
            },
        )
    }
}

@Composable
private fun SourceExtensionInfo(
    source: ExtensionSource,
    extension: SourceExtension,
    sourceBusy: Boolean,
    diagnostics: Map<String, String>,
    event: ExtensionSourcesEvent,
) {
    var dialog by remember(source.id, extension.extensionId) { mutableStateOf<ExtensionManageDialog?>(null) }
    // A confirmation belongs to this exact lifecycle snapshot. Any replacement or trust
    // change dismisses it instead of leaving an obsolete action on screen.
    var rollbackRequest by remember(source.id, extension.extensionId, extension.packageGeneration,
        extension.installedDigest, extension.rollbackTarget) { mutableStateOf<RollbackRequest?>(null) }
    val actionBusy = sourceBusy || extension.updateState.isInFlight()
    val updateStateLabel = stringResource(extensionUpdateStateLabel(extension.updateState))
    val updateStatusText = stringResource(R.string.extension_manage_status, updateStateLabel)
    val installedStatusLabel = stringResource(installedPackageStatusLabel(extension))
    val latestVersion = extension.latestAvailableVersion
    val unavailableLabel = stringResource(R.string.extension_manage_unavailable)
    val lastUpdateFailure = extension.lastUpdateFailure

    Text(stringResource(R.string.extension_manage_signed_name, extension.displayName),
        style = MaterialTheme.typography.titleSmall)
    Text(
        text = stringResource(R.string.extension_manage_signed_id, extension.extensionId),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.extension_manage_installed_version,
            extension.installedVersion ?: stringResource(R.string.extension_manage_not_installed)),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("extension-installed-version-${extension.extensionId}"),
    )
    Text(
        text = stringResource(R.string.extension_manage_latest_authenticated_version,
            latestVersion ?: stringResource(R.string.extension_manage_unavailable)),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("extension-latest-version-${extension.extensionId}"),
    )
    Text(
        text = stringResource(R.string.extension_manage_package_trust, installedStatusLabel),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("extension-installed-status-${extension.extensionId}"),
    )
    val digest = extension.installedDigest
    if (digest != null) {
        Text(
            text = stringResource(R.string.extension_manage_package_summary,
                extension.packageGeneration, digest.take(12)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val repoTrust = diagnostics["Trust status"]
    if (repoTrust != null) {
        Text(
            text = stringResource(R.string.extension_manage_authenticated_trust, repoTrust),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (lastUpdateFailure != null) {
        Text(
            text = stringResource(R.string.extension_manage_update_failure,
                stringResource(updateFailureLabel(lastUpdateFailure))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("extension-update-failure-${extension.extensionId}"),
        )
    }
    Text(
        text = updateStatusText,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .testTag("extension-update-state-${extension.extensionId}")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = updateStateLabel
            },
    )
    if (actionBusy) {
        val progressLabel = stringResource(R.string.extension_manage_working)
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("extension-progress-${extension.extensionId}").semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = progressLabel
            }) {
            CircularProgressIndicator(Modifier.size(16.dp).semantics {
                stateDescription = progressLabel
            }, strokeWidth = 2.dp)
            Text("  " + progressLabel, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = progressLabel
                })
        }
    }

    val current = extension.installedUsable && !extension.updateAvailable
    val primaryAction = when {
        source.enabled && !actionBusy && extension.activationAllowed && extension.installedDigest == null &&
            extension.installedStatus == com.axiel7.anihyou.release.core.source.InstalledPackageStatus.NOT_INSTALLED ->
            ExtensionPrimaryAction.INSTALL
        source.enabled && !actionBusy && extension.updateAvailable && extension.activationAllowed ->
            ExtensionPrimaryAction.UPDATE
        source.enabled && !actionBusy && current -> ExtensionPrimaryAction.CHECK
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primaryAction != null) {
            TextButton(
                onClick = {
                    when (primaryAction) {
                        ExtensionPrimaryAction.INSTALL, ExtensionPrimaryAction.UPDATE ->
                            event.activate(source.id, extension.extensionId)
                        ExtensionPrimaryAction.CHECK -> event.refreshSource(source.id)
                    }
                },
                enabled = !actionBusy,
                modifier = Modifier.testTag("extension-action-${extension.extensionId}"),
            ) {
                Text(stringResource(when (primaryAction) {
                    ExtensionPrimaryAction.INSTALL -> R.string.extension_install
                    ExtensionPrimaryAction.UPDATE -> R.string.extension_manage_update
                    ExtensionPrimaryAction.CHECK -> R.string.extension_manage_check
                }))
            }
        } else if (!extension.activationAllowed && (extension.updateAvailable ||
                extension.installedStatus == com.axiel7.anihyou.release.core.source.InstalledPackageStatus.NOT_INSTALLED)) {
            Text(
                text = stringResource(R.string.extension_sources_activation_unavailable),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { dialog = ExtensionManageDialog.CAPABILITIES },
            modifier = Modifier.testTag("extension-details-${extension.extensionId}")) {
            Text(stringResource(R.string.extension_manage_details))
        }
        if (digest != null) {
            TextButton(onClick = { dialog = ExtensionManageDialog.REMOVE_EXTENSION },
                enabled = !actionBusy,
                modifier = Modifier.testTag("extension-remove-${extension.extensionId}")) {
                Text(stringResource(R.string.extension_remove))
            }
        }
        val target = extension.rollbackTarget
        if (target != null && !actionBusy) {
            TextButton(onClick = {
                rollbackRequest = RollbackRequest(
                    sourceId = source.id,
                    extensionId = extension.extensionId,
                    expectedGeneration = extension.packageGeneration,
                    currentDigest = extension.installedDigest,
                    currentVersion = extension.installedVersion ?: unavailableLabel,
                    target = target,
                    reason = extension.lastUpdateFailure?.let(::updateFailureLabel),
                )
            }, modifier = Modifier.testTag("extension-rollback-${extension.extensionId}")) {
                Text(stringResource(R.string.extension_manage_previous_good))
            }
        }
    }
    if (dialog == ExtensionManageDialog.CAPABILITIES) {
        ExtensionCapabilityDialog(extension, onDismiss = { dialog = null })
    } else if (dialog == ExtensionManageDialog.REMOVE_EXTENSION) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.extension_manage_remove_extension_title)) },
            text = { Text(stringResource(R.string.extension_manage_remove_extension_message, extension.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        event.removeExtension(source.id, extension.extensionId)
                    },
                    enabled = !actionBusy,
                    modifier = Modifier.testTag("extension-remove-confirm-${extension.extensionId}"),
                ) { Text(stringResource(R.string.extension_sources_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null },
                    modifier = Modifier.testTag("extension-remove-cancel-${extension.extensionId}")) {
                    Text(stringResource(R.string.extension_manage_cancel))
                }
            },
        )
    }
    rollbackRequest?.let { request ->
        val rollbackTargetStillCurrent = extension.packageGeneration == request.expectedGeneration &&
            extension.installedDigest == request.currentDigest &&
            extension.rollbackTarget?.digest == request.target.digest &&
            extension.rollbackTarget?.trustState == request.target.trustState &&
            request.target.trustState == "TRUSTED"
        AlertDialog(
            onDismissRequest = { rollbackRequest = null },
            title = { Text(stringResource(R.string.extension_manage_rollback_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.extension_manage_rollback_versions,
                        request.currentVersion, request.target.version))
                    Text(stringResource(R.string.extension_manage_rollback_trust, request.target.trustState))
                    Text(stringResource(R.string.extension_manage_rollback_reason,
                        request.reason?.let { stringResource(it) } ?: stringResource(R.string.extension_manage_rollback_reason_default)))
                    Text(stringResource(R.string.extension_manage_rollback_warning))
                    if (!rollbackTargetStillCurrent) {
                        Text(stringResource(R.string.extension_manage_rollback_target_changed),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rollbackRequest = null
                        event.rollback(request.sourceId, request.extensionId,
                            request.expectedGeneration, request.target.digest)
                    },
                    enabled = !actionBusy && rollbackTargetStillCurrent,
                    modifier = Modifier.testTag("extension-rollback-confirm-${extension.extensionId}"),
                ) { Text(stringResource(R.string.extension_manage_previous_good)) }
            },
            dismissButton = {
                TextButton(onClick = { rollbackRequest = null },
                    modifier = Modifier.testTag("extension-rollback-cancel-${extension.extensionId}")) {
                    Text(stringResource(R.string.extension_manage_cancel))
                }
            },
        )
    }
    if (extension.rollbackTarget == null && extension.installedDigest != null &&
        extension.updateState in setOf(ExtensionUpdateState.UPDATE_FAILED, ExtensionUpdateState.ROLLBACK_AVAILABLE)) {
        Text(stringResource(R.string.extension_manage_no_previous_good), style = MaterialTheme.typography.bodySmall)
    }
    if (!extension.metadataFresh) {
        Text(stringResource(R.string.extension_manage_metadata_stale), style = MaterialTheme.typography.bodySmall)
    }
    if (extension.candidateYanked) {
        // D1: when the withdrawn release is the one that is installed and healthy, it keeps running. Say that
        // instead of implying an error: only a withdrawn candidate that is not installed is a problem.
        val installedIsWithdrawn = extension.installedDigest != null && extension.installedDigest == extension.digest
        Text(stringResource(if (installedIsWithdrawn) R.string.extension_manage_installed_yanked
                else R.string.extension_manage_candidate_yanked),
            style = MaterialTheme.typography.bodySmall,
            color = if (installedIsWithdrawn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("extension-yank-note-${extension.extensionId}"))
    }
}

private enum class ExtensionPrimaryAction { INSTALL, CHECK, UPDATE }
private enum class ExtensionManageDialog { CAPABILITIES, REMOVE_EXTENSION }

private data class RollbackRequest(
    val sourceId: String,
    val extensionId: String,
    val expectedGeneration: Long,
    val currentDigest: String?,
    val currentVersion: String,
    val target: com.axiel7.anihyou.release.core.source.ExtensionRollbackTarget,
    val reason: Int?,
)

@Composable
private fun ExtensionCapabilityDialog(extension: SourceExtension, onDismiss: () -> Unit) {
    val releaseCapabilities = extension.capabilities.filter { it in setOf("CALENDAR", "RECENT", "DIRECT", "POSTPONEMENT") }
    val navigationCapabilities = extension.capabilities.filter { it in setOf("OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION") }
    val otherCapabilities = extension.capabilities.filterNot { it in releaseCapabilities || it in navigationCapabilities }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extension_manage_details_title, extension.displayName)) },
        text = {
            Column {
                Text(stringResource(R.string.extension_manage_release_capabilities), style = MaterialTheme.typography.titleSmall)
                Text(releaseCapabilities.joinToString().ifBlank { stringResource(R.string.extension_manage_none) })
                Text(stringResource(R.string.extension_manage_navigation_capabilities),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(navigationCapabilities.joinToString().ifBlank { stringResource(R.string.extension_manage_none) })
                Text(stringResource(R.string.extension_manage_authority_separation),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (otherCapabilities.isNotEmpty()) {
                    Text(stringResource(R.string.extension_manage_other_capabilities),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(otherCapabilities.joinToString())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("extension-details-close-${extension.extensionId}")) {
                Text(stringResource(R.string.extension_manage_close))
            }
        },
    )
}

internal fun ExtensionUpdateState.isInFlight(): Boolean = when (this) {
    ExtensionUpdateState.CHECKING,
    ExtensionUpdateState.DOWNLOADING,
    ExtensionUpdateState.VERIFYING,
    ExtensionUpdateState.STAGING,
    ExtensionUpdateState.ACTIVATING,
    ExtensionUpdateState.ROLLING_BACK -> true
    else -> false
}

internal fun extensionUpdateStateLabel(state: ExtensionUpdateState): Int = when (state) {
    ExtensionUpdateState.NOT_INSTALLED -> R.string.extension_manage_state_not_installed
    ExtensionUpdateState.INSTALLED_CURRENT -> R.string.extension_manage_state_current
    ExtensionUpdateState.UPDATE_AVAILABLE -> R.string.extension_manage_state_update_available
    ExtensionUpdateState.CHECKING -> R.string.extension_manage_state_checking
    ExtensionUpdateState.DOWNLOADING -> R.string.extension_manage_state_downloading
    ExtensionUpdateState.VERIFYING -> R.string.extension_manage_state_verifying
    ExtensionUpdateState.STAGING -> R.string.extension_manage_state_staging
    ExtensionUpdateState.ACTIVATING -> R.string.extension_manage_state_activating
    ExtensionUpdateState.UPDATED -> R.string.extension_manage_state_updated
    ExtensionUpdateState.UPDATE_FAILED -> R.string.extension_manage_state_update_failed
    ExtensionUpdateState.ROLLBACK_AVAILABLE -> R.string.extension_manage_state_previous_good_available
    ExtensionUpdateState.ROLLING_BACK -> R.string.extension_manage_state_rolling_back
    ExtensionUpdateState.ROLLED_BACK -> R.string.extension_manage_state_rolled_back
    ExtensionUpdateState.REVOKED -> R.string.extension_manage_state_revoked
    ExtensionUpdateState.QUARANTINED -> R.string.extension_manage_state_quarantined
    ExtensionUpdateState.UNUSABLE -> R.string.extension_manage_state_unusable
    ExtensionUpdateState.TRUST_UNAVAILABLE -> R.string.extension_manage_state_trust_unavailable
}

internal fun installedPackageStatusLabel(extension: SourceExtension): Int =
    when (extension.installedStatus) {
        com.axiel7.anihyou.release.core.source.InstalledPackageStatus.NOT_INSTALLED -> R.string.extension_manage_trust_not_installed
        com.axiel7.anihyou.release.core.source.InstalledPackageStatus.USABLE -> R.string.extension_manage_trust_usable
        com.axiel7.anihyou.release.core.source.InstalledPackageStatus.REVOKED -> R.string.extension_manage_trust_revoked
        com.axiel7.anihyou.release.core.source.InstalledPackageStatus.QUARANTINED -> R.string.extension_manage_trust_quarantined
        com.axiel7.anihyou.release.core.source.InstalledPackageStatus.UNUSABLE -> R.string.extension_manage_trust_unusable
        com.axiel7.anihyou.release.core.source.InstalledPackageStatus.TRUST_UNAVAILABLE -> R.string.extension_manage_trust_unavailable
    }

internal fun updateFailureLabel(failure: ExtensionUpdateFailure): Int = when (failure) {
    ExtensionUpdateFailure.NETWORK -> R.string.extension_manage_failure_network
    ExtensionUpdateFailure.TRUST -> R.string.extension_manage_failure_trust
    ExtensionUpdateFailure.METADATA -> R.string.extension_manage_failure_metadata
    ExtensionUpdateFailure.SIGNATURE_OR_BINDING -> R.string.extension_manage_failure_signature
    ExtensionUpdateFailure.DIGEST -> R.string.extension_manage_failure_digest
    ExtensionUpdateFailure.RUNTIME -> R.string.extension_manage_failure_runtime
    ExtensionUpdateFailure.SMOKE -> R.string.extension_manage_failure_smoke
    ExtensionUpdateFailure.ACTIVATION -> R.string.extension_manage_failure_activation
    ExtensionUpdateFailure.STORAGE -> R.string.extension_manage_failure_storage
    ExtensionUpdateFailure.CANCELLED -> R.string.extension_manage_failure_cancelled
    ExtensionUpdateFailure.INTERRUPTED -> R.string.extension_manage_failure_interrupted
}

private fun repositoryHost(url: String): String? = try {
    java.net.URI(url).host?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

@Composable
internal fun ExtensionTrackPreferences(
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
        .map { it.substringBeforeLast('_').lowercase(java.util.Locale.ROOT) }
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
internal fun SelectionOption(
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
internal fun TrackSwitch(
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

internal fun SourceExtension.isUsableInstalled(): Boolean =
    installedDigest != null && installedUsable

internal fun SourceExtension.supportsNavigation(): Boolean =
    capabilities.any { it == "OVERVIEW_NAVIGATION" || it == "EPISODE_NAVIGATION" }

internal fun ExtensionSelectionKey.testTagPart(): String =
    listOf(sourceId, extensionId, publisherId, providerId).joinToString("-")

internal fun ExtensionPreferences.withGenericDefaults(extension: SourceExtension): ExtensionPreferences {
    return ExtensionPreferences.forTracks(extension.supportedTracks).copy(visibleInProviderField = visibleInProviderField)
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

/**
 * Shown once for a source nothing could verify independently. It names what was actually received (URL, root fingerprint,
 * requested capabilities, hosts); the publisher names are the source's own claim. Confirming stores this acceptance only.
 */
@Composable
fun UnverifiedSourceDialog(
    preview: UnverifiedSourcePreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        modifier = Modifier.testTag("extension-trust-dialog"),
        title = { Text(stringResource(R.string.extension_trust_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.extension_trust_warning), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.extension_trust_url, preview.url), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.extension_trust_repository, preview.repositoryId), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.extension_trust_fingerprint, preview.rootFingerprint.chunked(8).joinToString(" ")),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.testTag("extension-trust-fingerprint"),
                )
                Text(stringResource(R.string.extension_trust_capabilities, preview.capabilities.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.extension_trust_hosts, preview.hosts.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.extension_trust_publishers, preview.publisherIds.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.testTag("extension-trust-confirm")) {
                Text(stringResource(R.string.extension_trust_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("extension-trust-cancel")) {
                Text(stringResource(R.string.extension_trust_cancel))
            }
        },
    )
}

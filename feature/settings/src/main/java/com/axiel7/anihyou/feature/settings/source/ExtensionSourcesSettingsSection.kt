package com.axiel7.anihyou.feature.settings.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.SourceExtension

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
            modifier = Modifier.fillMaxWidth(),
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
            Spacer(Modifier.height(8.dp))
            uiState.sources.forEach { source ->
                ExtensionSourceCard(
                    source = source,
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
    event: ExtensionSourcesEvent,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
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
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { event.refreshSource(source.id) }) {
                    Text(stringResource(R.string.extension_sources_refresh))
                }
                TextButton(onClick = { event.removeSource(source.id) }) {
                    Text(stringResource(R.string.extension_sources_remove))
                }
            }
            source.extensions.forEach { extension ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SourceExtensionInfo(
                    sourceId = source.id,
                    sourceEnabled = source.enabled,
                    extension = extension,
                    event = event,
                )
            }
        }
    }
}

@Composable
private fun SourceExtensionInfo(
    sourceId: String,
    sourceEnabled: Boolean,
    extension: SourceExtension,
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
    if (extension.installedVersion != null) {
        Text(
            text = stringResource(R.string.extension_sources_installed, extension.installedVersion),
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
        TextButton(onClick = { event.activate(sourceId, extension.extensionId) }) {
            Text(stringResource(R.string.extension_sources_activate))
        }
    } else {
        Text(
            text = stringResource(R.string.extension_sources_activation_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )
    }
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

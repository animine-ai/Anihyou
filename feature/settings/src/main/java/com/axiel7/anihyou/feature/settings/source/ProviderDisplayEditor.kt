package com.axiel7.anihyou.feature.settings.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.core.ui.composables.*
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.*

@Composable
fun ProviderDisplayEditor(state: ExtensionSourcesUiState, event: ExtensionSourcesEvent) {
    val canEdit = state.canEditProductPolicy && !state.hasSourceOperationInFlight()
    val entries = installedEntries(state).filter { it.second.supportsNavigation() }
        .sortedWith(compareBy<Pair<ExtensionSelectionKey, SourceExtension>> {
            state.productPolicy.navigationProviderOrder.indexOf(it.first).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }.thenBy { it.first.sourceId }.thenBy { it.first.extensionId })
    val shown = entries.filter { state.productPolicy.preferencesFor(it.first, it.second.supportedTracks).visibleInProviderField }
    val hidden = entries.filterNot { it in shown }
    Text(stringResource(R.string.extension_provider_preferred_explanation),
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (entries.isEmpty()) {
        Text(stringResource(R.string.extension_provider_empty), modifier = Modifier.padding(24.dp))
        return
    }
    listOf(R.string.extension_provider_shown to shown, R.string.extension_provider_hidden to hidden).forEach { (title, group) ->
        PreferencesTitle(stringResource(title))
        group.forEachIndexed { index, (key, extension) ->
            val visible = key in shown.map { it.first }
            ProviderRow(key, extension, visible, state.productPolicy.preferredNavigationProvider == key,
                canEdit, preferenceShape(index, group.size), index > 0, index < group.lastIndex,
                event, onMove = { delta ->
                    val order = shown.map { it.first }.toMutableList()
                    order.removeAt(index); order.add(index + delta, key)
                    event.setProviderOrder(order + hidden.map { it.first })
                })
        }
    }
    Column(Modifier.padding(vertical = 16.dp)) {
        PlainPreference(title = stringResource(R.string.extension_provider_reset), icon = CoreR.drawable.refresh_24,
            enabled = canEdit, onClick = event::resetProviderDisplay, shape = singleShape,
            modifier = Modifier.testTag("provider-display-reset"))
    }
}

@Composable
private fun ProviderRow(key: ExtensionSelectionKey, extension: SourceExtension, visible: Boolean, preferred: Boolean,
    canEdit: Boolean, shape: RoundedCornerShape, up: Boolean, down: Boolean, event: ExtensionSourcesEvent,
    onMove: (Int) -> Unit) {
    val title = extension.displayName
    val preferLabel = stringResource(if (preferred) R.string.extension_provider_unprefer else R.string.extension_provider_prefer, title)
    val visibilityLabel = stringResource(R.string.extension_provider_visibility, title)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(CoreR.drawable.play_circle_24), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
                Text(title, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                Switch(checked = visible, onCheckedChange = { event.setProviderVisibility(key, it) }, enabled = canEdit,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                        .testTag("extension-preference-provider-visible-${key.testTagPart()}")
                        .semantics { contentDescription = visibilityLabel })
            }
            if (visible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { event.selectNavigationProvider(if (preferred) null else key) }, enabled = canEdit,
                    modifier = Modifier.testTag("extension-product-navigation-${key.testTagPart()}")
                        .semantics { toggleableState = if (preferred) ToggleableState.On else ToggleableState.Off }) {
                    Icon(painterResource(if (preferred) CoreR.drawable.star_filled_24 else CoreR.drawable.star_24),
                        contentDescription = preferLabel,
                        tint = if (preferred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onMove(-1) }, enabled = canEdit && up,
                    modifier = Modifier.testTag("provider-up-${key.testTagPart()}")) {
                    Icon(painterResource(CoreR.drawable.arrow_upward_24), contentDescription = stringResource(R.string.extension_provider_move_up, title))
                }
                IconButton(onClick = { onMove(1) }, enabled = canEdit && down,
                    modifier = Modifier.testTag("provider-down-${key.testTagPart()}")) {
                    Icon(painterResource(CoreR.drawable.arrow_downward_24), contentDescription = stringResource(R.string.extension_provider_move_down, title))
                }
            }
        }
    }
}

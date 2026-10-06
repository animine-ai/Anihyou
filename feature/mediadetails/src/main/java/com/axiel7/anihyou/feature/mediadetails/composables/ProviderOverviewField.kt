package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationProductState
import com.axiel7.anihyou.release.core.navigation.WatchNextState
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey

/** Overview navigation controls backed only by signed providers from the product repository. */
@VisibleForTesting
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderOverviewField(
    navigationState: ProviderNavigationProductState,
    onOpenProvider: (ExtensionSelectionKey) -> Unit,
    onChooseProvider: (ExtensionSelectionKey) -> Unit,
    modifier: Modifier = Modifier,
    streamingLinks: @Composable () -> Unit = {},
) {
    var showProviderChooser by remember { mutableStateOf(false) }
    val overviewProviders = navigationState.providers.filter {
        NavigationCapability.OVERVIEW_NAVIGATION in it.capabilities
    }.distinctBy { it.key }.sortedBy { if (it.key == navigationState.activeReleaseSource) 0 else 1 }
    val chooserProviders = (navigationState.watchNext as? WatchNextState.ChooseProvider)
        ?.providers.orEmpty()

    if (showProviderChooser && chooserProviders.size > 1) {
        AlertDialog(
            modifier = Modifier.testTag("provider-watch-next-chooser-dialog"),
            onDismissRequest = { showProviderChooser = false },
            title = { Text(stringResource(R.string.choose_watch_next_provider)) },
            text = {
                Column {
                    chooserProviders.forEach { provider ->
                        TextButton(
                            onClick = {
                                showProviderChooser = false
                                onChooseProvider(provider.key)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !navigationState.loading,
                        ) {
                            Text(provider.displayName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProviderChooser = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    Column(modifier = modifier.testTag("provider-overview-field")) {
        FlowRow(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        ) {
            overviewProviders.forEachIndexed { index, provider ->
                val isActiveReleaseSource = provider.key == navigationState.activeReleaseSource
                AssistChip(
                    onClick = { onOpenProvider(provider.key) },
                    enabled = !navigationState.loading,
                    label = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(provider.displayName)
                            if (isActiveReleaseSource) {
                                Text(
                                    text = stringResource(R.string.active_release_source),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .testTag("provider-overview-$index"),
                )
            }
            streamingLinks()
        }

        if (chooserProviders.size > 1) {
            TextButton(
                onClick = { showProviderChooser = true },
                enabled = !navigationState.loading,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .testTag("provider-watch-next-chooser"),
            ) {
                Text(stringResource(R.string.choose_watch_next_provider))
            }
        }

        if (navigationState.loading) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.provider_navigation_loading))
            }
        } else {
            val unavailableReason = navigationState.failure
                ?: (navigationState.watchNext as? WatchNextState.Unavailable)
                    ?.reason
                    ?.takeUnless { it == NavigationUnavailableReason.CHOOSE_PROVIDER }
            if (unavailableReason != null && (overviewProviders.isNotEmpty() || navigationState.failure != null)) {
                Text(
                    text = unavailableReason.localizedNavigationMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NavigationUnavailableReason.localizedNavigationMessage(): String = stringResource(
    when (this) {
        NavigationUnavailableReason.NO_ACTIVE_SOURCE -> R.string.navigation_no_active_source
        NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE -> R.string.navigation_release_source_unavailable
        NavigationUnavailableReason.NO_PROVIDERS,
        NavigationUnavailableReason.PROVIDER_UNAVAILABLE -> R.string.navigation_provider_unavailable
        NavigationUnavailableReason.CHOOSE_PROVIDER -> R.string.navigation_choose_provider
        NavigationUnavailableReason.UNKNOWN_PROGRESS -> R.string.navigation_unknown_progress
        NavigationUnavailableReason.NO_RELEASED_UNWATCHED -> R.string.navigation_no_released_episode
        NavigationUnavailableReason.MISSING_MAPPING -> R.string.navigation_missing_mapping
        NavigationUnavailableReason.TRACK_UNAVAILABLE -> R.string.navigation_track_unavailable
        NavigationUnavailableReason.INVALID_TARGET -> R.string.navigation_invalid_target
        NavigationUnavailableReason.STALE_RESULT -> R.string.navigation_stale_result
        NavigationUnavailableReason.LAUNCH_FAILED -> R.string.navigation_launch_failed
    }
)

package com.axiel7.anihyou.feature.settings.source

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.core.ui.composables.*
import com.axiel7.anihyou.feature.settings.R
import com.axiel7.anihyou.release.core.source.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ExtensionStatistics(state: ExtensionSourcesUiState, onRefresh: (() -> Unit)? = null) {
    val key = state.productPolicy.activeReleaseSource
    val extension = installedEntries(state).singleOrNull { it.first == key }?.second
    if (key == null || extension == null) {
        Text(stringResource(R.string.extension_statistics_empty), modifier = Modifier.padding(24.dp))
        return
    }
    val statistics = state.statistics[key]
    val counts = statistics?.matching
    val unknown = stringResource(R.string.extension_stats_no_data)
    PreferencesTitle(extension.displayName)
    Text(stringResource(R.string.extension_stats_scope), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
    val rows = listOf(
        Triple(R.string.extension_stats_found, counts?.found?.toString() ?: unknown, "found"),
        Triple(R.string.extension_stats_matched, counts?.matched?.toString() ?: unknown, "matched"),
        Triple(R.string.extension_stats_unmatched, counts?.unmatched?.toString() ?: unknown, "unmatched"),
        Triple(R.string.extension_stats_errors, stringResource(when (statistics?.updateStatus) {
            ExtensionDataUpdateStatus.SUCCESS -> R.string.extension_stats_errors_none
            ExtensionDataUpdateStatus.PARTIAL -> R.string.extension_stats_errors_partial
            ExtensionDataUpdateStatus.FAILED -> R.string.extension_stats_errors_failed
            else -> R.string.extension_stats_not_checked
        }), "errors"),
    )
    rows.forEachIndexed { index, (label, value, tag) ->
        StatisticRow(stringResource(label), value, "extension-stat-$tag", index, rows.size)
    }
    PreferencesTitle(stringResource(R.string.extension_stats_update))
    val configuration = LocalConfiguration.current
    val updated = statistics?.lastUpdatedAt?.atZone(ZoneId.systemDefault())?.format(
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(configuration.locales[0])) ?: stringResource(R.string.extension_stats_never_updated)
    StatisticRow(stringResource(R.string.extension_stats_last_updated), updated, "extension-stat-updated", 0, 1)
    if (onRefresh != null) {
        Column(Modifier.padding(vertical = 16.dp)) {
            PlainPreference(title = stringResource(R.string.extension_stats_refresh), icon = CoreR.drawable.refresh_24,
                onClick = onRefresh, enabled = !state.hasSourceOperationInFlight(), shape = singleShape,
                modifier = Modifier.testTag("extension-stats-refresh"))
        }
    }
}

@Composable
private fun StatisticRow(label: String, value: String, tag: String, index: Int, count: Int) {
    Surface(shape = preferenceShape(index, count), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp).testTag(tag))
        }
    }
}

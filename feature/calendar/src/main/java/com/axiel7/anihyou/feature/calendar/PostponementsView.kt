package com.axiel7.anihyou.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
import com.axiel7.anihyou.release.core.extension.ObservationScheduleMarker
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PostponementsView(
    modifier: Modifier = Modifier,
) {
    val model: PostponementsViewModel = koinViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    val nav = LocalNavActionManager.current

    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.postponements),
        navigationIcon = { BackIconButton(nav::goBack) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            state.observedAt?.let { observedAt ->
                item(key = "postponements-updated") {
                    Text(
                        text = stringResource(
                            R.string.postponements_last_updated,
                            observedAt.atZone(ZoneId.systemDefault()).format(UPDATED_FORMAT),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }

            if (state.notices.isEmpty()) {
                item(key = "postponements-empty") {
                    Text(
                        text = stringResource(R.string.postponements_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(
                    items = state.notices,
                    key = { it.stableKey() },
                ) { notice ->
                    PostponementRow(
                        notice = notice,
                        onClick = notice.mediaId?.let { mediaId ->
                            { nav.toMediaDetails(mediaId) }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PostponementRow(
    notice: ExtensionPostponementNotice,
    onClick: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 4.dp)) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = notice.detailsLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            notice.rawText?.takeIf { it.isNotBlank() }?.let { raw ->
                Text(
                    text = raw,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ExtensionPostponementNotice.detailsLabel(): String {
    val coordinates = buildList {
        sourceSeason?.let { add(stringResource(R.string.postponements_season, it)) }
        installmentNumber?.let { add(stringResource(R.string.postponements_episode, it)) }
        add(
            when (track) {
                ObservationTrack.DE_SUB -> "SUB"
                ObservationTrack.DE_DUB -> "DUB"
                ObservationTrack.UNKNOWN -> stringResource(R.string.postponements_track_unknown)
            },
        )
    }.joinToString(" · ")
    val status = when (marker) {
        ObservationScheduleMarker.POSTPONED -> stringResource(R.string.postponements_postponed)
        ObservationScheduleMarker.RESCHEDULED -> stringResource(R.string.postponements_rescheduled)
        ObservationScheduleMarker.CANCELLED -> stringResource(R.string.postponements_cancelled)
        ObservationScheduleMarker.NONE,
        ObservationScheduleMarker.UNKNOWN,
        -> ""
    }
    return listOf(coordinates, status).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun ExtensionPostponementNotice.stableKey(): String =
    listOf(
        title,
        sourceSeason?.toString().orEmpty(),
        installmentNumber.orEmpty(),
        track.name,
        marker.name,
        rawText.orEmpty(),
    ).joinToString("|")

private val UPDATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

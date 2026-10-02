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
import com.axiel7.anihyou.core.ui.common.LocalBlurAdult
import com.axiel7.anihyou.core.ui.composables.media.MediaItemHorizontal
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
import com.axiel7.anihyou.release.core.extension.ObservationScheduleMarker
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PostponementsView(
    modifier: Modifier = Modifier,
) {
    val model: PostponementsViewModel = koinViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    PostponementsViewContent(state, modifier)
}

@Composable
fun PostponementsViewContent(state: PostponementsUiState, modifier: Modifier = Modifier) {
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
                    items = state.notices.filter { it.mediaId != null },
                    key = { it.presentationKey },
                ) { notice ->
                    val details = notice.mediaId?.let(state.metadata::get)
                    MediaItemHorizontal(
                        title = details?.title?.takeIf { it.isNotBlank() } ?: notice.title,
                        imageUrl = details?.cover,
                        blurImage = LocalBlurAdult.current && details?.adult == true,
                        subtitle1 = { Text(notice.detailsLabel(), color = MaterialTheme.colorScheme.primary) },
                        subtitle2 = { notice.rawText?.let { Text(it) } },
                        onClick = { notice.mediaId?.let(nav::toMediaDetails) },
                    )
                    HorizontalDivider()
                }
                val unassigned = state.notices.filter { it.mediaId == null }
                if (unassigned.isNotEmpty()) {
                    item(key = "postponements-unassigned") {
                        Text(
                            stringResource(R.string.postponements_unassigned),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                        )
                    }
                    items(unassigned, key = { it.presentationKey }) { notice ->
                        PostponementRow(notice, onClick = null)
                        HorizontalDivider()
                    }
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
        installmentNumber?.let {
            add(stringResource(when (installmentKind) {
                ObservationInstallmentKind.EPISODE -> R.string.postponements_episode
                ObservationInstallmentKind.FILM -> R.string.postponements_film
                ObservationInstallmentKind.SPECIAL, ObservationInstallmentKind.UNKNOWN -> R.string.postponements_installment
            }, it))
        }
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

private val UPDATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

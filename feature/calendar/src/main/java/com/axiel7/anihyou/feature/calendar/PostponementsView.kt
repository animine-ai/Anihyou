package com.axiel7.anihyou.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.axiel7.anihyou.core.ui.composables.common.SearchPillField
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
    PostponementsViewContent(state, modifier, onRefresh = model::refresh, onSearch = model::search)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PostponementsViewContent(
    state: PostponementsUiState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onSearch: (String) -> Unit = {},
) {
    val nav = LocalNavActionManager.current

    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.postponements),
        navigationIcon = { BackIconButton(nav::goBack) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        modifier = modifier,
    ) { padding ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("postponements-list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "postponements-search") {
                SearchPillField(
                    value = state.query,
                    onValueChange = onSearch,
                    placeholder = stringResource(R.string.postponements_search),
                    modifier = Modifier.padding(bottom = 12.dp).testTag("postponements-search"),
                )
            }
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
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        MediaItemHorizontal(
                            title = details?.title?.takeIf { it.isNotBlank() } ?: notice.title,
                            imageUrl = details?.cover,
                            blurImage = LocalBlurAdult.current && details?.adult == true,
                            subtitle1 = { Text(notice.detailsLabel(), color = MaterialTheme.colorScheme.primary) },
                            subtitle2 = { notice.rawText?.let { Text(it) } },
                            onClick = { notice.mediaId?.let(nav::toMediaDetails) },
                        )
                    }
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
                    }
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 16.dp)) {
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

package com.axiel7.anihyou.feature.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.axiel7.anihyou.core.ui.composables.common.SearchPillField
import androidx.compose.material3.MaterialTheme
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
        // The pull gesture is easy to miss on a short list, so the same refresh is a button with its own progress.
        actions = {
            if (state.isRefreshing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(24.dp).testTag("postponements-refreshing"),
                    strokeWidth = 2.dp,
                )
            } else {
                androidx.compose.material3.IconButton(onClick = onRefresh, modifier = Modifier.testTag("postponements-refresh")) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.refresh_24),
                        contentDescription = stringResource(R.string.refresh),
                    )
                }
            }
        },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        modifier = modifier,
    ) { padding ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("postponements-list"),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                item(key = "postponements-search") {
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .testTag("postponements-search-container")) {
                        SearchPillField(
                            value = state.query,
                            onValueChange = onSearch,
                            placeholder = stringResource(R.string.postponements_search),
                            modifier = Modifier.testTag("postponements-search"),
                        )
                    }
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
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                                .testTag("postponements-updated"),
                        )
                    }
                }

                if (state.notices.isEmpty()) {
                    item(key = "postponements-empty") {
                        Text(
                            text = if (state.query.isNotBlank() && state.totalNotices > 0)
                                stringResource(R.string.postponements_no_results, state.query.trim())
                            else stringResource(R.string.postponements_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                                .testTag("postponements-empty"),
                        )
                    }
                } else {
                    // Every notice of the source is listed; the ones with an AniList binding show its cover and open its details,
                    // the others show the source title only.
                    items(
                        items = state.notices,
                        key = { it.presentationKey },
                    ) { notice ->
                        val details = notice.mediaId?.let(state.metadata::get)
                        MediaItemHorizontal(
                            title = details?.title?.takeIf { it.isNotBlank() } ?: notice.title,
                            imageUrl = details?.cover,
                            blurImage = LocalBlurAdult.current && details?.adult == true,
                            subtitle1 = {
                                Text(notice.detailsLabel(), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            },
                            subtitle2 = {
                                notice.rawText?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { notice.mediaId?.let(nav::toMediaDetails) },
                        )
                    }
                }
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

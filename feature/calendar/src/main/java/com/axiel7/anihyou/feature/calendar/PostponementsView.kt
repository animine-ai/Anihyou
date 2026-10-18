package com.axiel7.anihyou.feature.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.axiel7.anihyou.core.ui.composables.common.CenteredMessage
import com.axiel7.anihyou.core.ui.composables.common.SearchPillField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
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
    // A search that is running (its text is kept over a rotation) stays open; otherwise only the symbol shows.
    var searchOpen by rememberSaveable { mutableStateOf(state.query.isNotBlank()) }
    val searchFocus = remember { FocusRequester() }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(searchOpen) { if (searchOpen && state.query.isBlank()) runCatching { searchFocus.requestFocus() } }

    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.postponements),
        navigationIcon = { BackIconButton(nav::goBack) },
        // The only action is the search: a symbol that opens the field, and closes and clears it again.
        actions = {
            IconButton(
                onClick = {
                    if (searchOpen) onSearch("")
                    searchOpen = !searchOpen
                },
                modifier = Modifier.testTag("postponements-search-toggle"),
            ) {
                Icon(
                    painter = painterResource(if (searchOpen) R.drawable.close_24 else R.drawable.search_24),
                    contentDescription = stringResource(R.string.postponements_search),
                )
            }
        },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        modifier = modifier,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            // The same expressive indicator as the lists of the rest of the app.
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter).testTag("postponements-refreshing"),
                )
            },
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
                if (searchOpen) item(key = "postponements-search") {
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .testTag("postponements-search-container")) {
                        SearchPillField(
                            value = state.query,
                            onValueChange = onSearch,
                            placeholder = stringResource(R.string.postponements_search),
                            modifier = Modifier.focusRequester(searchFocus).testTag("postponements-search"),
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
                        val searching = state.query.isNotBlank() && state.totalNotices > 0
                        Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(0.7f), contentAlignment = Alignment.Center) {
                            CenteredMessage(
                                iconRes = if (searching) R.drawable.search_24 else R.drawable.schedule_24,
                                text = if (searching) stringResource(R.string.postponements_no_results, state.query.trim())
                                else stringResource(R.string.postponements_empty),
                                modifier = Modifier.testTag("postponements-empty"),
                            )
                        }
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

package com.axiel7.anihyou.feature.settings.source.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.core.ui.composables.common.SearchPillField
import com.axiel7.anihyou.core.ui.composables.media.MediaItemHorizontal
import com.axiel7.anihyou.feature.settings.R
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NotificationAnimeView() {
    val model: NotificationAnimeViewModel = koinViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    NotificationAnimeScreen(state, model)
}

/** Each anime with three choices: follow the general setting, always announce, never announce. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationAnimeScreen(state: NotificationAnimeState, event: NotificationAnimeEvent) {
    val nav = LocalNavActionManager.current
    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.notification_anime_title),
        navigationIcon = { BackIconButton(nav::goBack) },
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchPillField(
                    value = state.query, onValueChange = event::search,
                    placeholder = stringResource(R.string.notification_anime_search),
                    modifier = Modifier.testTag("notification-anime-search"),
                )
                Text(
                    stringResource(R.string.notification_anime_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (state.loading) LinearProgressIndicator(Modifier.padding(top = 4.dp))
            }
            LazyColumn(Modifier.weight(1f).testTag("notification-anime-list")) {
                items(state.rows, key = { it.mediaId }) { row ->
                    val decision = state.overrides[row.mediaId]
                    MediaItemHorizontal(
                        title = row.title, imageUrl = row.coverUrl, blurImage = false, status = null,
                        subtitle1 = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Chip(R.string.notification_anime_default, decision == null, "default", row.mediaId) {
                                    event.decide(row.mediaId, null)
                                }
                                Chip(R.string.notification_anime_always, decision == true, "always", row.mediaId) {
                                    event.decide(row.mediaId, true)
                                }
                                Chip(R.string.notification_anime_never, decision == false, "never", row.mediaId) {
                                    event.decide(row.mediaId, false)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: Int, selected: Boolean, tag: String, mediaId: Int, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(stringResource(label)) },
        modifier = Modifier.testTag("notification-anime-$tag-$mediaId"),
    )
}

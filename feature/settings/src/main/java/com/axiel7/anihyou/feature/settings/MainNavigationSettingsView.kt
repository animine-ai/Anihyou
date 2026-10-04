package com.axiel7.anihyou.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.resources.R as CoreR
import com.axiel7.anihyou.core.ui.common.BottomDestination
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.MainNavigationConfig
import com.axiel7.anihyou.core.ui.common.navigation.MainNavigationConfigStore
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.PlainPreference
import com.axiel7.anihyou.core.ui.composables.PreferencesTitle
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.core.ui.composables.preferenceShape
import com.axiel7.anihyou.core.ui.composables.singleShape

/** The most main tabs the bottom bar and the rail can show; the editor never allows more. */
private const val MAX_VISIBLE = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationSettingsView() {
    val nav = LocalNavActionManager.current
    val store = MainNavigationConfigStore.get(LocalContext.current)
    val config by store.config.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.main_navigation_title),
        scrollBehavior = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior(),
        navigationIcon = { BackIconButton(nav::goBack) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            MainNavigationEditor(config, { transform -> failed = !store.update(transform) })
            if (failed) {
                Text(
                    text = stringResource(R.string.main_navigation_save_failed),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The editor follows the grouped preference rows of the original settings pages: the tabs in the bar first (in their
 * order, each with its own move buttons and switch), then the others. Every control names its tab for a screen reader.
 */
@Composable
fun MainNavigationEditor(
    config: MainNavigationConfig,
    update: ((MainNavigationConfig) -> MainNavigationConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = config.normalized().visibleIds
    val shown = visible.map { id -> BottomDestination.catalog.single { it.stableId == id } }
    val hidden = BottomDestination.catalog.filter { it.stableId !in visible }
    Column(modifier.testTag("main-navigation-editor")) {
        Text(
            text = stringResource(R.string.main_navigation_explanation),
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        PreferencesTitle(stringResource(R.string.main_navigation_shown))
        shown.forEachIndexed { position, destination ->
            MainNavigationRow(
                destination = destination,
                isShown = true,
                canToggle = destination.stableId != "home",
                canMoveUp = position > 0,
                canMoveDown = position < shown.lastIndex,
                shape = preferenceShape(position, shown.size),
                update = update,
            )
        }
        if (hidden.isNotEmpty()) {
            PreferencesTitle(stringResource(R.string.main_navigation_hidden))
            hidden.forEachIndexed { position, destination ->
                MainNavigationRow(
                    destination = destination,
                    isShown = false,
                    canToggle = visible.size < MAX_VISIBLE,
                    canMoveUp = false,
                    canMoveDown = false,
                    shape = preferenceShape(position, hidden.size),
                    update = update,
                )
            }
        }
        Column(Modifier.padding(top = 16.dp, bottom = 16.dp)) {
            PlainPreference(
                title = stringResource(R.string.main_navigation_reset),
                icon = CoreR.drawable.refresh_24,
                modifier = Modifier.testTag("main-reset"),
                shape = singleShape,
                onClick = { update { MainNavigationConfig() } },
            )
        }
    }
}

@Composable
private fun MainNavigationRow(
    destination: BottomDestination,
    isShown: Boolean,
    canToggle: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    shape: androidx.compose.foundation.shape.RoundedCornerShape,
    update: ((MainNavigationConfig) -> MainNavigationConfig) -> Unit,
) {
    val id = destination.stableId
    val title = destination.displayTitle()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (isShown) {
                IconButton(
                    onClick = { update { it.move(id, -1) } },
                    enabled = canMoveUp,
                    modifier = Modifier.testTag("main-move-up-$id"),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.arrow_upward_24),
                        contentDescription = stringResource(R.string.main_navigation_move_up, title),
                    )
                }
                IconButton(
                    onClick = { update { it.move(id, 1) } },
                    enabled = canMoveDown,
                    modifier = Modifier.testTag("main-move-down-$id"),
                ) {
                    Icon(
                        painter = painterResource(CoreR.drawable.arrow_downward_24),
                        contentDescription = stringResource(R.string.main_navigation_move_down, title),
                    )
                }
            }
            Switch(
                checked = isShown,
                onCheckedChange = { show -> update { if (show) it.show(id) else it.hide(id) } },
                enabled = canToggle,
                modifier = Modifier
                    .padding(start = 4.dp, end = 8.dp)
                    .testTag("main-visible-$id")
                    .semantics { contentDescription = title },
            )
        }
    }
}

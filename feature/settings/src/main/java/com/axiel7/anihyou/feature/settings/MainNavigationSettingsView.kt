package com.axiel7.anihyou.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.ui.common.BottomDestination
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.MainNavigationConfig
import com.axiel7.anihyou.core.ui.common.navigation.MainNavigationConfigStore
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton

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
        MainNavigationEditor(config, { transform -> failed = !store.update(transform) },
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp))
        if (failed) Text(stringResource(R.string.main_navigation_save_failed))
    }
}

@Composable
fun MainNavigationEditor(
    config: MainNavigationConfig,
    update: ((MainNavigationConfig) -> MainNavigationConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalized = config.normalized()
    val visible = normalized.visibleIds
    val ordered = visible.map { id -> BottomDestination.catalog.single { it.stableId == id } } +
        BottomDestination.catalog.filter { it.stableId !in visible }
    Column(modifier.testTag("main-navigation-editor")) {
        Text(stringResource(R.string.main_navigation_explanation))
        ordered.forEach { destination ->
            val id = destination.stableId
            val index = visible.indexOf(id)
            Row {
                Column(Modifier.weight(1f)) {
                    Text(destination.displayTitle())
                }
                if (index >= 0) {
                    TextButton(onClick = { update { it.move(id, -1) } }, enabled = index > 0,
                        modifier = Modifier.testTag("main-move-up-$id")) { Text("↑") }
                    TextButton(onClick = { update { it.move(id, 1) } }, enabled = index < visible.lastIndex,
                        modifier = Modifier.testTag("main-move-down-$id")) { Text("↓") }
                }
                Switch(checked = index >= 0, enabled = id != "home" && (index >= 0 || visible.size < 5),
                    onCheckedChange = { show -> update { if (show) it.show(id) else it.hide(id) } },
                    modifier = Modifier.testTag("main-visible-$id"))
            }
        }
        TextButton(onClick = { update { MainNavigationConfig() } },
            modifier = Modifier.testTag("main-reset")) { Text(stringResource(R.string.main_navigation_reset)) }
    }
}

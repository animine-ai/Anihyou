package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationProductState

/** Same baseline: confirmed watch-next action at the left, edit/list status at the right. */
@Composable
fun MediaDetailsFloatingActions(
    navigationState: ProviderNavigationProductState,
    onWatchNext: () -> Unit,
    editAction: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderWatchNextFloatingActionButton(navigationState, onClick = onWatchNext)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { editAction() }
    }
}

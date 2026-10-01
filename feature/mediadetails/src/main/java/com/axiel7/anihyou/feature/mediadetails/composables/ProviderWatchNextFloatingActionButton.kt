package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.annotation.VisibleForTesting
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationProductState
import com.axiel7.anihyou.release.core.navigation.WatchNextState

@VisibleForTesting
@Composable
fun ProviderWatchNextFloatingActionButton(
    navigationState: ProviderNavigationProductState,
    onClick: () -> Unit,
) {
    val candidate = navigationState.watchNext as? WatchNextState.Candidate
    if (navigationState.watchTarget != null && candidate != null && candidate.behindCount > 0) {
        ExtendedFloatingActionButton(
            onClick = { if (!navigationState.loading) onClick() },
        ) {
            Icon(
                painter = painterResource(R.drawable.play_arrow_24),
                contentDescription = stringResource(R.string.watch_next),
            )
            Text(
                text = stringResource(R.string.watch_next_behind_count, candidate.behindCount),
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            )
        }
    }
}

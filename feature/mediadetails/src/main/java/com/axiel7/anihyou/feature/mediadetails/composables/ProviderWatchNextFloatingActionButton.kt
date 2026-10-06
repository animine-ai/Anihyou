package com.axiel7.anihyou.feature.mediadetails.composables

import androidx.annotation.VisibleForTesting
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
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

@VisibleForTesting
@Composable
fun ProviderWatchNextFloatingActionButton(
    navigationState: ProviderNavigationProductState,
    onClick: () -> Unit,
) {
    val behindCount = navigationState.watchNextCount
    if (behindCount > 0) {
        ExtendedFloatingActionButton(
            onClick = { if (!navigationState.loading) onClick() },
            modifier = Modifier.testTag("provider-watch-next-fab"),
        ) {
            if (navigationState.loading) CircularProgressIndicator(Modifier.size(24.dp).testTag("provider-watch-next-loading"), strokeWidth = 2.dp)
            else Icon(
                painter = painterResource(R.drawable.play_arrow_24),
                contentDescription = stringResource(R.string.watch_next),
            )
            Text(
                text = behindCount.toString(),
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            )
        }
    }
}

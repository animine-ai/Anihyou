package com.axiel7.anihyou.ui.screens.main.composables

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.axiel7.anihyou.core.ui.common.BottomDestination
import com.axiel7.anihyou.core.ui.common.navigation.Navigator

@Composable
fun MainNavigationRail(
    navigator: Navigator,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    destinations: List<BottomDestination> = BottomDestination.values,
) {
    NavigationRail(modifier = modifier) {
        Column(modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            destinations.forEach { dest ->
                androidx.compose.runtime.key(dest.stableId) {
                    val isSelected = navigator.state.topLevelRoute == dest.route
                    val image = if (dest.animatedIcon) AnimatedImageVector.animatedVectorResource(dest.icon) else null
                    NavigationRailItem(
                        modifier = Modifier.testTag("rail-" + dest.stableId),
                        selected = isSelected,
                        onClick = {
                            onItemSelected(dest.index)
                            navigator.navigate(dest.route)
                        },
                        icon = {
                            Icon(
                                painter = if (image != null) rememberAnimatedVectorPainter(image, isSelected)
                                    else androidx.compose.ui.res.painterResource(dest.icon),
                                contentDescription = dest.displayTitle(),
                            )
                        },
                        label = { Text(dest.displayTitle(), textAlign = TextAlign.Center) },
                    )
                }
            }
        }
    }
}

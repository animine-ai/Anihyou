package com.axiel7.anihyou.feature.usermedialist

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * The collapsing search bar follows what the list really scrolled, and it consumes nothing: the list always scrolls under
 * the finger by itself. The bar of the enter-always behaviour took the drag first, and because the bar lay in a column above
 * the list, every step of it (and its settle animation after the finger had left) moved the whole list, together with the pull
 * to refresh, up and down.
 */
@OptIn(ExperimentalMaterial3Api::class)
class FollowListScrollBehavior(override val state: TopAppBarState) : TopAppBarScrollBehavior {
    override val isPinned: Boolean = false
    override val snapAnimationSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
    override val flingAnimationSpec: DecayAnimationSpec<Float>? = null

    override val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed.y != 0f) {
                state.contentOffset += consumed.y
                state.heightOffset = (state.heightOffset + consumed.y).coerceIn(state.heightOffsetLimit, 0f)
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val limit = state.heightOffsetLimit
            val offset = state.heightOffset
            if (limit < 0f && offset < 0f && offset > limit) {
                val target = if (offset > limit / 2f) 0f else limit
                AnimationState(offset).animateTo(target, snapAnimationSpec) { state.heightOffset = value }
            }
            return Velocity.Zero
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberFollowListScrollBehavior(): FollowListScrollBehavior {
    val state = rememberTopAppBarState()
    return remember(state) { FollowListScrollBehavior(state) }
}

/**
 * [bars] lie over [content]; the content fills the whole box and gets the full height of the bars as its top padding, so
 * the bars collapsing and coming back never change the size or position of the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingBarsLayout(
    scrollBehavior: TopAppBarScrollBehavior,
    bars: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (topPadding: Dp) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val barPlaceables = subcompose("bars", bars).map {
            it.measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        }
        // The bars are measured with the state of this frame, so the collapsed height plus the collapsed part is the same
        // number all the time.
        val collapsed = scrollBehavior.state.heightOffset.coerceAtMost(0f)
        val expanded = barPlaceables.sumOf { it.height } - collapsed.roundToInt()
        val topPadding = expanded.toDp()
        val contentPlaceables = subcompose("content") { content(topPadding) }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceables.forEach { it.placeRelative(0, 0) }
            barPlaceables.forEach { it.placeRelative(0, 0) }
        }
    }
}

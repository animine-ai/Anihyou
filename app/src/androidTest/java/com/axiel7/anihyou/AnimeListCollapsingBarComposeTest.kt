package com.axiel7.anihyou

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.model.NovelTab
import com.axiel7.anihyou.core.model.media.exampleCommonMediaListEntry
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.usermedialist.CollapsingBarsLayout
import com.axiel7.anihyou.feature.usermedialist.UserMediaListEvent
import com.axiel7.anihyou.feature.usermedialist.UserMediaListUiState
import com.axiel7.anihyou.feature.usermedialist.UserMediaListView
import com.axiel7.anihyou.feature.usermedialist.rememberFollowListScrollBehavior
import java.lang.reflect.Proxy
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The anime list with its collapsing search bar, dragged the way the video shows it: down the list, then slowly back up
 * and down again, and let go. The position of one row on the screen is sampled frame by frame. Two layouts are measured:
 * the bar in a column above the list (the old one: the bar moves the list) and the bar over the list (the new one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class AnimeListCollapsingBarComposeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun entry(id: Int): CommonMediaListEntry = exampleCommonMediaListEntry.copy(
        mediaId = id,
        media = exampleCommonMediaListEntry.media!!.copy(id = id),
    )

    private val noEvent = Proxy.newProxyInstance(
        UserMediaListEvent::class.java.classLoader, arrayOf(UserMediaListEvent::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NoEvent"
            else -> null
        }
    } as UserMediaListEvent

    /** One sample per frame: the finger movement of the frame and the screen y of every list row that is on the screen. */
    private class Frame(val label: String, val finger: Float, val rows: Map<Int, Float>)

    private fun drive(rowsNow: () -> Map<Int, Float>): List<Frame> {
        val frames = mutableListOf<Frame>()
        fun sample(label: String, finger: Float) { frames += Frame(label, finger, rowsNow()) }
        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(16) { moveBy(Offset(0f, -45f)); composeRule.mainClock.advanceTimeByFrame(); sample("up$it", -45f) }
            repeat(10) { moveBy(Offset(0f, 30f)); composeRule.mainClock.advanceTimeByFrame(); sample("back$it", 30f); composeRule.mainClock.advanceTimeByFrame(); sample("backwait$it", 0f) }
            repeat(10) { moveBy(Offset(0f, -30f)); composeRule.mainClock.advanceTimeByFrame(); sample("again$it", -30f); composeRule.mainClock.advanceTimeByFrame(); sample("againwait$it", 0f) }
            repeat(24) { moveBy(Offset(0f, 36f)); composeRule.mainClock.advanceTimeByFrame(); sample("down$it", 36f); composeRule.mainClock.advanceTimeByFrame(); sample("downwait$it", 0f) }
            up()
        }
        repeat(90) { composeRule.mainClock.advanceTimeByFrame(); sample("rest$it", 0f) }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        return frames
    }

    /**
     * Per phase of the drag (a frame may lag behind the finger by one, so whole phases are compared): the rows must move the
     * way the finger moved, never the other way. Returns the number of phases where they did, and the largest move of a row in
     * a frame without a finger move (only written to the log).
     */
    private fun report(name: String, frames: List<Frame>): Pair<Int, Float> {
        val stepsByPhase = linkedMapOf<String, Float>()
        val fingerByPhase = linkedMapOf<String, Float>()
        var worstAlone = 0f
        for (i in 1 until frames.size) {
            val common = frames[i].rows.keys.intersect(frames[i - 1].rows.keys).minOrNull() ?: continue
            val step = frames[i].rows.getValue(common) - frames[i - 1].rows.getValue(common)
            val phase = frames[i].label.replace(Regex("[0-9]+"), "").removeSuffix("wait")
            stepsByPhase[phase] = (stepsByPhase[phase] ?: 0f) + step
            fingerByPhase[phase] = (fingerByPhase[phase] ?: 0f) + frames[i].finger
            if (frames[i].finger == 0f && abs(step) > worstAlone) worstAlone = abs(step)
            Log.i("AnimeListBar", "$name ${frames[i].label} row=$common step=$step finger=${frames[i].finger}")
        }
        var wrongWay = 0
        stepsByPhase.forEach { (phase, steps) ->
            val finger = fingerByPhase.getValue(phase)
            val against = finger != 0f && abs(steps) > 2f && (steps > 0f) != (finger > 0f)
            if (against) wrongWay++
            Log.i("AnimeListBar", "$name phase=$phase rows=$steps finger=$finger wrongWay=$against")
        }
        Log.i("AnimeListBar", "$name reversals=$wrongWay largestMoveWithoutFinger=$worstAlone")
        return wrongWay to worstAlone
    }

    private fun measure(barOver: Boolean): Pair<Int, Float> {
        val listState = LazyListState()
        var containerTop = 0f
        composeRule.setContent {
            val navigation = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            val navigator = remember(navigation) { Navigator(navigation) }
            val entries = remember { mutableStateListOf<CommonMediaListEntry>().apply { addAll((1..40).map(::entry)) } }
            val state = remember { UserMediaListUiState(mediaType = MediaType.ANIME, isLoading = false, entries = entries) }
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme {
                    if (barOver) {
                        val behavior = rememberFollowListScrollBehavior()
                        val revealed by remember { derivedStateOf { behavior.state.heightOffset > -0.5f } }
                        Scaffold { _ ->
                            CollapsingBarsLayout(
                                scrollBehavior = behavior,
                                bars = { TopAppBar(title = { Text("Suche") }, scrollBehavior = behavior) },
                            ) { topPadding ->
                                Box(Modifier.fillMaxSize().onGloballyPositioned { containerTop = it.boundsInRoot().top }) {
                                    UserMediaListView(
                                        uiState = state, event = noEvent, isCompactScreen = true,
                                        navActionManager = LocalNavActionManager.current,
                                        defaultNovelTab = NovelTab.MANGA, onShowEditSheet = {},
                                        lazyListState = listState, lazyGridState = LazyGridState(),
                                        stickyHeaderContent = {},
                                        barScrollConnection = behavior.nestedScrollConnection, pullEnabled = revealed,
                                        contentPadding = PaddingValues(top = topPadding, bottom = 58.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
                        val revealed by remember { derivedStateOf { behavior.state.heightOffset > -0.5f } }
                        Scaffold(topBar = { TopAppBar(title = { Text("Suche") }, scrollBehavior = behavior) }) { padding ->
                            Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize().onGloballyPositioned { containerTop = it.boundsInRoot().top }) {
                                UserMediaListView(
                                    uiState = state, event = noEvent, isCompactScreen = true,
                                    navActionManager = LocalNavActionManager.current,
                                    defaultNovelTab = NovelTab.MANGA, onShowEditSheet = {},
                                    lazyListState = listState, lazyGridState = LazyGridState(),
                                    stickyHeaderContent = {},
                                    barScrollConnection = behavior.nestedScrollConnection, pullEnabled = revealed,
                                    contentPadding = PaddingValues(bottom = 58.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val frames = drive {
            listState.layoutInfo.visibleItemsInfo.filter { it.key is Int }
                .associate { (it.key as Int) to containerTop + it.offset }
        }
        return report(if (barOver) "barOver" else "barAbove", frames)
    }

    @Test fun barAboveTheListIsMeasuredForComparison() {
        val (reversals, alone) = measure(barOver = false)
        Log.i("AnimeListBar", "barAbove summary reversals=$reversals moveWithoutFinger=$alone (not asserted: the old layout)")
    }

    @Test fun barOverTheListNeverMovesTheListAgainstTheFinger() {
        val (reversals, _) = measure(barOver = true)
        assertTrue("the list went against the finger in $reversals phases of the drag", reversals == 0)
    }
}

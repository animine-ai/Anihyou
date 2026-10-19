package com.axiel7.anihyou

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.center
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.model.media.exampleCommonMediaListEntry
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.navigation.*
import com.axiel7.anihyou.feature.usermedialist.UserMediaListEvent
import com.axiel7.anihyou.feature.usermedialist.UserMediaListUiState
import com.axiel7.anihyou.feature.usermedialist.UserMediaListView
import com.axiel7.anihyou.core.model.NovelTab
import java.lang.reflect.Proxy
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The anime list while a finger pulls it down and the list reloads (the video of the Pixel: "rutscht ganz nach unten aus dem Bild").
 * The search bar is the first row of the list; the test samples its position (and the first rows) frame by frame and writes the
 * largest displacement to the log. The list must not move at all: the pull only shows the loading mark above it.
 */
@RunWith(AndroidJUnit4::class)
class AnimeListPullRefreshComposeTest {
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

    @Test fun pullingAndReloadingDoesNotMoveTheList() {
        var state by mutableStateOf(
            UserMediaListUiState(
                mediaType = MediaType.ANIME, isLoading = false,
                entries = mutableStateListOf<CommonMediaListEntry>().apply { addAll((1..12).map(::entry)) },
            ),
        )
        var barTop = Float.NaN
        lateinit var listState: LazyListState
        composeRule.setContent {
            val navigation = rememberNavigationState(Route.Home, MainNavigationResolver.allRoutes)
            val navigator = remember(navigation) { Navigator(navigation) }
            listState = androidx.compose.foundation.lazy.rememberLazyListState()
            CompositionLocalProvider(LocalNavActionManager provides NavActionManager(navigator)) {
                MaterialTheme {
                    UserMediaListView(
                        uiState = state, event = noEvent, isCompactScreen = true,
                        navActionManager = LocalNavActionManager.current,
                        defaultNovelTab = NovelTab.MANGA,
                        onShowEditSheet = {},
                        lazyListState = listState, lazyGridState = LazyGridState(),
                        stickyHeaderContent = {},
                        topContent = {
                            Box(Modifier.height(56.dp).testTag("search-bar").onGloballyPositioned { barTop = it.boundsInRoot().top }) {
                                Text("Suche")
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val rest = barTop
        val restRow = listState.layoutInfo.visibleItemsInfo.map { it.key to it.offset }
        var worst = 0f
        fun sample(label: String) {
            val moved = abs(barTop - rest)
            val rows = listState.layoutInfo.visibleItemsInfo.joinToString { "${it.key}@${it.offset}" }
            Log.i("AnimeListPull", "$label barTop=$barTop rest=$rest rows=$rows")
            if (moved > worst) worst = moved
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("search-bar").performTouchInput {
            down(center)
            repeat(14) { moveBy(androidx.compose.ui.geometry.Offset(0f, 60f)); composeRule.mainClock.advanceTimeByFrame(); sample("drag$it") }
        }
        // the reload starts while the finger is still down: the list is replaced by a new one
        composeRule.runOnIdle { }
        state = state.copy(
            fetchFromNetwork = true,
            entries = mutableStateListOf<CommonMediaListEntry>().apply { addAll((5..20).map(::entry)) },
        )
        repeat(60) { composeRule.mainClock.advanceTimeByFrame(); sample("reload$it") }
        composeRule.onNodeWithTag("search-bar").performTouchInput { up() }
        state = state.copy(fetchFromNetwork = false)
        repeat(90) { composeRule.mainClock.advanceTimeByFrame(); sample("after$it") }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        Log.i("AnimeListPull", "worst displacement of the search bar: $worst px (rest $rest, rows at rest $restRow)")
        assertTrue("the list moved by $worst px while it was pulled and reloaded", worst <= 1.5f)
    }
}

package com.axiel7.anihyou.feature.usermedialist

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.network.UserListCollectionQuery
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.ui.common.navigation.Route
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class UserMediaListReleaseObservationTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun changingAndClearingSearchPublishesTheActualVisibleReleaseIds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = Harness()
        val viewModel = harness.viewModel()
        awaitState { !viewModel.uiState.value.isLoading }

        harness.results.value = PagedResult.Success(
            listOf(collection(entry(1, "Alpha"), entry(2, "Beta"))), 1, false,
        )
        awaitState { harness.observedIds.lastOrNull() == setOf(1, 2) }

        viewModel.setQuery("Alpha")
        awaitState {
            viewModel.uiState.value.entries.map { it.mediaId } == listOf(1) &&
                harness.observedIds.lastOrNull() == setOf(1)
        }
        viewModel.setQuery("")
        awaitState {
            viewModel.uiState.value.entries.map { it.mediaId }.toSet() == setOf(1, 2) &&
                harness.observedIds.lastOrNull() == setOf(1, 2)
        }
    }

    @Test
    fun subsequentCollectionPageIsAppendedOnceWithoutRecursiveStateUpdates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val harness = Harness()
        val viewModel = harness.viewModel()
        awaitState { !viewModel.uiState.value.isLoading }
        harness.results.value = PagedResult.Success(listOf(collection(entry(1, "Alpha"))), 1, true)
        awaitState { harness.observedIds.lastOrNull() == setOf(1) }

        // This screen requests an unchunked collection today. Exercise the supported
        // PagedResult boundary explicitly so a future chunked caller cannot regress.
        harness.results.value = PagedResult.Success(listOf(collection(entry(2, "Beta"))), 2, false)
        awaitState { harness.observedIds.lastOrNull() == setOf(1, 2) }
        assertEquals(listOf(1, 2), viewModel.uiState.value.lists["CURRENT"]!!.map { it.mediaId })
        assertEquals(2, viewModel.uiState.value.filteredEntriesCache.size)
    }

    private suspend fun TestScope.awaitState(condition: () -> Boolean) {
        // The production view model computes filtering/sorting on Dispatchers.Default.
        // Wait on a real dispatcher while draining the controlled Main dispatcher.
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                while (true) {
                    testScheduler.advanceTimeBy(50)
                    testScheduler.runCurrent()
                    // The view model replaces its snapshot lists on Dispatchers.Default while this poll reads them; a read that
                    // meets a replacement in flight is simply taken again on the next round.
                    val met = try { condition() } catch (_: ConcurrentModificationException) { false }
                    if (met) return@withTimeout
                    delay(10)
                }
            }
        }
    }

    private fun entry(id: Int, title: String) = mockk<CommonMediaListEntry>(relaxed = true).also {
        every { it.mediaId } returns id
        every { it.media?.title?.romaji } returns title
        every { it.media?.title?.english } returns title
        every { it.media?.title?.native } returns title
        every { it.media?.synonyms } returns emptyList()
    }

    private fun collection(vararg entries: CommonMediaListEntry) =
        mockk<UserListCollectionQuery.List>().also { list ->
            every { list.name } returns "CURRENT"
            every { list.isCustomList } returns false
            every { list.entries } returns entries.map { entry ->
                mockk<UserListCollectionQuery.Entry>().also {
                    every { it.commonMediaListEntry } returns entry
                }
            }
        }

    private class Harness {
        val results = MutableStateFlow<PagedResult<UserListCollectionQuery.List?>>(
            PagedResult.Success(emptyList(), 1, false),
        )
        val observedIds = CopyOnWriteArrayList<Set<Int>>()
        private val releases = mockk<ReleasePresentationRepository>().also {
            every { it.observeForMedia(any(), any()) } answers {
                observedIds += arg<Set<Int>>(1).toSet()
                flowOf(emptyMap())
            }
        }
        private val repository = mockk<MediaListRepository>().also {
            every { it.getMediaListCollection(any(), any(), any(), any(), any(), any()) } returns results
        }
        private val preferences = mockk<DefaultPreferencesRepository>().also {
            every { it.userId } returns flowOf(4242)
            every { it.titleLanguage } returns emptyFlow()
            every { it.scoreFormat } returns emptyFlow()
            every { it.showLowPriority } returns emptyFlow()
            every { it.colorLowPriority } returns emptyFlow()
            every { it.colorMediumPriority } returns emptyFlow()
            every { it.colorHighPriority } returns emptyFlow()
            every { it.useFuzzySearch } returns flowOf(false)
            every { it.translatorApp } returns emptyFlow()
            every { it.animeLists } returns emptyFlow()
        }
        private val listPreferences = mockk<ListPreferencesRepository>().also {
            every { it.animeListSelected } returns flowOf(null)
            every { it.useGeneralListStyle } returns flowOf(true)
            every { it.generalListStyle } returns flowOf(ListStyle.STANDARD)
            every { it.gridItemsPerRow } returns emptyFlow()
            every { it.animeListSort } returns emptyFlow()
        }

        fun viewModel() = UserMediaListViewModel(
            Route.UserMediaList(mediaType = "ANIME", userId = 4242),
            repository, preferences, listPreferences, releases,
        )
    }
}

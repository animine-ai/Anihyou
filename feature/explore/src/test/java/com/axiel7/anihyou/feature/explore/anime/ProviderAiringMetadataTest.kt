package com.axiel7.anihyou.feature.explore.anime

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderAiringMetadataTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AnimeExploreViewModel>()
    private val rows = MutableStateFlow<List<ReleaseUiCalendarItem>>(emptyList())
    private val user = MutableStateFlow<Int?>(1)
    private val onList = MutableStateFlow(false)
    private val media = mockk<MediaRepository>()
    private val requests = mutableListOf<List<Int>>()
    private var fetch: (List<Int>) -> Flow<PagedResult<ExploreMedia>> = { ids -> success(ids) }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun success(ids: List<Int>) = flowOf<PagedResult<ExploreMedia>>(PagedResult.Success(
        ids.map { id -> mockk<ExploreMedia>(relaxed = true) { every { this@mockk.id } returns id } }, 1, false,
    ))

    private fun model(): AnimeExploreViewModel {
        val preferences = mockk<DefaultPreferencesRepository> {
            every { userId } returns user
            every { airingOnMyList } returns onList
            every { displayAdult } returns flowOf(false)
        }
        val release = mockk<ReleasePresentationRepository> {
            every { observeCalendar(any(), any()) } returns rows
            every { observeForMedia(any(), any()) } returns flowOf(emptyMap())
        }
        every { media.getMediaByIdsPage(any(), 1, 50) } answers {
            firstArg<List<Int>>().also { requests += it }.let(fetch)
        }
        every { media.getAiringAnimesPage(any(), any(), any(), any(), any(), any(), any(), any()) } returns flowOf(PagedResult.Success(emptyList(), 1, false))
        every { media.getSeasonalAnimePage(any(), any(), any(), any(), any()) } returns flowOf(PagedResult.Success(emptyList(), 1, false))
        every { media.getMediaSortedPage(any(), any(), any(), any(), any(), any()) } returns flowOf(PagedResult.Success(emptyList(), 1, false))
        return AnimeExploreViewModel(media, preferences, release, Clock.fixed(Instant.parse("2026-10-06T14:00:00Z"), ZoneOffset.UTC))
            .also { models += it }
    }

    @Test fun allTenMappedSourceTitlesAreLoadedWithoutAnAniListAiringPage() = runTest(dispatcher) {
        val vm = model()
        rows.value = (1..10).map(::row)
        advanceTimeBy(251); runCurrent()
        assertEquals((1..10).toSet(), vm.uiState.value.providerAiringMedia.keys)
        assertEquals(listOf((1..10).toList()), requests)
        assertTrue(vm.uiState.value.airingAnime.isEmpty())
        assertFalse(vm.uiState.value.isLoadingProviderAiring)
    }

    @Test fun fiftyOneIdsAreBatchedAndRepeatedTracksDoNotDuplicateRequests() = runTest(dispatcher) {
        val vm = model()
        rows.value = (1..51).map(::row) + listOf(row(1).copy(stream = row(1).stream.copy(languageTrack = LanguageTrack.DE_DUB)))
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf(50, 1), requests.map { it.size })
        assertEquals(51, vm.uiState.value.providerAiringMedia.size)
    }

    @Test fun filterAndSourceRevisionChangesKeepLoadedTitlesWithoutRefetching() = runTest(dispatcher) {
        val vm = model(); rows.value = listOf(row(7)); advanceTimeBy(251); runCurrent()
        onList.value = true
        rows.value = listOf(row(7).copy(revision = 2))
        advanceTimeBy(251); runCurrent()
        assertEquals(setOf(7), vm.uiState.value.providerAiringMedia.keys)
        assertEquals(1, requests.size)
    }

    @Test fun newMappingCancelsObsoleteRequestAndDoesNotCommitItsMetadata() = runTest(dispatcher) {
        var cancelled = false
        fetch = { ids -> if (ids == listOf(7)) flow {
            try { emit(PagedResult.Loading); awaitCancellation() } finally { cancelled = true }
        } else success(ids) }
        val vm = model(); rows.value = listOf(row(7)); advanceTimeBy(251); runCurrent()
        assertTrue(vm.uiState.value.isLoadingProviderAiring)
        rows.value = listOf(row(8)); advanceTimeBy(251); runCurrent()
        assertTrue(cancelled)
        assertEquals(setOf(8), vm.uiState.value.providerAiringMedia.keys)
        assertFalse(vm.uiState.value.isLoadingProviderAiring)
    }

    @Test fun logoutClearsOldMembershipAndFetchesTheSameIdsForTheNewAccount() = runTest(dispatcher) {
        val vm = model(); rows.value = listOf(row(7)); advanceTimeBy(251); runCurrent()
        val old = vm.uiState.value.providerAiringMedia[7]
        user.value = null; advanceTimeBy(251); runCurrent()
        assertEquals(2, requests.size)
        assertNotSame(old, vm.uiState.value.providerAiringMedia[7])
    }

    @Test fun errorCanBeRetriedByRefreshAndDoesNotBecomeFakeMetadata() = runTest(dispatcher) {
        fetch = { flowOf(PagedResult.Error("unavailable")) }
        val vm = model(); rows.value = listOf(row(7)); advanceTimeBy(251); runCurrent()
        assertTrue(vm.uiState.value.providerAiringMedia.isEmpty())
        assertFalse(vm.uiState.value.isLoadingProviderAiring)
        fetch = ::success
        vm.refresh(); advanceTimeBy(251); runCurrent()
        assertEquals(setOf(7), vm.uiState.value.providerAiringMedia.keys)
        assertEquals(2, requests.size)
    }

    @Test fun matcherBindingBurstLoadsAllTitlesInOneRequest() = runTest(dispatcher) {
        val vm = model()
        (1..37).forEach { last ->
            rows.value = (1..last).map(::row)
            runCurrent()
            advanceTimeBy(20)
        }
        assertTrue("intermediate mapping commits must not each start a request", requests.isEmpty())
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf((1..37).toList()), requests)
        assertEquals((1..37).toSet(), vm.uiState.value.providerAiringMedia.keys)
    }

    @Test fun accountChangeClearsOldMetadataBeforeTheCoalescingDelay() = runTest(dispatcher) {
        val vm = model(); rows.value = listOf(row(7)); advanceTimeBy(251); runCurrent()
        assertEquals(setOf(7), vm.uiState.value.providerAiringMedia.keys)
        user.value = 2; runCurrent()
        assertTrue(vm.uiState.value.providerAiringMedia.isEmpty())
        advanceTimeBy(251); runCurrent()
        assertEquals(setOf(7), vm.uiState.value.providerAiringMedia.keys)
        assertEquals(2, requests.size)
    }

    @Test fun noSourceRowsNeverAddsMetadataRequestsToTheAniListPath() = runTest(dispatcher) {
        val vm = model(); advanceTimeBy(251); runCurrent()
        assertTrue(requests.isEmpty())
        assertTrue(vm.uiState.value.providerAiringMedia.isEmpty())
    }

    private fun row(id: Int) = ReleaseUiCalendarItem(
        mediaId = id, stream = ReleaseStreamKey(ProviderId("test-provider"), SourceSeriesKey("series-$id"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB), installment = Installment.Episode(23),
        forecastAt = Instant.parse("2026-10-06T15:10:00Z"), confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = java.time.LocalDate.of(2026, 10, 6),
        sourceRoot = "https://example.test", revision = 1,
    )
}

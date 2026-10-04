package com.axiel7.anihyou.feature.home.current

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.media.exampleBasicMediaListEntry
import com.axiel7.anihyou.core.model.media.exampleCommonMediaListEntry
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiFreshness
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behind on Home follows the confirmed source state and the user's progress. Fixed clock 2026-10-04T12:00:00Z; the inputs
 * contradict each other: AniList names episode 12 as the next one, the accepted source confirms episode 10.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentBehindProjectionTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun entry(mediaId: Int, progress: Int): CommonMediaListEntry = exampleCommonMediaListEntry.copy(
        mediaId = mediaId, id = mediaId,
        media = exampleCommonMediaListEntry.media!!.copy(
            id = mediaId, status = MediaStatus.RELEASING,
            nextAiringEpisode = CommonMediaListEntry.NextAiringEpisode(
                __typename = "", episode = 12, timeUntilAiring = 86_400, id = 0),
        ),
        basicMediaListEntry = exampleBasicMediaListEntry.copy(mediaId = mediaId, id = mediaId, progress = progress),
    )

    private fun confirmedThrough(mediaId: Int, episode: Int, authority: ReleaseUiAuthority = ReleaseUiAuthority.VALID) =
        ReleaseUiPresentation(
            mediaId = mediaId,
            stream = ReleaseStreamKey(ProviderId("source"), SourceSeriesKey("/s/$mediaId"), ReleaseKind.EPISODE, 1,
                LanguageTrack.DE_SUB),
            authority = authority, confirmedThroughEpisode = episode,
            confirmedInstallments = (1..episode).map { Installment.Episode(it) },
            // A stored count that disagrees on purpose: it must never be read.
            confirmedPending = 99, nextExpectedInstallment = null, nextForecast = null,
            freshness = ReleaseUiFreshness.UNKNOWN, sourceRoot = null, revision = 1,
        )

    private fun preferences(): DefaultPreferencesRepository = mockk {
        every { userId } returns flowOf(4242)
        every { scoreFormat } returns flowOf(ScoreFormat.POINT_10_DECIMAL)
        every { showLowPriority } returns flowOf(false)
        every { colorLowPriority } returns flowOf(0xFF7CB342.toInt())
        every { colorMediumPriority } returns flowOf(0xFFF4D03F.toInt())
        every { colorHighPriority } returns flowOf(0xFFD84315.toInt())
        every { scoreSteps } returns flowOf(1.0)
    }

    @Test fun confirmedSourceStateAndProgressDecideBehindAndAProgressChangeReprojectsLocally() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val behind = entry(mediaId = 7, progress = 8)      // source: 10 - 8 = 2 behind
        val caughtUp = entry(mediaId = 8, progress = 10)   // source: 0 behind, although AniList says 12 is next
        val noSource = entry(mediaId = 9, progress = 5)    // no presentation: AniList rule, 5 < 11 behind
        val releases = mockk<ReleasePresentationRepository>()
        every { releases.observeForMedia(any(), any()) } returns flowOf(mapOf(
            7 to listOf(confirmedThrough(7, 10)),
            8 to listOf(confirmedThrough(8, 10)),
        ))
        val animeResults = MutableStateFlow<PagedResult<CommonMediaListEntry>>(
            PagedResult.Success(emptyList(), currentPage = 1, hasNextPage = false))
        val lastUpdated = MutableStateFlow<BasicMediaListEntry?>(null)
        val repository = mockk<MediaListRepository>()
        every { repository.lastUpdatedEntry } returns lastUpdated
        every { repository.getUserMediaList(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<MediaType>(1) == MediaType.ANIME) animeResults
            else flowOf(PagedResult.Success(emptyList(), currentPage = 1, hasNextPage = false))
        }
        every { repository.getMySeasonalAnime(any(), any(), any(), any(), any()) } returns
            flowOf(PagedResult.Success(emptyList(), currentPage = 1, hasNextPage = false))

        val viewModel = CurrentViewModel(repository, preferences(), releases,
            Clock.fixed(now, ZoneOffset.UTC))
        advanceUntilIdle()
        animeResults.value = PagedResult.Success(listOf(behind, caughtUp, noSource), currentPage = 1, hasNextPage = false)
        advanceUntilIdle()

        assertEquals("source state wins over AniList where it is valid; most unseen first, the stored 99 is never read",
            listOf(9, 7), viewModel.uiState.value.behindList.map { it.mediaId })
        assertEquals(listOf(8), viewModel.uiState.value.airingList.map { it.mediaId })

        // The user watches episodes 9 and 10 elsewhere: Home reprojects from the entry, no source refresh happens.
        lastUpdated.value = behind.basicMediaListEntry.copy(progress = 10)
        advanceUntilIdle()
        assertEquals(listOf(9), viewModel.uiState.value.behindList.map { it.mediaId })
        assertEquals(listOf(7, 8), viewModel.uiState.value.airingList.map { it.mediaId }.sorted())

        // Undo (or another device sets it back to 8): the same two unseen episodes are behind again, no source refresh.
        lastUpdated.value = behind.basicMediaListEntry.copy(progress = 8)
        advanceUntilIdle()
        assertEquals(listOf(9, 7), viewModel.uiState.value.behindList.map { it.mediaId })
        assertEquals(listOf(8), viewModel.uiState.value.airingList.map { it.mediaId })
    }
}

package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.network.UserListCollectionQuery
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListLibraryCandidateLoaderTest {
    private val now = Instant.parse("2026-10-05T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val today = LocalDate.ofInstant(now, ZoneId.systemDefault())

    private fun entry(
        id: Int, status: MediaListStatus, airing: MediaStatus, year: Int, romaji: String, english: String? = null,
        synonyms: List<String?> = emptyList(),
    ) = mockk<UserListCollectionQuery.Entry> {
        every { commonMediaListEntry } returns mockk<CommonMediaListEntry>(relaxed = true) {
            every { this@mockk.mediaId } returns id
            every { basicMediaListEntry.status } returns status
            every { media!!.status } returns airing
            every { media!!.format } returns MediaFormat.TV
            every { media!!.startDate!!.year } returns year
            every { media!!.title!!.romaji } returns romaji
            every { media!!.title!!.english } returns english
            every { media!!.title!!.native } returns null
            every { media!!.synonyms } returns synonyms
            every { media!!.basicMediaDetails.title } returns null
        }
    }

    private fun repository(entries: List<UserListCollectionQuery.Entry>) = mockk<MediaListRepository> {
        val list = mockk<UserListCollectionQuery.List> { every { this@mockk.entries } returns entries }
        every { getMediaListCollection(any(), any(), any(), any(), any(), any()) } returns
            flowOf(PagedResult.Success(listOf(list), 1, false))
    }

    private fun preferences(userId: Int?) = mockk<DefaultPreferencesRepository> { every { this@mockk.userId } returns flowOf(userId) }

    @Test fun theListGivesEveryTitleAndDatesAReleasingShowToday() = runBlocking {
        val media = repository(listOf(
            entry(21, MediaListStatus.CURRENT, MediaStatus.RELEASING, 1999, "ONE PIECE", synonyms = listOf("OP", null)),
            entry(22, MediaListStatus.COMPLETED, MediaStatus.FINISHED, 2023, "Yamada-kun to Lv999 no Koi wo Suru",
                english = "My Love Story with Yamada-kun at Lv999"),
            entry(23, MediaListStatus.DROPPED, MediaStatus.FINISHED, 2020, "A dropped show"),
            entry(21, MediaListStatus.CURRENT, MediaStatus.RELEASING, 1999, "ONE PIECE"),
        ))
        val loaded = AniListLibraryCandidateLoader(media, preferences(7), clock).load().associateBy { it.mediaId }

        assertEquals("a dropped show and a repeat from a custom list are left out", setOf(21, 22), loaded.keys)
        assertEquals(setOf("ONE PIECE", "OP"), loaded.getValue(21).titles)
        assertEquals("a show that releases now airs now, whatever year it began", today, loaded.getValue(21).startDate)
        assertEquals(LocalDate.of(2023, 1, 1), loaded.getValue(22).startDate)
        assertTrue(loaded.getValue(22).titles.contains("My Love Story with Yamada-kun at Lv999"))
        assertEquals("TV", loaded.getValue(22).format)
    }

    @Test fun theListIsReadOnceForAFewMinutesAndNeverWithoutAnAccount() = runBlocking {
        val media = repository(listOf(entry(21, MediaListStatus.CURRENT, MediaStatus.RELEASING, 1999, "ONE PIECE")))
        val loader = AniListLibraryCandidateLoader(media, preferences(7), clock)
        loader.load()
        loader.load()
        verify(exactly = 1) { media.getMediaListCollection(any(), any(), any(), any(), any(), any()) }

        val none = repository(emptyList())
        assertEquals(emptyList<Int>(), AniListLibraryCandidateLoader(none, preferences(null), clock).load().map { it.mediaId })
        verify(exactly = 0) { none.getMediaListCollection(any(), any(), any(), any(), any(), any()) }
    }
}

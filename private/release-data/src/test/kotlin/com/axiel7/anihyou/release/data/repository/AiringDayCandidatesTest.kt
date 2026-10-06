package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.domain.repository.SearchRepository
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.type.MediaFormat
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiringDayCandidatesTest {
    private lateinit var database: ReleaseDatabase
    private val day = LocalDate.of(2026, 10, 9)

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ReleaseDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { database.close() }

    private fun event(mediaId: Int, vararg titles: String): CalendarAiringEvent {
        val media = mockk<ExploreMedia>(relaxed = true) {
            every { this@mockk.id } returns mediaId
            every { basicMediaDetails.format } returns MediaFormat.ONA
        }
        return CalendarAiringEvent(mediaId * 10, 1, 1_760_000_000, media, titles.toSet())
    }

    private fun repository(events: List<CalendarAiringEvent>) = mockk<MediaRepository> {
        every {
            getCalendarAiringEventsPage(any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(PagedResult.Success(events, 1, false))
    }

    @Test fun aDayThatWasReadToItsEndIsKeptUntilItExpires() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-05T06:00:00Z"))
        val media = repository(listOf(event(71, "Magical Explorer"), event(72, "Another Show")))
        val source = AniListIdentityCandidateSource(mockk<SearchRepository>(), RoomIdentityCandidateStore(database, clock), clock, media)

        val first = source.airingCandidates(day)
        assertEquals(listOf(71, 72), first.map { it.mediaId })
        assertEquals("ONA", first.first().format)
        assertEquals(day, first.first().startDate)

        val again = source.airingCandidates(day)
        assertEquals("the second run reads the kept rows", first.map { it.mediaId to it.titles }, again.map { it.mediaId to it.titles })
        verify(exactly = 1) { media.getCalendarAiringEventsPage(any(), any(), any(), any(), any(), any(), any(), any()) }

        clock.now = clock.now.plusSeconds(4 * 3_600)
        source.airingCandidates(day)
        verify(exactly = 2) { media.getCalendarAiringEventsPage(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun anEmptyDayIsAnAnswerToo() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-05T06:00:00Z"))
        val media = repository(emptyList())
        val source = AniListIdentityCandidateSource(mockk<SearchRepository>(), RoomIdentityCandidateStore(database, clock), clock, media)
        assertEquals(emptyList<Int>(), source.airingCandidates(day).map { it.mediaId })
        assertEquals(emptyList<Int>(), source.airingCandidates(day).map { it.mediaId })
        verify(exactly = 1) { media.getCalendarAiringEventsPage(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}

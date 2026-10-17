package com.axiel7.anihyou.feature.calendar.composables

import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.feature.calendar.CalendarRow
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** One rule marks every calendar style: dimmed when the extension's planned time has passed, checked when proven, both when both. */
class ReleaseMarkTest {
    private val now = Instant.parse("2026-10-17T16:00:00Z")

    private fun item(at: Instant?, confirmed: Boolean, track: LanguageTrack = LanguageTrack.DE_SUB) = ReleaseUiCalendarItem(
        mediaId = 7,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/example"), ReleaseKind.EPISODE, 1, track),
        installment = Installment.Episode(1), forecastAt = at, confirmed = confirmed, authority = ReleaseUiAuthority.VALID,
        sourceDate = LocalDate.of(2026, 10, 17), sourceRoot = null, revision = 1,
    )

    private fun providerRow(vararg items: ReleaseUiCalendarItem) = CalendarRow("k", null, items.toList())

    private fun aniListRow(airingAt: Instant): CalendarRow {
        val media = mockk<ExploreMedia>(relaxed = true) { every { id } returns 5 }
        return CalendarRow("a", media, emptyList(), CalendarAiringEvent(1, 1, airingAt.epochSecond.toInt(), media))
    }

    @Test fun aPlannedTimeInTheFutureIsUpcoming() {
        assertEquals(ReleaseMark.UPCOMING, providerRow(item(now.plus(Duration.ofHours(1)), false)).releaseMark(now))
    }

    @Test fun aPlannedTimeThatHasPassedWithoutProofIsTheoreticalAndDimmed() {
        val row = providerRow(item(now.minus(Duration.ofMinutes(5)), false))
        assertEquals(ReleaseMark.THEORETICAL, row.releaseMark(now))
        assertEquals(ReleaseMark.THEORETICAL, providerRow(item(now, false)).releaseMark(now))
    }

    @Test fun anEpisodeTheSourceListsAfterItsTimeIsCheckedAndDimmed() {
        assertEquals(ReleaseMark.CONFIRMED_PAST, providerRow(item(now.minus(Duration.ofHours(2)), true)).releaseMark(now))
        assertEquals(ReleaseMark.CONFIRMED_PAST, providerRow(item(now, true)).releaseMark(now))
    }

    @Test fun anEpisodeTheSourceListsBeforeItsTimeIsCheckedButNotDimmed() {
        assertEquals(ReleaseMark.CONFIRMED, providerRow(item(now.plus(Duration.ofMinutes(1)), true)).releaseMark(now))
    }

    @Test fun aRowWithoutAPlannedTimeIsNeverDimmed() {
        assertEquals(ReleaseMark.UPCOMING, providerRow(item(null, false)).releaseMark(now))
    }

    @Test fun inACombinedRowOnlyTheSubDecidesNeverAReleasedDubBesideAPlannedSub() {
        val sub = item(now.minus(Duration.ofMinutes(5)), false, LanguageTrack.DE_SUB)
        val dub = item(now.minus(Duration.ofMinutes(5)), true, LanguageTrack.DE_DUB)
        assertEquals(ReleaseMark.THEORETICAL, providerRow(sub, dub).releaseMark(now))
    }

    @Test fun anAniListAiringTimeAloneNeverDimsOrChecks() {
        assertEquals(ReleaseMark.UPCOMING, aniListRow(now.plus(Duration.ofMinutes(30))).releaseMark(now))
        assertEquals(ReleaseMark.UPCOMING, aniListRow(now.minus(Duration.ofMinutes(30))).releaseMark(now))
    }

    @Test fun aRowWithNeitherSourceNorAiringTimeIsUpcoming() {
        assertEquals(ReleaseMark.UPCOMING, CalendarRow("x", null, emptyList()).releaseMark(now))
    }
}

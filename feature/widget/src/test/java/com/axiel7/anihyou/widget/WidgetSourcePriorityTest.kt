package com.axiel7.anihyou.widget

import com.axiel7.anihyou.core.model.media.exampleAiringWidgetEntry
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WidgetSourcePriorityTest {
    private fun row(mediaId: Int?) = ReleaseUiCalendarItem(mediaId = mediaId,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("/anime/stream/series"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB), installment = Installment.Episode(1),
        forecastAt = Instant.parse("2026-10-04T12:00:00Z"), confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = LocalDate.of(2026,10,4), sourceRoot = null, revision = 1)
    @Test fun sourcePriorityKeepsAniListOnlyMediaAndDoesNotDuplicateCoveredMedia() {
        val original = listOf(exampleAiringWidgetEntry, exampleAiringWidgetEntry.copy(id = 2))
        val items = mergeWidgetAiringItems(original, listOf(row(1), row(1)))
        assertEquals(2, items.size)
        assertEquals(listOf(1, 2), items.map { it.media!!.id })
        assertEquals(1, items[0].releaseRows.size)
        assertTrue(items[1].releaseRows.isEmpty())
    }
    @Test fun removedOrNonAuthoritativeSourceRestoresBothOriginalMedia() {
        val original = listOf(exampleAiringWidgetEntry, exampleAiringWidgetEntry.copy(id = 2))
        assertEquals(listOf(1, 2), mergeWidgetAiringItems(original, emptyList()).map { it.media!!.id })
        assertTrue(mergeWidgetAiringItems(original, listOf(row(1).copy(authority = ReleaseUiAuthority.AMBIGUOUS)))
            .all { it.releaseRows.isEmpty() })
    }
}

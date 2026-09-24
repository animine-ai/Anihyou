package com.axiel7.anihyou.release.core

import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReleaseUiCalendarItemIdentityTest {
    @Test
    fun eventIdentityDoesNotCollapseSameMediaTracksOrInstallments() {
        val stream = ReleaseStreamKey(
            providerId = ProviderId("aniworld"),
            stableSeriesKey = SourceSeriesKey("same-media"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        )
        val first = item(stream, Installment.Episode(1), mediaId = 7)
        val second = item(stream, Installment.Episode(2), mediaId = 7)
        val otherTrack = item(
            stream.copy(languageTrack = LanguageTrack.DE_DUB),
            Installment.Episode(1),
            mediaId = 7,
        )
        val unmapped = item(stream, Installment.Special(1), mediaId = null)

        assertNotEquals(first.eventKey, second.eventKey)
        assertNotEquals(first.eventKey, otherTrack.eventKey)
        assertNotEquals(second.eventKey, unmapped.eventKey)
        assertEquals(first.eventKey, first.copy(revision = 2L).eventKey)
    }

    @Test
    fun BerlinSourceZoneHandlesUtcBoundaryAndDstCalendar() {
        val boundary = Instant.parse("2026-09-30T22:30:00Z")
        assertEquals(
            LocalDate.of(2026, 10, 1),
            boundary.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 9, 30),
            boundary.atZone(ZoneOffset.UTC).toLocalDate(),
        )

        val dstTransition = Instant.parse("2026-10-25T00:30:00Z")
        assertEquals(
            LocalDate.of(2026, 10, 25),
            dstTransition.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate(),
        )
    }

    private fun item(
        stream: ReleaseStreamKey,
        installment: Installment,
        mediaId: Int?,
    ) = ReleaseUiCalendarItem(
        mediaId = mediaId,
        stream = stream,
        installment = installment,
        forecastAt = Instant.parse("2026-10-01T18:00:00Z"),
        confirmed = false,
        authority = ReleaseUiAuthority.VALID,
        sourceDate = LocalDate.of(2026, 10, 1),
        sourceRoot = "https://aniworld.to",
        revision = 1L,
    )
}

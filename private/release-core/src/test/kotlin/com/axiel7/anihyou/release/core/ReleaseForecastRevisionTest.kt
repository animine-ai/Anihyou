package com.axiel7.anihyou.release.core

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseForecastRevision
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseForecastRevisionTest {
    @Test
    fun onlyApproximateCalendarForecastsBecomeForecastRevisions() {
        val observedAt = Instant.parse("2026-09-22T18:00:00Z")
        val forecastAt = Instant.parse("2026-09-22T20:10:00Z")
        val forecast = evidence(
            id = "calendar-forecast",
            sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
            evidenceType = ReleaseEvidenceType.FORECAST,
            observedAt = observedAt,
            sourceReportedAt = forecastAt,
            approximate = true,
        )

        val revision = ReleaseForecastRevision.fromEvidence(forecast)

        assertEquals(forecast.id, revision?.evidenceId)
        assertEquals(forecastAt, revision?.forecastAt)
        assertEquals(true, revision?.approximateTime)
        assertNull(
            ReleaseForecastRevision.fromEvidence(
                forecast.copy(
                    sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                    evidenceType = ReleaseEvidenceType.CONFIRMATION,
                ),
            ),
        )
    }

    private fun evidence(
        id: String,
        sourceType: ReleaseSourceType,
        evidenceType: ReleaseEvidenceType,
        observedAt: Instant,
        sourceReportedAt: Instant?,
        approximate: Boolean,
    ) = ReleaseEvidence(
        id = id,
        sourceType = sourceType,
        sourceUrl = "https://aniworld.to/anime/stream/wp04a-core",
        sourceHash = "hash-$id",
        parserVersion = "wp04a-test",
        observedAt = observedAt,
        sourceReportedAt = sourceReportedAt,
        approximateTime = approximate,
        siteIdentifier = AniWorldSiteIdentifier(
            slug = "wp04a-core",
            sourceType = AniWorldIdentitySourceType.CALENDAR,
            firstSeenAt = observedAt,
            lastValidatedAt = observedAt,
        ),
        sourceSeason = 1,
        navigationSeason = 1,
        installment = Installment.Episode(1),
        languageTrack = LanguageTrack.DE_SUB,
        evidenceType = evidenceType,
        scheduleCondition = ScheduleCondition.UNKNOWN,
        confidence = ConfidenceVector(
            source = 1.0,
            identity = 1.0,
            installment = 1.0,
            languageTrack = 1.0,
            timing = 1.0,
        ),
    )
}

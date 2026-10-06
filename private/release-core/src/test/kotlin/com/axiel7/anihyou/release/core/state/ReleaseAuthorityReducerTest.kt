package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseAuthorityReducerTest {
    private val observedAt = Instant.parse("2026-09-21T12:00:00Z")
    private val site = AniWorldSiteIdentifier("foundation-test")
    private val reducer = AniWorldReleaseAuthorityReducer()

    @Test
    fun forecastCanNeverCreateReleasedDecision() {
        val decision = reducer.reduce(
            previous = null,
            evidence = evidence(
                id = "calendar-1",
                sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                evidenceType = ReleaseEvidenceType.FORECAST,
            ),
        )

        assertEquals(ReleasePhase.EXPECTED, decision.phase)
        assertNotEquals(ReleasePhase.RELEASED, decision.phase)
        assertEquals(ReleaseAuthority.NONE, decision.authority)
        assertEquals(ScheduleCondition.UNKNOWN, decision.scheduleCondition)
        assertEquals(null, decision.releaseAt)
        assertTrue(decision.authoritativeEvidenceIds.isEmpty())
    }

    @Test
    fun anilistEvidenceCannotBecomeReleaseAuthority() {
        val decision = reducer.reduce(
            previous = null,
            evidence = evidence(
                id = "anilist-1",
                sourceType = ReleaseSourceType.ANILIST_METADATA,
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
            ),
        )

        assertEquals(ReleasePhase.UNKNOWN, decision.phase)
        assertEquals(ReleaseAuthority.NONE, decision.authority)
        assertTrue(decision.authoritativeEvidenceIds.isEmpty())
    }

    @Test
    fun deSubAndDeDubRemainSeparateDecisionStreams() {
        val sub = reducer.reduce(
            previous = null,
            evidence = evidence(
                id = "recent-sub",
                languageTrack = LanguageTrack.DE_SUB,
            ),
        )
        val dub = reducer.reduce(
            previous = null,
            evidence = evidence(
                id = "recent-dub",
                languageTrack = LanguageTrack.DE_DUB,
            ),
        )

        assertEquals(ReleasePhase.RELEASED, sub.phase)
        assertEquals(ReleasePhase.RELEASED, dub.phase)
        assertNotEquals(sub.identityKey, dub.identityKey)

        val unchangedSub = reducer.reduce(
            previous = sub,
            evidence = evidence(
                id = "recent-dub-2",
                languageTrack = LanguageTrack.DE_DUB,
            ),
        )
        assertEquals(sub.identityKey, unchangedSub.identityKey)
        assertEquals(sub.phase, unchangedSub.phase)
        assertTrue(unchangedSub.diagnostics.any { it.contains("mismatched release identity") })
    }

    @Test
    fun releasedCannotFallBackToUnknown() {
        val released = reducer.reduce(
            previous = null,
            evidence = evidence(id = "recent-1"),
        )
        val afterUnknownSource = reducer.reduce(
            previous = released,
            evidence = evidence(
                id = "anilist-2",
                sourceType = ReleaseSourceType.ANILIST_METADATA,
                evidenceType = ReleaseEvidenceType.METADATA,
            ),
        )

        assertEquals(ReleasePhase.RELEASED, afterUnknownSource.phase)
        assertEquals(ReleaseAuthority.ANIWORLD, afterUnknownSource.authority)
        assertEquals(released.authoritativeEvidenceIds, afterUnknownSource.authoritativeEvidenceIds)
    }

    @Test
    fun exactPositiveCanReleaseAfterLegacyConflict() {
        val conflict = ReleaseDecision(
            siteIdentifier = site,
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(4),
            languageTrack = LanguageTrack.DE_SUB,
            phase = ReleasePhase.CONFLICT,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            authority = ReleaseAuthority.NONE,
            decidedAt = observedAt,
        )

        val afterPositiveEvidence = reducer.reduce(
            previous = conflict,
            evidence = evidence(
                id = "direct-4",
                sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
                evidenceType = ReleaseEvidenceType.VERIFICATION,
            ),
        )

        assertEquals(ReleasePhase.RELEASED, afterPositiveEvidence.phase)
        assertEquals(ReleaseAuthority.ANIWORLD, afterPositiveEvidence.authority)
    }

    @Test
    fun postponementEvidencePreservesExplicitHiatusCondition() {
        val decision = reducer.reduce(
            previous = null,
            evidence = evidence(
                id = "postponement-4",
                sourceType = ReleaseSourceType.ANIWORLD_POSTPONEMENT,
                evidenceType = ReleaseEvidenceType.CORRECTION,
                scheduleCondition = ScheduleCondition.HIATUS,
            ),
        )

        assertEquals(ReleasePhase.EXPECTED, decision.phase)
        assertEquals(ScheduleCondition.HIATUS, decision.scheduleCondition)
        assertEquals(ReleaseAuthority.NONE, decision.authority)
    }

    @Test
    fun onlyOriginallyExactCorrectRoleCanGrantAuthority() {
        val recent = evidence("recent")
        val direct = evidence("direct", sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
            evidenceType = ReleaseEvidenceType.VERIFICATION)
        assertEquals(ReleasePhase.RELEASED, reducer.reduce(null, recent).phase)
        assertEquals(ReleasePhase.RELEASED, reducer.reduce(null, direct).phase)
        val rejected = listOf(
            recent.copy(sourceSeason = null),
            recent.copy(languageTrack = null),
            recent.copy(installment = Installment.Film(0), sourceSeason = null),
            recent.copy(installment = Installment.Film(null), sourceSeason = null),
            recent.copy(installment = Installment.Special(1)),
            recent.copy(evidenceType = ReleaseEvidenceType.VERIFICATION),
            direct.copy(evidenceType = ReleaseEvidenceType.CONFIRMATION),
        )
        rejected.forEach { item ->
            assertNotEquals("Unexpected authority: ${item.identityKey}", ReleasePhase.RELEASED,
                reducer.reduce(null, item).phase)
        }
        assertEquals(ReleasePhase.RELEASED, reducer.reduce(null,
            recent.copy(sourceSeason = 0, installment = Installment.Episode(0))).phase)
    }

    @Test
    fun calendarCannotSetOnScheduleAndUnknownCorrectionCannotImplyDelay() {
        val forecast = reducer.reduce(null, evidence("calendar", sourceType =
            ReleaseSourceType.ANIWORLD_CALENDAR, evidenceType = ReleaseEvidenceType.FORECAST))
        assertEquals(ScheduleCondition.UNKNOWN, forecast.scheduleCondition)
        assertEquals(null, forecast.releaseAt)
        val corrected = reducer.reduce(forecast, evidence("correction", sourceType =
            ReleaseSourceType.ANIWORLD_POSTPONEMENT, evidenceType = ReleaseEvidenceType.CORRECTION,
            scheduleCondition = ScheduleCondition.UNKNOWN))
        assertEquals(ScheduleCondition.UNKNOWN, corrected.scheduleCondition)
        assertEquals(ReleaseAuthority.NONE, corrected.authority)
    }

    private fun evidence(
        id: String,
        sourceType: ReleaseSourceType = ReleaseSourceType.ANIWORLD_RECENT,
        evidenceType: ReleaseEvidenceType = ReleaseEvidenceType.CONFIRMATION,
        siteIdentifier: AniWorldSiteIdentifier? = site,
        languageTrack: LanguageTrack? = LanguageTrack.DE_SUB,
        scheduleCondition: ScheduleCondition = ScheduleCondition.UNKNOWN,
    ): ReleaseEvidence = ReleaseEvidence(
        id = id,
        sourceType = sourceType,
        sourceUrl = when {
            sourceType == ReleaseSourceType.ANILIST_METADATA -> "https://graphql.anilist.co"
            else -> "https://aniworld.to/anime/foundation-test"
        },
        sourceHash = "sha256-$id",
        parserVersion = "v3-foundation-test",
        observedAt = observedAt,
        sourceReportedAt = observedAt,
        approximateTime = false,
        siteIdentifier = siteIdentifier,
        sourceSeason = 1,
        navigationSeason = 1,
        installment = Installment.Episode(4),
        languageTrack = languageTrack,
        evidenceType = evidenceType,
        scheduleCondition = scheduleCondition,
        confidence = ConfidenceVector(
            source = 1.0,
            identity = 1.0,
            installment = 1.0,
            languageTrack = 1.0,
            timing = 1.0,
        ),
    )
}

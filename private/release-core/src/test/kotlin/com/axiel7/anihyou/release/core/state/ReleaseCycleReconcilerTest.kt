package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReleaseCycleReconcilerTest {
    private val site = AniWorldSiteIdentifier("reconciliation")
    private val t0 = Instant.parse("2026-03-28T12:00:00Z")
    private val reconciler = ReleaseCycleReconciler()

    private fun evidence(id: String, source: ReleaseSourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
                         kind: ReleaseEvidenceType = ReleaseEvidenceType.FORECAST,
                         season: Int? = 2, track: LanguageTrack? = LanguageTrack.DE_SUB,
                         part: Installment = Installment.Episode(1),
                         time: Instant? = t0): ReleaseEvidence = ReleaseEvidence(
        id, source, "https://aniworld.to/anime/stream/reconciliation", "hash-$id", "fixture",
        t0, time, false, site, season, 3, part, track, kind,
        ScheduleCondition.UNKNOWN, ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
    )

    private fun cycle(id: String, time: Instant, vararg items: ReleaseEvidence,
                      coverage: TargetCoverage = TargetCoverage.UNKNOWN,
                      presence: TargetPresence = TargetPresence.UNKNOWN): CompletedObservationCycle {
        val key = items.firstOrNull()?.let(CanonicalReleaseIdentity::from)?.key ?: "scope"
        val source = items.firstOrNull()?.sourceType ?: ReleaseSourceType.ANIWORLD_DIRECT_PAGE
        return CompletedObservationCycle(id, "scope", time.minusSeconds(60), time,
            AbsencePolicySnapshot(), listOf(CycleSourceObservation(id, source, key,
                LanguageTrack.DE_SUB, CycleResult.SUCCESS, SourceHealthStatus.HEALTHY, coverage,
                presence, time, items.toList())))
    }

    @Test fun exactIdentityIgnoresNavigationButDistinguishesSeasonTrackAndFraction() {
        val first = evidence("a")
        val key = CanonicalReleaseIdentity.from(first)!!
        assertEquals(key, CanonicalReleaseIdentity.decode(key.key))
        assertEquals(key, CanonicalReleaseIdentity.from(first.copy(navigationSeason = 99)))
        assertNotEquals(key, CanonicalReleaseIdentity.from(evidence("b", season = 1)))
        assertNotEquals(key, CanonicalReleaseIdentity.from(evidence("c", track = LanguageTrack.DE_DUB)))
        assertNotEquals(key, CanonicalReleaseIdentity.from(evidence("d", part = Installment.Episode(1, 1))))
        assertNotNull(CanonicalReleaseIdentity.from(evidence("zero", season = 0, part = Installment.Episode(0))))
        assertNotNull(CanonicalReleaseIdentity.from(evidence("film", season = null, part = Installment.Film(1))))
        assertNull(CanonicalReleaseIdentity.from(evidence("film-zero", season = null, part = Installment.Film(0))))
        assertNull(CanonicalReleaseIdentity.from(evidence("special", part = Installment.Special(1))))
        assertNull(CanonicalReleaseIdentity.decode(key.key + "x"))
    }

    @Test fun partialCompatibilityNeverUnionsTwoDifferentSeasons() {
        val partial = evidence("p", season = null)
        val first = evidence("a", season = 1)
        val second = evidence("b", season = 2)
        assertTrue(ReleaseIdentityCompatibilityPolicy.compatible(partial, first))
        assertTrue(ReleaseIdentityCompatibilityPolicy.compatible(partial, second))
        assertNotEquals(CanonicalReleaseIdentity.from(first), CanonicalReleaseIdentity.from(second))
        assertEquals(ReleaseIdentityCompatibilityPolicy.Selection.Ambiguous,
            ReleaseIdentityCompatibilityPolicy.select(partial, listOf(first, second), true))
        assertEquals(ReleaseIdentityCompatibilityPolicy.Selection.Unresolved,
            ReleaseIdentityCompatibilityPolicy.select(partial, listOf(first), false))
        assertEquals(ReleaseIdentityCompatibilityPolicy.Selection.Unresolved,
            ReleaseIdentityCompatibilityPolicy.select(partial.copy(
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                evidenceType = ReleaseEvidenceType.CONFIRMATION), listOf(first), true))
    }

    @Test fun partialBindingSuspendsWhenSecondCandidateArrivesButReleaseRemains() {
        val p = evidence("p", season = null)
        val a = evidence("a", source = ReleaseSourceType.ANIWORLD_RECENT,
            kind = ReleaseEvidenceType.CONFIRMATION, season = 1)
        val b = evidence("b", source = ReleaseSourceType.ANIWORLD_RECENT,
            kind = ReleaseEvidenceType.CONFIRMATION, season = 2)
        val first = reconciler.reconcile(emptyMap(), cycle("one", t0, a), listOf(p), true)
        assertEquals(CanonicalReleaseIdentity.from(a)!!.key, first.states["partial-v1:p"]?.bindingKey)
        val second = reconciler.reconcile(first.states, cycle("two", t0.plusSeconds(60), b), listOf(p), true)
        assertNull(second.states["partial-v1:p"]?.bindingKey)
        assertEquals(ReleasePhase.CONFLICT, second.states["partial-v1:p"]?.phase)
        assertEquals(ReleasePhase.RELEASED, second.states[CanonicalReleaseIdentity.from(a)!!.key]?.phase)
        assertTrue(second.changes.any { it.kind == "SUSPEND_BINDING" })
    }

    @Test fun forecastReceiptAtoBtoAReusesOriginalEvidence() {
        val a = evidence("A", time = t0)
        val b = evidence("B", time = t0.plusSeconds(3600))
        val first = reconciler.reconcile(emptyMap(), cycle("one", t0, a), emptyList(), true)
        val second = reconciler.reconcile(first.states, cycle("two", t0.plusSeconds(60), b), emptyList(), true)
        val third = reconciler.reconcile(second.states, cycle("three", t0.plusSeconds(120), a), emptyList(), true)
        val key = CanonicalReleaseIdentity.from(a)!!.key
        assertEquals("A", third.states[key]?.forecastEvidenceId)
        assertEquals(t0, third.states[key]?.forecastAt)
        assertTrue(third.states.getValue(key).revision > second.states.getValue(key).revision)
        assertEquals(ReleaseAuthority.NONE, third.states[key]?.authority)
        assertEquals(ScheduleCondition.UNKNOWN, third.states[key]?.scheduleCondition)
    }

    @Test fun missingRequiresTwoCoveredHealthySeparatedDirectCyclesAfterGrace() {
        val forecast = evidence("forecast")
        val key = CanonicalReleaseIdentity.from(forecast)!!.key
        val initial = reconciler.reconcile(emptyMap(), cycle("forecast", t0, forecast), emptyList(), true)
        val due = t0.plusSeconds(24 * 3600L)
        val emptyUncovered = cycle("uncovered", due)
        assertEquals(0, ReleaseMissingPolicy.apply(initial.states.getValue(key), emptyUncovered, false).absenceCount)
        fun negative(id: String, at: Instant) = CompletedObservationCycle(id, "scope",
            at.minusSeconds(60), at, AbsencePolicySnapshot(), listOf(CycleSourceObservation(
                id, ReleaseSourceType.ANIWORLD_DIRECT_PAGE, key, LanguageTrack.DE_SUB,
                CycleResult.SUCCESS, SourceHealthStatus.HEALTHY,
                TargetCoverage.COMPLETE_FOR_TARGET, TargetPresence.ABSENT, at)))
        val first = ReleaseMissingPolicy.apply(initial.states.getValue(key), negative("n1", due), false)
        assertEquals(ReleasePhase.EXPECTED, first.phase)
        assertEquals(1, first.absenceCount)
        val second = ReleaseMissingPolicy.apply(first, negative("n2", due.plusSeconds(1800)), false)
        assertEquals(ReleasePhase.MISSING, second.phase)
        val failed = negative("n3", due.plusSeconds(3600)).copy(sources = listOf(
            negative("n3", due.plusSeconds(3600)).sources.single().copy(result = CycleResult.FAILURE,
                coverage = TargetCoverage.UNKNOWN, health = SourceHealthStatus.UNAVAILABLE)))
        assertEquals(0, ReleaseMissingPolicy.apply(second, failed, false).absenceCount)
        assertEquals(ReleasePhase.MISSING, ReleaseMissingPolicy.apply(second, failed, false).phase)
    }

    @Test fun exactPositiveCanCoexistWithStickyConflictWithoutPhaseRegression() {
        val recent = evidence("recent", source = ReleaseSourceType.ANIWORLD_RECENT,
            kind = ReleaseEvidenceType.CONFIRMATION, time = t0)
        val direct = evidence("direct", source = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
            kind = ReleaseEvidenceType.VERIFICATION, time = t0.plusSeconds(120))
        val first = reconciler.reconcile(emptyMap(), cycle("one", t0, recent), emptyList(), true)
        val second = reconciler.reconcile(first.states, cycle("two", t0.plusSeconds(60), direct),
            emptyList(), true)
        val key = CanonicalReleaseIdentity.from(recent)!!.key
        assertEquals(ReleasePhase.RELEASED, second.states[key]?.phase)
        assertEquals(t0, second.states[key]?.releaseAt)
        assertTrue(second.states.getValue(key).conflicts.any { it.open &&
            it.kind == ReleaseConflictKind.PUBLICATION_TIME_DISAGREEMENT })
        val conflict = second.states.getValue(key).conflicts.first()
        val resolved = ReleaseConflictPolicy.resolve(second.states.getValue(key), conflict.id,
            "operator", "source correction proof")
        assertEquals(ReleasePhase.RELEASED, resolved.phase)
        assertFalse(resolved.conflicts.first().open)
    }
}

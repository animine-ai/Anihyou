package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.*
import java.time.Instant

object ReleaseMissingPolicy {
    fun apply(state: CanonicalReleaseState, cycle: CompletedObservationCycle,
              positivePresent: Boolean, late: Boolean = false): CanonicalReleaseState {
        if (state.underlyingPhase == ReleasePhase.RELEASED || positivePresent || late) return state
        val at = state.forecastAt ?: return state.copy(absenceCount = 0, lastAbsenceAt = null)
        if (state.expectationEvidenceId == null || state.scheduleCondition != ScheduleCondition.UNKNOWN ||
            state.conflicts.any { it.open }) return state.copy(absenceCount = 0, lastAbsenceAt = null)
        val required = cycle.manifest.filter { it.negativeRequired && it.targetKey == state.key }
        val complete = cycle.sources.filter { source -> required.any { it.instanceId == source.instanceId } }
        // An unrelated source or unchanged calendar refresh is not an absence probe.
        if (required.isEmpty()) return state
        if (required.none { it.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE &&
            it.track == CanonicalReleaseIdentity.decode(state.key)?.track })
            return state.copy(absenceCount = 0, lastAbsenceAt = null)
        val qualified = complete.all {
            it.result == CycleResult.SUCCESS && it.health == SourceHealthStatus.HEALTHY &&
                it.coverage == TargetCoverage.COMPLETE_FOR_TARGET &&
                it.presence == TargetPresence.ABSENT && it.observedAt != null &&
                it.track == CanonicalReleaseIdentity.decode(state.key)?.track
        } && cycle.completedAt <= cycle.startedAt.plus(cycle.policy.maximumCycleDuration) &&
            cycle.completedAt >= at.plus(cycle.policy.grace) &&
            cycle.sources.none { it.evidence.any { e ->
                CanonicalReleaseIdentity.from(e)?.key == state.key &&
                    ((e.sourceType == ReleaseSourceType.ANIWORLD_RECENT &&
                        e.evidenceType == ReleaseEvidenceType.CONFIRMATION) ||
                        (e.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE &&
                            e.evidenceType == ReleaseEvidenceType.VERIFICATION))
            } }
        if (!qualified) return state.copy(absenceCount = 0, lastAbsenceAt = null)
        val prior = state.lastAbsenceAt
        val count = if (prior != null && cycle.completedAt > prior &&
            cycle.completedAt >= prior.plus(cycle.policy.minimumSeparation)) state.absenceCount + 1 else 1
        return state.copy(
            absenceCount = count, lastAbsenceAt = cycle.completedAt,
            underlyingPhase = if (count >= 2) ReleasePhase.MISSING else state.underlyingPhase,
            phase = if (count >= 2) ReleasePhase.MISSING else state.phase,
        )
    }
}

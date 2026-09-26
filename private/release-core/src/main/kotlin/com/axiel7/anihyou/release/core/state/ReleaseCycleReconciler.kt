package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.*

data class ReconciliationChange(
    val kind: String,
    val key: String,
    val evidenceId: String?,
    val before: CanonicalReleaseState?,
    val after: CanonicalReleaseState,
)

data class ReconciliationPlan(
    val states: Map<String, CanonicalReleaseState>,
    val changes: List<ReconciliationChange>,
)

/** Pure cycle policy. Evidence snapshots and prior state must be complete for the supplied buckets. */
class ReleaseCycleReconciler {
    fun reconcile(previous: Map<String, CanonicalReleaseState>, cycle: CompletedObservationCycle,
                  partialForecasts: Collection<ReleaseEvidence>,
                  completeCandidates: Boolean, late: Boolean = false): ReconciliationPlan {
        val states = previous.toMutableMap()
        val changes = mutableListOf<ReconciliationChange>()
        val items = cycle.sources.flatMap { it.evidence }.distinctBy { it.id }
            .sortedWith(compareBy({ it.sourceType.name }, { it.id }))
        fun put(key: String, kind: String, evidenceId: String?, next: CanonicalReleaseState,
                semantic: Boolean = true) {
            val before = states[key]
            if (before == next) return
            val after = next.copy(revision = (before?.revision ?: 0) + if (semantic) 1 else 0)
            states[key] = after
            changes += ReconciliationChange(kind, key, evidenceId, before, after)
        }
        val exact = items.mapNotNull { evidence ->
            CanonicalReleaseIdentity.from(evidence)?.let { it to evidence }
        }.groupBy({ it.first }, { it.second })
        exact.toSortedMap(compareBy { it.key }).forEach { (identity, evidence) ->
            val key = identity.key
            var state = states[key] ?: CanonicalReleaseState(key)
            val keyLate = late || state.latestCompletedAt?.isAfter(cycle.completedAt) == true
            val observedNavigation = evidence.mapNotNull { it.navigationSeason }.toSet()
            if (observedNavigation.isNotEmpty()) {
                state = state.copy(navigationSeasons = state.navigationSeasons + observedNavigation)
                put(key, "NAVIGATION_OBSERVATION", null, state)
            }
            val positives = evidence.filter { it.isExactPositive() }
            val preciseTimes = positives.filter { !it.approximateTime }
                .mapNotNull { it.sourceReportedAt }.distinct()
            if (positives.isNotEmpty()) {
                val simultaneousDisagreement = state.releaseAt == null && preciseTimes.size > 1
                if (simultaneousDisagreement || (state.releaseAt != null &&
                    preciseTimes.any { it != state.releaseAt })) {
                    val ids = positives.map { it.id }.toSet()
                    state = ReleaseConflictPolicy.open(state, ReleaseConflict(
                        id = "publication:$key:${ids.sorted().joinToString(",")}",
                        kind = ReleaseConflictKind.PUBLICATION_TIME_DISAGREEMENT,
                        evidenceIds = ids, open = true,
                    ))
                }
                state = state.copy(
                    underlyingPhase = ReleasePhase.RELEASED, authority = ReleaseAuthority.ANIWORLD,
                    releaseAt = if (simultaneousDisagreement) null else state.releaseAt ?: preciseTimes.singleOrNull(),
                    absenceCount = 0, lastAbsenceAt = null,
                )
                state = state.copy(phase = ReleaseConflictPolicy.effectivePhase(state))
                put(key, "EXACT_RELEASE", positives.first().id, state)
            }
            val forecasts = evidence.filter {
                it.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                    it.evidenceType == ReleaseEvidenceType.FORECAST
            }
            if (forecasts.isNotEmpty() && !keyLate) {
                val moments = forecasts.map { it.sourceReportedAt to it.approximateTime }.distinct()
                if (moments.size == 1) {
                    val forecast = forecasts.minBy { it.id }
                    val changed = state.forecastEvidenceId != forecast.id
                    state = state.copy(
                        underlyingPhase = if (state.underlyingPhase == ReleasePhase.UNKNOWN ||
                            changed && state.underlyingPhase == ReleasePhase.MISSING)
                            ReleasePhase.EXPECTED else state.underlyingPhase,
                        forecastAt = forecast.sourceReportedAt,
                        forecastEvidenceId = forecast.id,
                        expectationEvidenceId = if (forecast.sourceReportedAt != null &&
                            !forecast.approximateTime) forecast.id else null,
                        absenceCount = if (changed) 0 else state.absenceCount,
                        lastAbsenceAt = if (changed) null else state.lastAbsenceAt,
                    )
                    state = state.copy(phase = ReleaseConflictPolicy.effectivePhase(state))
                    put(key, "FORECAST_RECEIPT", forecast.id, state)
                } else {
                    val ids = forecasts.map { it.id }.toSet()
                    state = ReleaseConflictPolicy.open(state, ReleaseConflict(
                        "forecast:$key:${ids.sorted().joinToString(",")}",
                        ReleaseConflictKind.SCHEDULE_DISAGREEMENT, ids, true,
                    ))
                    put(key, "OPEN_CONFLICT", forecasts.first().id, state)
                }
            }
            val corrections = evidence.filter {
                it.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT &&
                    it.evidenceType == ReleaseEvidenceType.CORRECTION &&
                    it.scheduleCondition != ScheduleCondition.UNKNOWN
            }
            if (corrections.isNotEmpty() && !keyLate) {
                val conditions = corrections.map { it.scheduleCondition }.distinct()
                if (conditions.size == 1 && state.scheduleCondition == ScheduleCondition.UNKNOWN &&
                    state.conflicts.none { it.open &&
                        it.kind == ReleaseConflictKind.SCHEDULE_DISAGREEMENT }) {
                    state = state.copy(scheduleCondition = conditions.single(),
                        scheduleEvidenceId = corrections.first().id)
                    put(key, "EXACT_CORRECTION", corrections.first().id, state)
                } else if (conditions.size > 1 ||
                    conditions.single() != state.scheduleCondition) {
                    val replacement = corrections.singleOrNull()?.takeIf { next ->
                        state.scheduleEvidenceId != null && cycle.sources.any { source ->
                            source.evidence.any { it.id == next.id } &&
                                state.scheduleEvidenceId in source.supersedesEvidenceIds
                        }
                    }
                    if (replacement != null && state.conflicts.none { it.open &&
                        it.kind == ReleaseConflictKind.SCHEDULE_DISAGREEMENT }) {
                        state = state.copy(scheduleCondition = replacement.scheduleCondition,
                            scheduleEvidenceId = replacement.id, absenceCount = 0, lastAbsenceAt = null)
                        put(key, "SUPERSEDE_CORRECTION", replacement.id, state)
                    } else {
                        val ids = (corrections.map { it.id } + listOfNotNull(state.scheduleEvidenceId)).toSet()
                        state = ReleaseConflictPolicy.open(state, ReleaseConflict(
                            "schedule:$key:${ids.sorted().joinToString(",")}",
                            ReleaseConflictKind.SCHEDULE_DISAGREEMENT, ids, true,
                        )).copy(scheduleCondition = ScheduleCondition.UNKNOWN)
                        put(key, "OPEN_CONFLICT", corrections.first().id, state)
                    }
                }
            }
        }
        // A second exact candidate can invalidate an earlier binding even if the partial
        // Evidence is absent from this poll. The caller supplies all persisted partials in scope.
        val candidatesByBucket = states.keys.mapNotNull(CanonicalReleaseIdentity::decode)
            .groupBy { it.bucketKey }
        partialForecasts.sortedBy { it.id }.forEach { partial ->
            if (!ReleaseIdentityCompatibilityPolicy.mayBindForecast(partial)) return@forEach
            val key = "partial-v1:${partial.id}"
            val prior = states[key] ?: CanonicalReleaseState(key, underlyingPhase = ReleasePhase.EXPECTED,
                phase = ReleasePhase.EXPECTED)
            val candidates = candidatesByBucket[CanonicalReleaseIdentity.bucketOf(partial)].orEmpty()
            val selection = ReleaseIdentityCompatibilityPolicy.selectKeys(partial, candidates,
                completeCandidates)
            val bound = (selection as? ReleaseIdentityCompatibilityPolicy.Selection.Bound)?.identity?.key
            var next = prior.copy(bindingKey = bound)
            if (prior.bindingKey != null && prior.bindingKey != bound) {
                val old = states[prior.bindingKey]
                if (old != null && old.forecastEvidenceId == partial.id) {
                    val stripped = old.copy(
                        forecastAt = null, forecastEvidenceId = null, expectationEvidenceId = null,
                        absenceCount = 0, lastAbsenceAt = null,
                        underlyingPhase = if (old.underlyingPhase == ReleasePhase.RELEASED)
                            ReleasePhase.RELEASED else ReleasePhase.UNKNOWN,
                    )
                    put(old.key, "SUSPEND_CONTEXT_EFFECT", partial.id,
                        stripped.copy(phase = ReleaseConflictPolicy.effectivePhase(stripped)))
                }
            }
            if (selection == ReleaseIdentityCompatibilityPolicy.Selection.Ambiguous) {
                next = ReleaseConflictPolicy.open(next, ReleaseConflict(
                    "ambiguous:$key", ReleaseConflictKind.AMBIGUOUS_PARTIAL_TARGET,
                    setOf(partial.id), true,
                ))
            }
            val kind = if (prior.bindingKey != null && bound == null) "SUSPEND_BINDING"
                else if (bound != null && prior.bindingKey != bound) "BIND_CONTEXT"
                else "PARTIAL_CONTEXT"
            put(key, kind, partial.id, next)
            if (bound != null) {
                val target = states[bound] ?: error("bound candidate missing from snapshot")
                if (target.forecastEvidenceId == null && partial.sourceReportedAt != null) {
                    val enriched = target.copy(
                        underlyingPhase = if (target.underlyingPhase == ReleasePhase.UNKNOWN)
                            ReleasePhase.EXPECTED else target.underlyingPhase,
                        forecastAt = partial.sourceReportedAt,
                        forecastEvidenceId = partial.id,
                        expectationEvidenceId = null,
                    )
                    put(bound, "BOUND_FORECAST_CONTEXT", partial.id,
                        enriched.copy(phase = ReleaseConflictPolicy.effectivePhase(enriched)))
                }
            }
        }
        // Missing only applies to exact projections with a persisted exact expectation.
        states.keys.sorted().forEach { key ->
            if (CanonicalReleaseIdentity.decode(key) == null) return@forEach
            val state = states.getValue(key)
            val keyLate = late || state.latestCompletedAt?.isAfter(cycle.completedAt) == true
            val missing = ReleaseMissingPolicy.apply(state, cycle,
                items.any { CanonicalReleaseIdentity.from(it)?.key == key && it.isExactPositive() }, keyLate)
            put(key, "ABSENCE_PROBE", null, missing)
        }
        val touchedKeys = exact.keys.map { it.key }.toSet() +
            cycle.sources.mapNotNull { CanonicalReleaseIdentity.decode(it.targetKey)?.key }
        touchedKeys.sorted().forEach { key ->
            val state = states[key] ?: return@forEach
            if (state.latestCompletedAt == null || cycle.completedAt > state.latestCompletedAt) {
                put(key, "CYCLE_TIMESTAMP", null,
                    state.copy(latestCompletedAt = cycle.completedAt), semantic = false)
            }
        }
        return ReconciliationPlan(states.toMap(), changes)
    }

    private fun ReleaseEvidence.isExactPositive(): Boolean =
        CanonicalReleaseIdentity.from(this) != null && confidence.overall > 0 &&
            ((sourceType == ReleaseSourceType.ANIWORLD_RECENT &&
                evidenceType == ReleaseEvidenceType.CONFIRMATION) ||
                (sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE &&
                    evidenceType == ReleaseEvidenceType.VERIFICATION))
}

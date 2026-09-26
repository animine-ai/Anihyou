package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.*

object ReleaseConflictPolicy {
    fun effectivePhase(state: CanonicalReleaseState): ReleasePhase = when {
        state.underlyingPhase == ReleasePhase.RELEASED -> ReleasePhase.RELEASED
        state.conflicts.any { it.open } -> ReleasePhase.CONFLICT
        else -> state.underlyingPhase
    }

    fun open(state: CanonicalReleaseState, conflict: ReleaseConflict): CanonicalReleaseState {
        require(conflict.open && conflict.evidenceIds.isNotEmpty())
        val existing = state.conflicts.singleOrNull { it.id == conflict.id }
        check(existing == null || existing == conflict) { "conflict ID collision" }
        val updated = state.copy(conflicts = if (existing == null) state.conflicts + conflict else state.conflicts)
        return updated.copy(phase = effectivePhase(updated))
    }

    fun resolve(state: CanonicalReleaseState, id: String,
                operator: String, reason: String): CanonicalReleaseState {
        require(operator.isNotBlank() && reason.isNotBlank())
        check(state.conflicts.any { it.id == id && it.open }) { "unknown or closed conflict" }
        val updated = state.copy(conflicts = state.conflicts.map {
            if (it.id == id) it.copy(open = false) else it
        })
        return updated.copy(phase = effectivePhase(updated))
    }
}

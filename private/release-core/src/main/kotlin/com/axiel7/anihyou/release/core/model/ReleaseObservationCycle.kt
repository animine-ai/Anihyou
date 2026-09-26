package com.axiel7.anihyou.release.core.model

import java.time.Duration
import java.time.Instant

enum class TargetCoverage { UNKNOWN, COMPLETE_FOR_TARGET }
enum class TargetPresence { UNKNOWN, PRESENT, ABSENT }
enum class CycleResult { SUCCESS, PARTIAL_SUCCESS, FAILURE, INCOMPLETE }

/** The manifest is represented by one result per expected source instance, even on failure. */
data class CycleSourceObservation(
    val instanceId: String,
    val sourceType: ReleaseSourceType,
    val targetKey: String,
    val track: LanguageTrack?,
    val result: CycleResult,
    val health: SourceHealthStatus,
    val coverage: TargetCoverage = TargetCoverage.UNKNOWN,
    val presence: TargetPresence = TargetPresence.UNKNOWN,
    val observedAt: Instant? = null,
    val evidence: List<ReleaseEvidence> = emptyList(),
    val supersedesEvidenceIds: List<String> = emptyList(),
) {
    init {
        require(instanceId.isNotBlank() && instanceId.length <= 256)
        require(targetKey.isNotBlank() && targetKey.length <= 2048)
        require(result != CycleResult.FAILURE || evidence.isEmpty())
        require(coverage != TargetCoverage.COMPLETE_FOR_TARGET ||
            (result == CycleResult.SUCCESS && health == SourceHealthStatus.HEALTHY &&
                observedAt != null))
        require(presence != TargetPresence.ABSENT || evidence.isEmpty())
        require(supersedesEvidenceIds.size <= 64 && supersedesEvidenceIds.distinct().size == supersedesEvidenceIds.size)
    }
}

data class AbsencePolicySnapshot(
    val version: Int = 1,
    val grace: Duration = Duration.ofHours(24),
    val minimumSeparation: Duration = Duration.ofMinutes(30),
    val maximumCycleDuration: Duration = Duration.ofMinutes(30),
) {
    init {
        require(version == 1 && grace == Duration.ofHours(24) &&
            minimumSeparation == Duration.ofMinutes(30) &&
            maximumCycleDuration == Duration.ofMinutes(30))
    }
}

data class ExpectedSourceInstance(
    val instanceId: String,
    val sourceType: ReleaseSourceType,
    val targetKey: String,
    val track: LanguageTrack?,
    val negativeRequired: Boolean = false,
)

data class CompletedObservationCycle(
    val id: String,
    val scopeId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val policy: AbsencePolicySnapshot,
    val sources: List<CycleSourceObservation>,
    val manifest: List<ExpectedSourceInstance> = emptyList(),
) {
    init {
        require(id.isNotBlank() && id.length <= 256 && scopeId.isNotBlank() && scopeId.length <= 2048)
        require(!completedAt.isBefore(startedAt) && sources.isNotEmpty())
        require(sources.map { it.instanceId }.distinct().size == sources.size)
        require(sources.size <= 1024 && sources.sumOf { it.evidence.size } <= 65536)
        require(manifest.size <= 1024 && manifest.map { it.instanceId }.distinct().size == manifest.size)
        require(manifest.isEmpty() || manifest.map { it.instanceId }.toSet() ==
            sources.map { it.instanceId }.toSet())
        require(manifest.all { expected -> sources.any { actual ->
            actual.instanceId == expected.instanceId && actual.sourceType == expected.sourceType &&
                actual.targetKey == expected.targetKey && actual.track == expected.track
        } })
        require(sources.none { it.coverage == TargetCoverage.COMPLETE_FOR_TARGET } ||
            manifest.isNotEmpty()) { "negative coverage requires a predeclared manifest" }
        require(sources.all { it.observedAt == null ||
            (it.observedAt >= startedAt && it.observedAt <= completedAt) })
    }
}

enum class ReleaseConflictKind {
    AMBIGUOUS_PARTIAL_TARGET, PUBLICATION_TIME_DISAGREEMENT,
    SCHEDULE_DISAGREEMENT, LEGACY_INCOMPLETE_AUTHORITY,
}

data class ReleaseConflict(
    val id: String,
    val kind: ReleaseConflictKind,
    val evidenceIds: Set<String>,
    val open: Boolean,
)

data class CanonicalReleaseState(
    val key: String,
    val underlyingPhase: ReleasePhase = ReleasePhase.UNKNOWN,
    val phase: ReleasePhase = ReleasePhase.UNKNOWN,
    val authority: ReleaseAuthority = ReleaseAuthority.NONE,
    val scheduleCondition: ScheduleCondition = ScheduleCondition.UNKNOWN,
    val scheduleEvidenceId: String? = null,
    val releaseAt: Instant? = null,
    val forecastAt: Instant? = null,
    val forecastEvidenceId: String? = null,
    val bindingKey: String? = null,
    val conflicts: List<ReleaseConflict> = emptyList(),
    val revision: Long = 0,
    val absenceCount: Int = 0,
    val lastAbsenceAt: Instant? = null,
    val expectationEvidenceId: String? = null,
) {
    init { require(revision >= 0 && absenceCount >= 0) }
}

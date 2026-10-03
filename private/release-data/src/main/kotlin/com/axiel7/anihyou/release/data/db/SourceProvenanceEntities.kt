package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * R04: which exact extension release source committed a cycle. A row exists only for cycles that
 * were committed through the source-bound extension route (Room v14 and later); cycles from before
 * v14 have no row, so their origin stays unknown and is never guessed.
 */
@Entity(tableName = "v3_cycle_provenance",
    foreignKeys = [ForeignKey(entity = ObservationCycleEntity::class, parentColumns = ["cycleId"],
        childColumns = ["cycleId"], onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT)],
    indices = [Index(value = ["sourceId", "extensionId", "publisherId", "providerId", "commitSequence"],
        name = "idx_v3_provenance_source")])
data class CycleProvenanceEntity(
    @androidx.room.PrimaryKey val cycleId: String,
    val sourceId: String,
    val extensionId: String,
    val publisherId: String,
    val providerId: String,
    val commitSequence: Long,
    val completedAt: String,
)

/**
 * R04: the canonical release state folded from the cycles of exactly one extension release source.
 * Two sources may carry the same canonical `projectionKey` with different values; each keeps its own
 * row, so a source never presents (or builds on) rows another source accepted. The global
 * `v3_canonical_release_projection` stays untouched as the provider-wide history.
 */
@Entity(tableName = "v3_source_projection",
    primaryKeys = ["sourceId", "extensionId", "publisherId", "providerId", "projectionKey"],
    indices = [
        Index(value = ["sourceId", "extensionId", "publisherId", "providerId", "bucketKey"],
            name = "idx_v3_source_projection_bucket"),
    ])
data class SourceReleaseProjectionEntity(
    val sourceId: String,
    val extensionId: String,
    val publisherId: String,
    val providerId: String,
    val projectionKey: String,
    val bucketKey: String,
    val underlyingPhase: String,
    val phase: String,
    val authority: String,
    val scheduleCondition: String,
    val scheduleEvidenceId: String?,
    val releaseAt: String?,
    val forecastAt: String?,
    val forecastEvidenceId: String?,
    val bindingKey: String?,
    val conflictIdsPayload: String,
    val revision: Long,
    val lastAppliedSequence: Long,
    val absenceCount: Int,
    val lastAbsenceAt: String?,
    val expectationEvidenceId: String?,
    val navigationPayload: String,
    val latestCompletedAt: String?,
) {
    /** The shared state decoder and validator work on the canonical row shape. */
    fun asCanonical(): CanonicalReleaseProjectionEntity = CanonicalReleaseProjectionEntity(
        projectionKey, bucketKey, underlyingPhase, phase, authority, scheduleCondition,
        scheduleEvidenceId, releaseAt, forecastAt, forecastEvidenceId, bindingKey, conflictIdsPayload,
        revision, lastAppliedSequence, absenceCount, lastAbsenceAt, expectationEvidenceId,
        navigationPayload, latestCompletedAt,
    )
}

fun CanonicalReleaseProjectionEntity.forSource(
    sourceId: String, extensionId: String, publisherId: String, providerId: String,
): SourceReleaseProjectionEntity = SourceReleaseProjectionEntity(
    sourceId, extensionId, publisherId, providerId, projectionKey, bucketKey, underlyingPhase, phase,
    authority, scheduleCondition, scheduleEvidenceId, releaseAt, forecastAt, forecastEvidenceId,
    bindingKey, conflictIdsPayload, revision, lastAppliedSequence, absenceCount, lastAbsenceAt,
    expectationEvidenceId, navigationPayload, latestCompletedAt,
)

package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "v3_release_evidence",
    indices = [
        Index(
            value = ["identityKey", "observedAt"],
            name = "idx_v3_evidence_identity_observed",
        ),
        Index(
            value = ["sourceType", "observedAt"],
            name = "idx_v3_evidence_source_observed",
        ),
        Index(value = ["sourceHash"], name = "idx_v3_evidence_hash"),
        Index(
            value = ["canonicalFingerprint"],
            unique = true,
            name = "idx_v3_evidence_fingerprint",
        ),
        Index(
            value = ["evidenceType", "observedAt"],
            name = "idx_v3_evidence_type_observed",
        ),
    ],
)
data class ReleaseEvidenceEntity(
    @androidx.room.PrimaryKey val id: String,
    val canonicalFingerprint: String,
    val identityKey: String,
    val sourceType: String,
    val sourceUrl: String,
    val sourceHash: String,
    val parserVersion: String,
    val observedAt: String,
    val sourceReportedAt: String?,
    val approximateTime: Boolean,
    val siteIdentifierPayload: String?,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installmentPayload: String,
    val languageTrack: String?,
    val evidenceType: String,
    val scheduleCondition: String,
    val confidenceSource: Double,
    val confidenceIdentity: Double,
    val confidenceInstallment: Double,
    val confidenceLanguageTrack: Double,
    val confidenceTiming: Double,
)

@Entity(
    tableName = "v3_release_decision",
    indices = [
        Index(
            value = ["phase", "authority"],
            name = "idx_v3_decision_phase_authority",
        ),
        Index(value = ["decidedAt"], name = "idx_v3_decision_decided"),
        Index(
            value = ["languageTrack", "phase"],
            name = "idx_v3_decision_track_phase",
        ),
    ],
)
data class ReleaseDecisionEntity(
    @androidx.room.PrimaryKey val identityKey: String,
    val siteIdentifierPayload: String?,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installmentPayload: String,
    val languageTrack: String?,
    val phase: String,
    val scheduleCondition: String,
    val authority: String,
    val contributingEvidenceIdsPayload: String,
    val authoritativeEvidenceIdsPayload: String,
    val releaseAt: String?,
    val lastObservedAt: String?,
    val decidedAt: String,
    val revision: Long,
    val diagnosticsPayload: String,
)

@Entity(
    tableName = "v3_source_health",
    indices = [
        Index(
            value = ["status", "lastAttemptAt"],
            name = "idx_v3_health_status_attempt",
        ),
    ],
)
data class SourceHealthEntity(
    @androidx.room.PrimaryKey val sourceType: String,
    val status: String,
    val lastAttemptAt: String?,
    val lastSuccessAt: String?,
    val consecutiveFailures: Int,
    val parserVersion: String?,
    val sourceHash: String?,
    val diagnostic: String?,
)

@Entity(
    tableName = "v3_forecast_revision",
    indices = [
        Index(
            value = ["identityKey", "observedAt"],
            name = "idx_v3_forecast_identity_observed",
        ),
        Index(
            value = ["evidenceId"],
            unique = true,
            name = "idx_v3_forecast_evidence",
        ),
    ],
)
data class ReleaseForecastRevisionEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val revisionId: Long = 0,
    val identityKey: String,
    val evidenceId: String,
    val forecastAt: String,
    val observedAt: String,
    val approximateTime: Boolean,
    val sourceHash: String,
    val parserVersion: String,
)

@Entity(
    tableName = "v3_evidence_alias",
    foreignKeys = [
        ForeignKey(
            entity = ReleaseEvidenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonicalEvidenceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["canonicalEvidenceId"], name = "idx_v3_alias_canonical_evidence"),
        Index(value = ["canonicalFingerprint"], name = "idx_v3_alias_fingerprint"),
    ],
)
data class ReleaseEvidenceAliasEntity(
    @androidx.room.PrimaryKey val aliasId: String,
    val canonicalEvidenceId: String,
    val canonicalFingerprint: String,
    val aliasKind: String,
    val createdAt: String,
)

@Entity(
    tableName = "v3_evidence_duplicate_archive",
    foreignKeys = [
        ForeignKey(
            entity = ReleaseEvidenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonicalEvidenceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["canonicalEvidenceId"], name = "idx_v3_evidence_archive_canonical"),
    ],
)
data class ReleaseEvidenceDuplicateArchiveEntity(
    @androidx.room.PrimaryKey val originalId: String,
    val canonicalEvidenceId: String,
    val canonicalFingerprint: String,
    val identityKey: String,
    val sourceType: String,
    val sourceUrl: String,
    val sourceHash: String,
    val parserVersion: String,
    val observedAt: String,
    val sourceReportedAt: String?,
    val approximateTime: Boolean,
    val siteIdentifierPayload: String?,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installmentPayload: String,
    val languageTrack: String?,
    val evidenceType: String,
    val scheduleCondition: String,
    val confidenceSource: Double,
    val confidenceIdentity: Double,
    val confidenceInstallment: Double,
    val confidenceLanguageTrack: Double,
    val confidenceTiming: Double,
    val archivedAt: String,
)

@Entity(
    tableName = "v3_forecast_revision_archive",
    foreignKeys = [
        ForeignKey(
            entity = ReleaseForecastRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["canonicalRevisionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["canonicalRevisionId"], name = "idx_v3_forecast_archive_canonical"),
    ],
)
data class ReleaseForecastRevisionArchiveEntity(
    @androidx.room.PrimaryKey val originalRevisionId: Long,
    val canonicalRevisionId: Long,
    val identityKey: String,
    val evidenceId: String,
    val forecastAt: String,
    val observedAt: String,
    val approximateTime: Boolean,
    val sourceHash: String,
    val parserVersion: String,
    val archivedAt: String,
)

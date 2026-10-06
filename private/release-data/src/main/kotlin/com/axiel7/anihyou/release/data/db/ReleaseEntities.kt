package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "provider_snapshot",
    primaryKeys = ["streamKey"],
    indices = [
        Index(value = ["providerId", "lastSuccessAt"], name = "idx_provider_snapshot_success"),
        Index(value = ["sourceHash"], name = "idx_provider_snapshot_source_hash"),
    ],
)
data class ProviderSnapshotEntity(
    val streamKey: String,
    val providerId: String,
    val stableSeriesKey: String,
    val releaseKind: String,
    val sourceSeason: Int?,
    val languageTrack: String,
    val confirmationsPayload: String,
    val forecastsPayload: String,
    val freshnessStatus: String,
    val lastAttemptAt: String?,
    val lastSuccessAt: String?,
    val freshnessObservedAt: String?,
    val parserVersion: String?,
    val sourceHash: String?,
    val freshnessDiagnostic: String?,
    val mappingPayload: String?,
    val sourcePresent: Boolean,
    val sourceRoot: String?,
    val snapshotObservedAt: String,
)

@Entity(
    tableName = "source_release_observation",
    primaryKeys = ["identityKey", "observedAt"],
    indices = [
        Index(value = ["streamKey", "sourceDate"], name = "idx_observation_stream_date"),
        Index(value = ["sourceRoot"], name = "idx_observation_source_root"),
    ],
)
data class SourceReleaseObservationEntity(
    val identityKey: String,
    val observedAt: String,
    val streamKey: String,
    val streamPayload: String?,
    val installmentPayload: String,
    val track: String,
    val confirmed: Boolean,
    val forecastAt: String?,
    val sourceDate: String?,
    val sourceTime: String?,
    val sourceZone: String?,
    val approximate: Boolean,
    val sourceRoot: String,
    val rawTokensPayload: String,
)

@Entity(
    tableName = "release_mapping",
    primaryKeys = ["streamKey"],
    indices = [
        Index(value = ["mediaId", "confidence"], name = "idx_release_mapping_media_confidence"),
    ],
)
data class ReleaseMappingEntity(
    val streamKey: String,
    val mediaId: Int?,
    val confidence: String?,
    val score: Double?,
    val runnerUpMargin: Double?,
    val evidence: String?,
    val matcherVersion: String?,
    val origin: String?,
    val updatedAt: String,
)

@Entity(
    tableName = "account_library_state",
    primaryKeys = ["accountId", "mediaId"],
    indices = [
        Index(value = ["accountId", "updatedAt"], name = "idx_library_state_account_updated"),
    ],
)
data class AccountLibraryStateEntity(
    val accountId: Long,
    val mediaId: Int,
    val statePayload: String,
    val updatedAt: String,
)

@Entity(
    tableName = "media_release_projection",
    primaryKeys = ["projectionKey"],
    indices = [
        Index(value = ["accountId", "mediaId"], name = "idx_media_projection_account_media"),
        Index(
            value = ["accountId", "authority", "pendingCount"],
            name = "idx_media_projection_account_authority_pending",
        ),
        Index(value = ["accountId", "sourceRoot"], name = "idx_media_projection_account_source"),
        Index(value = ["accountId", "nextForecastAt"], name = "idx_media_projection_account_forecast"),
        Index(value = ["revision"], name = "idx_media_projection_revision"),
    ],
)
data class MediaReleaseProjectionEntity(
    val projectionKey: String,
    val accountId: Long?,
    val mediaId: Int?,
    val streamPayload: String,
    val authority: String,
    val confirmedThroughEpisode: Int?,
    val confirmedInstallmentsPayload: String,
    val nextForecastPayload: String?,
    val nextForecastAt: String?,
    val pendingCount: Int,
    val freshnessPayload: String,
    val mappingPayload: String?,
    val sourceRoot: String?,
    val revision: Long,
    val diagnosticsPayload: String,
)

@Entity(
    tableName = "calendar_release_projection",
    primaryKeys = ["projectionKey"],
    indices = [
        Index(value = ["accountId", "sourceDate"], name = "idx_calendar_projection_account_date"),
        Index(value = ["accountId", "mediaId"], name = "idx_calendar_projection_account_media"),
        Index(value = ["revision"], name = "idx_calendar_projection_revision"),
    ],
)
data class CalendarReleaseProjectionEntity(
    val projectionKey: String,
    val accountId: Long?,
    val mediaId: Int?,
    val streamPayload: String,
    val installmentPayload: String,
    val forecastAt: String?,
    val confirmed: Boolean,
    val authority: String,
    val revision: Long,
    val sourceDate: String?,
)

@Entity(tableName = "schema_meta")
data class SchemaMetaEntity(
    @androidx.room.PrimaryKey val key: String,
    val schemaVersion: Int,
    val value: String?,
    val updatedAt: String,
)

@Entity(
    tableName = "sync_diagnostic",
    primaryKeys = ["diagnosticKey"],
    indices = [
        Index(value = ["accountId", "createdAt"], name = "idx_sync_diagnostic_account_created"),
        Index(value = ["code"], name = "idx_sync_diagnostic_code"),
    ],
)
data class SyncDiagnosticEntity(
    val diagnosticKey: String,
    val accountId: Long?,
    val code: String,
    val message: String,
    val createdAt: String,
    val recoverable: Boolean,
)

@Entity(
    tableName = "forecast_recheck_work",
    indices = [
        Index(value = ["streamKey", "runAt"], name = "idx_forecast_recheck_stream_run"),
        Index(value = ["runAt"], name = "idx_forecast_recheck_run"),
    ],
)
data class ForecastRecheckWorkEntity(
    @androidx.room.PrimaryKey val workKey: String,
    val identityKey: String,
    val streamKey: String,
    val streamPayload: String,
    val installmentPayload: String,
    val forecastAt: String,
    val runAt: String,
    val updatedAt: String,
)

package com.axiel7.anihyou.release.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val RELEASE_DATABASE_VERSION = 8

@Database(
    entities = [
        ProviderSnapshotEntity::class,
        SourceReleaseObservationEntity::class,
        ReleaseMappingEntity::class,
        AccountLibraryStateEntity::class,
        MediaReleaseProjectionEntity::class,
        CalendarReleaseProjectionEntity::class,
        SchemaMetaEntity::class,
        SyncDiagnosticEntity::class,
        IdentityCandidateEntity::class,
        LookupCacheEntity::class,
        SyncGenerationEntity::class,
        NotificationOutboxEntity::class,
        ForecastRecheckWorkEntity::class,
    ],
    version = RELEASE_DATABASE_VERSION,
    exportSchema = true,
)
abstract class ReleaseDatabase : RoomDatabase() {
    abstract fun releaseDao(): ReleaseDao
}

val RELEASE_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS provider_snapshot (
                streamKey TEXT NOT NULL,
                providerId TEXT NOT NULL,
                stableSeriesKey TEXT NOT NULL,
                releaseKind TEXT NOT NULL,
                sourceSeason INTEGER,
                languageTrack TEXT NOT NULL,
                confirmationsPayload TEXT NOT NULL,
                forecastsPayload TEXT NOT NULL,
                freshnessStatus TEXT NOT NULL,
                lastAttemptAt TEXT,
                lastSuccessAt TEXT,
                freshnessObservedAt TEXT,
                parserVersion TEXT,
                sourceHash TEXT,
                freshnessDiagnostic TEXT,
                mappingPayload TEXT,
                sourcePresent INTEGER NOT NULL,
                sourceRoot TEXT,
                snapshotObservedAt TEXT NOT NULL,
                PRIMARY KEY(streamKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS source_release_observation (
                identityKey TEXT NOT NULL,
                observedAt TEXT NOT NULL,
                streamKey TEXT NOT NULL,
                streamPayload TEXT,
                installmentPayload TEXT NOT NULL,
                track TEXT NOT NULL,
                confirmed INTEGER NOT NULL,
                forecastAt TEXT,
                sourceDate TEXT,
                sourceTime TEXT,
                sourceZone TEXT,
                approximate INTEGER NOT NULL,
                sourceRoot TEXT NOT NULL,
                rawTokensPayload TEXT NOT NULL,
                PRIMARY KEY(identityKey, observedAt)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS release_mapping (
                streamKey TEXT NOT NULL,
                mediaId INTEGER,
                confidence TEXT,
                score REAL,
                runnerUpMargin REAL,
                evidence TEXT,
                matcherVersion TEXT,
                origin TEXT,
                updatedAt TEXT NOT NULL,
                PRIMARY KEY(streamKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS account_library_state (
                accountId INTEGER NOT NULL,
                mediaId INTEGER NOT NULL,
                statePayload TEXT NOT NULL,
                updatedAt TEXT NOT NULL,
                PRIMARY KEY(accountId, mediaId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_release_projection (
                projectionKey TEXT NOT NULL,
                accountId INTEGER,
                mediaId INTEGER,
                streamPayload TEXT NOT NULL,
                authority TEXT NOT NULL,
                confirmedThroughEpisode INTEGER,
                confirmedInstallmentsPayload TEXT NOT NULL,
                nextForecastPayload TEXT,
                nextForecastAt TEXT,
                pendingCount INTEGER NOT NULL,
                freshnessPayload TEXT NOT NULL,
                mappingPayload TEXT,
                sourceRoot TEXT,
                revision INTEGER NOT NULL,
                diagnosticsPayload TEXT NOT NULL,
                PRIMARY KEY(projectionKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS calendar_release_projection (
                projectionKey TEXT NOT NULL,
                accountId INTEGER,
                mediaId INTEGER,
                streamPayload TEXT NOT NULL,
                installmentPayload TEXT NOT NULL,
                forecastAt TEXT,
                confirmed INTEGER NOT NULL,
                authority TEXT NOT NULL,
                revision INTEGER NOT NULL,
                sourceDate TEXT,
                PRIMARY KEY(projectionKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS schema_meta (
                key TEXT NOT NULL,
                schemaVersion INTEGER NOT NULL,
                value TEXT,
                updatedAt TEXT NOT NULL,
                PRIMARY KEY(key)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_diagnostic (
                diagnosticKey TEXT NOT NULL,
                accountId INTEGER,
                code TEXT NOT NULL,
                message TEXT NOT NULL,
                createdAt TEXT NOT NULL,
                recoverable INTEGER NOT NULL,
                PRIMARY KEY(diagnosticKey)
            )
            """.trimIndent(),
        )

        if (!db.hasColumn("source_release_observation", "streamPayload")) {
            db.execSQL(
                "ALTER TABLE source_release_observation ADD COLUMN streamPayload TEXT",
            )
        }
        if (!db.hasColumn("media_release_projection", "nextForecastAt")) {
            db.execSQL(
                "ALTER TABLE media_release_projection ADD COLUMN nextForecastAt TEXT",
            )
        }

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_provider_snapshot_success " +
                "ON provider_snapshot(providerId, lastSuccessAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_provider_snapshot_source_hash " +
                "ON provider_snapshot(sourceHash)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_observation_stream_date " +
                "ON source_release_observation(streamKey, sourceDate)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_observation_source_root " +
                "ON source_release_observation(sourceRoot)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_release_mapping_media_confidence " +
                "ON release_mapping(mediaId, confidence)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_library_state_account_updated " +
                "ON account_library_state(accountId, updatedAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_media_projection_account_media " +
                "ON media_release_projection(accountId, mediaId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_media_projection_account_authority_pending " +
                "ON media_release_projection(accountId, authority, pendingCount)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_media_projection_account_source " +
                "ON media_release_projection(accountId, sourceRoot)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_media_projection_account_forecast " +
                "ON media_release_projection(accountId, nextForecastAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_media_projection_revision " +
                "ON media_release_projection(revision)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_calendar_projection_account_date " +
                "ON calendar_release_projection(accountId, sourceDate)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_calendar_projection_account_media " +
                "ON calendar_release_projection(accountId, mediaId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_calendar_projection_revision " +
                "ON calendar_release_projection(revision)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_sync_diagnostic_account_created " +
                "ON sync_diagnostic(accountId, createdAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_sync_diagnostic_code " +
                "ON sync_diagnostic(code)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 2, 'wp03', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}

private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
    query("PRAGMA table_info(" + tableName + ")").use { cursor ->
        val nameColumn = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameColumn >= 0 && cursor.getString(nameColumn) == columnName) return true
        }
    }
    return false
}


val RELEASE_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS identity_candidate (
                sourceKey TEXT NOT NULL,
                mediaId INTEGER NOT NULL,
                titlesPayload TEXT NOT NULL,
                format TEXT,
                startDate TEXT,
                fetchedAt TEXT NOT NULL,
                expiresAt TEXT NOT NULL,
                PRIMARY KEY(sourceKey, mediaId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS lookup_cache (
                cacheKey TEXT NOT NULL,
                sourceKey TEXT NOT NULL,
                query TEXT NOT NULL,
                signature TEXT NOT NULL,
                resultPayload TEXT NOT NULL,
                fetchedAt TEXT NOT NULL,
                expiresAt TEXT NOT NULL,
                PRIMARY KEY(cacheKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_identity_candidate_source_expiry " +
                "ON identity_candidate(sourceKey, expiresAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_identity_candidate_media " +
                "ON identity_candidate(mediaId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_lookup_cache_source_expiry " +
                "ON lookup_cache(sourceKey, expiresAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_lookup_cache_signature_expiry " +
                "ON lookup_cache(signature, expiresAt)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 3, 'wp04', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}


val RELEASE_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_generation (
                generationKey TEXT NOT NULL,
                generation INTEGER NOT NULL,
                updatedAt TEXT NOT NULL,
                PRIMARY KEY(generationKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 4, 'wp05', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}


val RELEASE_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_outbox (
                eventKey TEXT NOT NULL,
                accountId INTEGER NOT NULL,
                mediaId INTEGER NOT NULL,
                eventKind TEXT NOT NULL,
                identityKey TEXT NOT NULL,
                payload TEXT NOT NULL,
                evidenceKind TEXT NOT NULL,
                observedAt TEXT NOT NULL,
                status TEXT NOT NULL,
                attemptCount INTEGER NOT NULL,
                createdAt TEXT NOT NULL,
                nextAttemptAt TEXT,
                deliveredAt TEXT,
                lastError TEXT,
                PRIMARY KEY(eventKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_notification_outbox_account_status_attempt " +
                "ON notification_outbox(accountId, status, nextAttemptAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_notification_outbox_created " +
                "ON notification_outbox(createdAt)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 5, 'wp06', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}

val RELEASE_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("notification_outbox", "postingLeaseUntil")) {
            db.execSQL(
                "ALTER TABLE notification_outbox ADD COLUMN postingLeaseUntil TEXT",
            )
        }
        if (!db.hasColumn("notification_outbox", "cancelledAt")) {
            db.execSQL(
                "ALTER TABLE notification_outbox ADD COLUMN cancelledAt TEXT",
            )
        }
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_notification_outbox_status_lease " +
                "ON notification_outbox(status, postingLeaseUntil)",
        )
        db.execSQL(
            "UPDATE notification_outbox SET status = 'PENDING' " +
                "WHERE status = 'RETRY'",
        )
        db.execSQL(
            "UPDATE notification_outbox SET status = 'CANCELLED', " +
                "cancelledAt = COALESCE(cancelledAt, createdAt), " +
                "nextAttemptAt = NULL " +
                "WHERE status = 'FAILED'",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 6, 'wp06-canonical', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}


val RELEASE_MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS forecast_recheck_work (
                workKey TEXT NOT NULL,
                identityKey TEXT NOT NULL,
                streamKey TEXT NOT NULL,
                streamPayload TEXT NOT NULL,
                installmentPayload TEXT NOT NULL,
                forecastAt TEXT NOT NULL,
                runAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL,
                PRIMARY KEY(workKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_forecast_recheck_stream_run " +
                "ON forecast_recheck_work(streamKey, runAt)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_forecast_recheck_run " +
                "ON forecast_recheck_work(runAt)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 7, 'forecast-recheck', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}

val RELEASE_MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("notification_outbox", "displayTitle")) {
            db.execSQL("ALTER TABLE notification_outbox ADD COLUMN displayTitle TEXT")
        }
        if (!db.hasColumn("notification_outbox", "installmentPayload")) {
            db.execSQL("ALTER TABLE notification_outbox ADD COLUMN installmentPayload TEXT")
        }
        if (!db.hasColumn("notification_outbox", "track")) {
            db.execSQL("ALTER TABLE notification_outbox ADD COLUMN track TEXT")
        }
        db.execSQL(
            "INSERT OR REPLACE INTO schema_meta(key, schemaVersion, value, updatedAt) " +
                "VALUES('release_schema', 8, 'typed-release-outbox', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))",
        )
    }
}

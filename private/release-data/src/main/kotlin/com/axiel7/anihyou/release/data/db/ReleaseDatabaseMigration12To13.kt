package com.axiel7.anihyou.release.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val RELEASE_MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) = migrateReleaseDatabase12To13(db)
}

internal fun migrateReleaseDatabase12To13(
    db: SupportSQLiteDatabase,
    afterFirstCreateForTest: (() -> Unit)? = null,
) {
        db.query("SELECT schemaVersion FROM schema_meta WHERE key='release_schema'").use { c ->
            check(c.moveToFirst() && c.getInt(0) == 12 && !c.moveToNext()) { "expected exactly one v12 release_schema marker" }
        }
        val tableNames = listOf("v3_poll_generation", "v3_http_attempt", "v3_request_state", "v3_shadow_metric")
        tableNames.forEach { name ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use { check(!it.moveToNext()) { "unexpected v13 table $name" } }
        }
        val indexNames = listOf(
            "idx_v3_poll_generation_scope_state", "idx_v3_poll_generation_cycle",
            "idx_v3_http_attempt_root_time", "idx_v3_request_state_eligible",
            "idx_v3_request_state_active", "idx_v3_shadow_metric_recorded",
        )
        indexNames.forEach { name ->
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$name'").use {
                check(!it.moveToNext()) { "unexpected v13 index $name" }
            }
        }
        db.execSQL("""CREATE TABLE v3_poll_generation (generationId TEXT NOT NULL, scopeId TEXT NOT NULL, ownerToken TEXT NOT NULL,
            processEpoch TEXT NOT NULL, policyVersion INTEGER NOT NULL, startedAt TEXT NOT NULL, deadlineAt TEXT NOT NULL,
            completedAt TEXT, state TEXT NOT NULL, manifestVersion INTEGER NOT NULL, manifestPayload TEXT NOT NULL,
            manifestDigest TEXT NOT NULL, directUrlCount INTEGER NOT NULL, directReserved INTEGER NOT NULL, listReserved INTEGER NOT NULL,
            outcome TEXT, reason TEXT, cycleId TEXT, PRIMARY KEY(generationId), FOREIGN KEY(cycleId) REFERENCES v3_observation_cycle(cycleId)
            ON UPDATE RESTRICT ON DELETE RESTRICT)""")
        afterFirstCreateForTest?.invoke()
        db.execSQL("CREATE INDEX idx_v3_poll_generation_scope_state ON v3_poll_generation(scopeId,state,startedAt)")
        db.execSQL("CREATE INDEX idx_v3_poll_generation_cycle ON v3_poll_generation(cycleId)")
        db.execSQL("""CREATE TABLE v3_http_attempt (generationId TEXT NOT NULL, ordinal INTEGER NOT NULL, rootUrl TEXT NOT NULL,
            requestUrl TEXT NOT NULL, role TEXT NOT NULL, reservedAt TEXT NOT NULL, completedAt TEXT, outcome TEXT NOT NULL,
            retryAfterSeconds INTEGER, elapsedMillis INTEGER, PRIMARY KEY(generationId,ordinal), FOREIGN KEY(generationId)
            REFERENCES v3_poll_generation(generationId) ON UPDATE RESTRICT ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX idx_v3_http_attempt_root_time ON v3_http_attempt(rootUrl,reservedAt)")
        db.execSQL("""CREATE TABLE v3_request_state (scopeKey TEXT NOT NULL, firstEligibleAt TEXT, lastAttemptAt TEXT, lastSuccessAt TEXT,
            failureCount INTEGER NOT NULL, nextEligibleAt TEXT, lastAppliedGeneration TEXT, lastAppliedOrdinal INTEGER,
            activeGenerationId TEXT, policyVersion INTEGER NOT NULL, PRIMARY KEY(scopeKey), FOREIGN KEY(activeGenerationId)
            REFERENCES v3_poll_generation(generationId) ON UPDATE RESTRICT ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX idx_v3_request_state_eligible ON v3_request_state(nextEligibleAt)")
        db.execSQL("CREATE INDEX idx_v3_request_state_active ON v3_request_state(activeGenerationId)")
        db.execSQL("""CREATE TABLE v3_shadow_metric (generationId TEXT NOT NULL, recordedAt TEXT NOT NULL, payloadVersion INTEGER NOT NULL,
            payload TEXT NOT NULL, PRIMARY KEY(generationId), FOREIGN KEY(generationId) REFERENCES v3_poll_generation(generationId)
            ON UPDATE RESTRICT ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX idx_v3_shadow_metric_recorded ON v3_shadow_metric(recordedAt)")
        db.execSQL("UPDATE schema_meta SET schemaVersion=13,value='wp04b-shadow-control',updatedAt=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE key='release_schema' AND schemaVersion=12")
        db.query("SELECT COUNT(*) FROM schema_meta WHERE key='release_schema' AND schemaVersion=13").use { c ->
            check(c.moveToFirst() && c.getInt(0) == 1) { "release_schema marker failed to advance to v13" }
        }
}

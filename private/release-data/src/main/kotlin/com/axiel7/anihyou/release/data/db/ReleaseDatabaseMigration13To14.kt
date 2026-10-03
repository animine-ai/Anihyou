package com.axiel7.anihyou.release.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val RELEASE_MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) = migrateReleaseDatabase13To14(db)
}

/**
 * R04: additive, source-bound provenance. Nothing existing is rewritten or deleted. Cycles and
 * canonical rows from before this version have no provenance and no per-source rows, so their origin
 * stays unknown (inactive for the extension presentation) until a valid new commit of a source folds
 * its own rows; global rows, evidence, mappings and journals are preserved byte for byte.
 */
internal fun migrateReleaseDatabase13To14(
    db: SupportSQLiteDatabase,
    afterFirstCreateForTest: (() -> Unit)? = null,
) {
    db.query("SELECT schemaVersion FROM schema_meta WHERE key='release_schema'").use { c ->
        check(c.moveToFirst() && c.getInt(0) == 13 && !c.moveToNext()) { "expected exactly one v13 release_schema marker" }
    }
    listOf("v3_cycle_provenance", "v3_source_projection").forEach { name ->
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'").use {
            check(!it.moveToNext()) { "unexpected v14 table $name" }
        }
    }
    listOf("idx_v3_provenance_source", "idx_v3_source_projection_bucket").forEach { name ->
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$name'").use {
            check(!it.moveToNext()) { "unexpected v14 index $name" }
        }
    }
    db.execSQL("""CREATE TABLE v3_cycle_provenance (cycleId TEXT NOT NULL, sourceId TEXT NOT NULL, extensionId TEXT NOT NULL,
        publisherId TEXT NOT NULL, providerId TEXT NOT NULL, commitSequence INTEGER NOT NULL, completedAt TEXT NOT NULL,
        PRIMARY KEY(cycleId), FOREIGN KEY(cycleId) REFERENCES v3_observation_cycle(cycleId) ON UPDATE RESTRICT ON DELETE RESTRICT)""")
    afterFirstCreateForTest?.invoke()
    db.execSQL("CREATE INDEX idx_v3_provenance_source ON v3_cycle_provenance(sourceId,extensionId,publisherId,providerId,commitSequence)")
    db.execSQL("""CREATE TABLE v3_source_projection (sourceId TEXT NOT NULL, extensionId TEXT NOT NULL, publisherId TEXT NOT NULL,
        providerId TEXT NOT NULL, projectionKey TEXT NOT NULL, bucketKey TEXT NOT NULL, underlyingPhase TEXT NOT NULL,
        phase TEXT NOT NULL, authority TEXT NOT NULL, scheduleCondition TEXT NOT NULL, scheduleEvidenceId TEXT, releaseAt TEXT,
        forecastAt TEXT, forecastEvidenceId TEXT, bindingKey TEXT, conflictIdsPayload TEXT NOT NULL, revision INTEGER NOT NULL,
        lastAppliedSequence INTEGER NOT NULL, absenceCount INTEGER NOT NULL, lastAbsenceAt TEXT, expectationEvidenceId TEXT,
        navigationPayload TEXT NOT NULL, latestCompletedAt TEXT,
        PRIMARY KEY(sourceId,extensionId,publisherId,providerId,projectionKey))""")
    db.execSQL("CREATE INDEX idx_v3_source_projection_bucket ON v3_source_projection(sourceId,extensionId,publisherId,providerId,bucketKey)")
    db.execSQL("UPDATE schema_meta SET schemaVersion=14,value='r04-source-provenance',updatedAt=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE key='release_schema' AND schemaVersion=13")
    db.query("SELECT COUNT(*) FROM schema_meta WHERE key='release_schema' AND schemaVersion=14").use { c ->
        check(c.moveToFirst() && c.getInt(0) == 1) { "release_schema marker failed to advance to v14" }
    }
}

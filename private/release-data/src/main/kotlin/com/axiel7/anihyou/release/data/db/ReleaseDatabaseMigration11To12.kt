package com.axiel7.anihyou.release.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val RELEASE_MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("SELECT schemaVersion FROM schema_meta WHERE key = 'release_schema'").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 11 && !cursor.moveToNext()) {
                "expected exactly one v11 release_schema marker"
            }
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS v3_observation_cycle (
            cycleId TEXT NOT NULL, commitSequence INTEGER NOT NULL, scopeId TEXT NOT NULL,
            policyVersion INTEGER NOT NULL, policyPayload TEXT NOT NULL, startedAt TEXT NOT NULL,
            completedAt TEXT NOT NULL, completeness TEXT NOT NULL, requestDigest TEXT NOT NULL,
            PRIMARY KEY(cycleId))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_v3_cycle_sequence ON v3_observation_cycle(commitSequence)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_cycle_scope_completion ON v3_observation_cycle(scopeId, completedAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS v3_cycle_source_observation (
            cycleId TEXT NOT NULL, sourceInstanceId TEXT NOT NULL, sourceType TEXT NOT NULL,
            target TEXT NOT NULL, track TEXT, result TEXT NOT NULL, health TEXT NOT NULL,
            coverage TEXT NOT NULL, presence TEXT NOT NULL, observedAt TEXT,
            supersedesEvidenceId TEXT,
            PRIMARY KEY(cycleId, sourceInstanceId),
            FOREIGN KEY(cycleId) REFERENCES v3_observation_cycle(cycleId)
                ON UPDATE RESTRICT ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_source_target_cycle ON v3_cycle_source_observation(target, cycleId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS v3_cycle_evidence_receipt (
            cycleId TEXT NOT NULL, sourceInstanceId TEXT NOT NULL, canonicalEvidenceId TEXT NOT NULL,
            PRIMARY KEY(cycleId, sourceInstanceId, canonicalEvidenceId),
            FOREIGN KEY(cycleId, sourceInstanceId) REFERENCES v3_cycle_source_observation(cycleId, sourceInstanceId)
                ON UPDATE RESTRICT ON DELETE RESTRICT,
            FOREIGN KEY(canonicalEvidenceId) REFERENCES v3_release_evidence(id)
                ON UPDATE RESTRICT ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_receipt_evidence ON v3_cycle_evidence_receipt(canonicalEvidenceId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS v3_reconciliation_event (
            eventId TEXT NOT NULL, commitSequence INTEGER NOT NULL, eventOrdinal INTEGER NOT NULL,
            kind TEXT NOT NULL, payloadVersion INTEGER NOT NULL, projectionKey TEXT NOT NULL,
            partialEvidenceId TEXT, fromKey TEXT, toKey TEXT, causeCycleId TEXT,
            policyVersion INTEGER NOT NULL, beforeRevision INTEGER NOT NULL,
            afterRevision INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(eventId))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_v3_event_order ON v3_reconciliation_event(commitSequence, eventOrdinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_event_projection ON v3_reconciliation_event(projectionKey, commitSequence, eventOrdinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_event_partial ON v3_reconciliation_event(partialEvidenceId, commitSequence)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS v3_canonical_release_projection (
            projectionKey TEXT NOT NULL, bucketKey TEXT NOT NULL, underlyingPhase TEXT NOT NULL,
            phase TEXT NOT NULL, authority TEXT NOT NULL, scheduleCondition TEXT NOT NULL,
            releaseAt TEXT, forecastAt TEXT, forecastEvidenceId TEXT, bindingKey TEXT,
            conflictIdsPayload TEXT NOT NULL, revision INTEGER NOT NULL,
            lastAppliedSequence INTEGER NOT NULL, absenceCount INTEGER NOT NULL,
            lastAbsenceAt TEXT, expectationEvidenceId TEXT, PRIMARY KEY(projectionKey))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_canonical_phase_authority ON v3_canonical_release_projection(phase, authority)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_v3_canonical_bucket ON v3_canonical_release_projection(bucketKey)")
        db.execSQL("UPDATE schema_meta SET schemaVersion = 12, value = 'wp04a5-journal', " +
            "updatedAt = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE key = 'release_schema'")
    }
}

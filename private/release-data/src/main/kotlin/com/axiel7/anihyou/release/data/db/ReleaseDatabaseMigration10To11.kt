package com.axiel7.anihyou.release.data.db

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseForecastRevision
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import java.time.Instant
import java.util.Locale

private const val MIGRATION_ARCHIVE_TIMESTAMP_SQL =
    "strftime('%Y-%m-%dT%H:%M:%fZ', 'now')"

private const val EVIDENCE_TABLE = "v3_release_evidence"
private const val DECISION_TABLE = "v3_release_decision"
private const val SOURCE_HEALTH_TABLE = "v3_source_health"
private const val FORECAST_TABLE = "v3_forecast_revision"

private enum class HistoricalIdKind {
    LEGACY,
    V2,
    OPAQUE,
}

private data class EvidenceSnapshot(
    val raw: ReleaseEvidenceEntity,
    val domain: ReleaseEvidence,
    val fingerprint: String,
    val idKind: HistoricalIdKind,
)

private data class DecisionRewrite(
    val original: ReleaseDecisionEntity,
    val rewritten: ReleaseDecisionEntity,
)

private data class RevisionSnapshot(
    val raw: ReleaseForecastRevisionEntity,
    val canonicalEvidenceId: String,
)

private data class RevisionRewrite(
    val active: ReleaseForecastRevisionEntity,
    val archived: List<ReleaseForecastRevisionEntity>,
)

/** Strict, transactional consolidation of the v10 Evidence key space. */
val RELEASE_MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        preflightSchema(db)
        val timestamp = migrationTimestamp(db)
        val evidence = readEvidence(db)
        val groups = evidence.groupBy(EvidenceSnapshot::fingerprint)
        val canonicalByFingerprint = groups.mapValues { (_, rows) -> selectCanonical(rows) }
        val canonicalByOldId = linkedMapOf<String, EvidenceSnapshot>()
        val displacedRows = mutableListOf<Pair<EvidenceSnapshot, EvidenceSnapshot>>()
        evidence.forEach { row ->
            val canonical = canonicalByFingerprint.getValue(row.fingerprint)
            require(canonicalByOldId.put(row.raw.id, canonical) == null) {
                "duplicate historical Evidence ID"
            }
            if (row.raw.id != canonical.raw.id) displacedRows += row to canonical
        }

        val decisionRewrites = readAndPlanDecisions(db, canonicalByOldId)
        val revisionRewrites = readAndPlanRevisions(db, canonicalByOldId)
        val originalRevisionSequence = readRevisionSequence(db)

        replaceEvidenceTable(db, canonicalByFingerprint.values, timestamp)
        writeDecisionRewrites(db, decisionRewrites)
        applyRevisionRewrites(db, revisionRewrites)
        ensureRevisionSequence(db, originalRevisionSequence)
        createAliasAndArchiveTables(db)
        writeAliasesAndEvidenceArchives(db, displacedRows, timestamp)
        writeRevisionArchives(db, revisionRewrites, timestamp)
        createV11Indexes(db)
        validateMigratedRows(
            db = db,
            canonicalByFingerprint = canonicalByFingerprint,
            canonicalByOldId = canonicalByOldId,
            displacedRows = displacedRows,
            decisionRewrites = decisionRewrites,
            revisionRewrites = revisionRewrites,
        )

        db.execSQL(
            "UPDATE schema_meta SET schemaVersion = 11, value = ?, updatedAt = " +
                MIGRATION_ARCHIVE_TIMESTAMP_SQL +
                " WHERE key = 'release_schema' AND schemaVersion = 10",
            arrayOf("wp04a4-fingerprint-alias"),
        )
        db.query(
            "SELECT schemaVersion, value FROM schema_meta WHERE key = 'release_schema'",
        ).use { cursor ->
            require(cursor.moveToFirst()) { "release schema metadata disappeared" }
            require(cursor.getLong(0) == 11L && cursor.getString(1) == "wp04a4-fingerprint-alias") {
                "release schema metadata did not advance to v11"
            }
            require(!cursor.moveToNext()) { "duplicate release schema metadata" }
        }
    }
}

private fun preflightSchema(db: SupportSQLiteDatabase) {
    val expectedEvidence = listOf(
        col("id", "TEXT", notNull = true, primaryKey = 1),
        col("identityKey", "TEXT", true),
        col("sourceType", "TEXT", true),
        col("sourceUrl", "TEXT", true),
        col("sourceHash", "TEXT", true),
        col("parserVersion", "TEXT", true),
        col("observedAt", "TEXT", true),
        col("sourceReportedAt", "TEXT", false),
        col("approximateTime", "INTEGER", true),
        col("siteIdentifierPayload", "TEXT", false),
        col("sourceSeason", "INTEGER", false),
        col("navigationSeason", "INTEGER", false),
        col("installmentPayload", "TEXT", true),
        col("languageTrack", "TEXT", false),
        col("evidenceType", "TEXT", true),
        col("scheduleCondition", "TEXT", true),
        col("confidenceSource", "REAL", true),
        col("confidenceIdentity", "REAL", true),
        col("confidenceInstallment", "REAL", true),
        col("confidenceLanguageTrack", "REAL", true),
        col("confidenceTiming", "REAL", true),
    )
    val expectedDecision = listOf(
        col("identityKey", "TEXT", true, 1),
        col("siteIdentifierPayload", "TEXT", false),
        col("sourceSeason", "INTEGER", false),
        col("navigationSeason", "INTEGER", false),
        col("installmentPayload", "TEXT", true),
        col("languageTrack", "TEXT", false),
        col("phase", "TEXT", true),
        col("scheduleCondition", "TEXT", true),
        col("authority", "TEXT", true),
        col("contributingEvidenceIdsPayload", "TEXT", true),
        col("authoritativeEvidenceIdsPayload", "TEXT", true),
        col("releaseAt", "TEXT", false),
        col("lastObservedAt", "TEXT", false),
        col("decidedAt", "TEXT", true),
        col("revision", "INTEGER", true),
        col("diagnosticsPayload", "TEXT", true),
    )
    val expectedHealth = listOf(
        col("sourceType", "TEXT", true, 1),
        col("status", "TEXT", true),
        col("lastAttemptAt", "TEXT", false),
        col("lastSuccessAt", "TEXT", false),
        col("consecutiveFailures", "INTEGER", true),
        col("parserVersion", "TEXT", false),
        col("sourceHash", "TEXT", false),
        col("diagnostic", "TEXT", false),
    )
    val expectedForecast = listOf(
        col("revisionId", "INTEGER", true, 1),
        col("identityKey", "TEXT", true),
        col("evidenceId", "TEXT", true),
        col("forecastAt", "TEXT", true),
        col("observedAt", "TEXT", true),
        col("approximateTime", "INTEGER", true),
        col("sourceHash", "TEXT", true),
        col("parserVersion", "TEXT", true),
    )
    val expectedSchemaMeta = listOf(
        col("key", "TEXT", true, 1),
        col("schemaVersion", "INTEGER", true),
        col("value", "TEXT", false),
        col("updatedAt", "TEXT", true),
    )
    assertTableShape(db, EVIDENCE_TABLE, expectedEvidence)
    assertTableShape(db, DECISION_TABLE, expectedDecision)
    assertTableShape(db, SOURCE_HEALTH_TABLE, expectedHealth)
    assertTableShape(db, FORECAST_TABLE, expectedForecast)
    assertTableShape(db, "schema_meta", expectedSchemaMeta)
    readAndValidateSourceHealth(db)

    assertIndex(db, EVIDENCE_TABLE, "idx_v3_evidence_identity_observed", listOf("identityKey", "observedAt"))
    assertIndex(db, EVIDENCE_TABLE, "idx_v3_evidence_source_observed", listOf("sourceType", "observedAt"))
    assertIndex(db, EVIDENCE_TABLE, "idx_v3_evidence_hash", listOf("sourceHash"))
    assertIndex(db, EVIDENCE_TABLE, "idx_v3_evidence_type_observed", listOf("evidenceType", "observedAt"))
    assertIndex(db, DECISION_TABLE, "idx_v3_decision_phase_authority", listOf("phase", "authority"))
    assertIndex(db, DECISION_TABLE, "idx_v3_decision_decided", listOf("decidedAt"))
    assertIndex(db, DECISION_TABLE, "idx_v3_decision_track_phase", listOf("languageTrack", "phase"))
    assertIndex(db, SOURCE_HEALTH_TABLE, "idx_v3_health_status_attempt", listOf("status", "lastAttemptAt"))
    assertIndex(db, FORECAST_TABLE, "idx_v3_forecast_identity_observed", listOf("identityKey", "observedAt"))
    assertIndex(
        db,
        FORECAST_TABLE,
        "idx_v3_forecast_evidence",
        listOf("evidenceId"),
        unique = true,
    )

    listOf(
        "v3_evidence_alias",
        "v3_evidence_duplicate_archive",
        "v3_forecast_revision_archive",
        "v3_release_evidence_v11",
    ).forEach { name ->
        require(!tableExists(db, name)) { "unexpected v11 migration object: $name" }
    }
    assertNoForeignKeysTargetEvidence(db)
    db.query("PRAGMA foreign_key_check").use { cursor ->
        require(!cursor.moveToFirst()) { "v10 database already has foreign key violations" }
    }
    db.query("SELECT schemaVersion, value FROM schema_meta WHERE key = 'release_schema'").use { cursor ->
        require(cursor.moveToFirst()) { "release schema metadata is missing" }
        require(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == 10L)
        require(cursor.getType(1) == Cursor.FIELD_TYPE_STRING && cursor.getString(1) == "wp04a-intelligence")
        require(!cursor.moveToNext()) { "duplicate release schema metadata" }
    }
}

private fun readAndValidateSourceHealth(db: SupportSQLiteDatabase) {
    db.query("SELECT * FROM $SOURCE_HEALTH_TABLE ORDER BY sourceType COLLATE BINARY").use { cursor ->
        while (cursor.moveToNext()) {
            val failures = cursor.strictLong("consecutiveFailures")
            require(failures in 0..Int.MAX_VALUE.toLong()) { "invalid SourceHealth failure count" }
            val row = SourceHealthEntity(
                sourceType = cursor.requiredString("sourceType"),
                status = cursor.requiredString("status"),
                lastAttemptAt = cursor.optionalString("lastAttemptAt"),
                lastSuccessAt = cursor.optionalString("lastSuccessAt"),
                consecutiveFailures = failures.toInt(),
                parserVersion = cursor.optionalString("parserVersion"),
                sourceHash = cursor.optionalString("sourceHash"),
                diagnostic = cursor.optionalString("diagnostic"),
            )
            require(row.toDomainOrNull() != null) { "malformed v10 SourceHealth row" }
        }
    }
}

private fun assertNoForeignKeysTargetEvidence(db: SupportSQLiteDatabase) {
    val tables = mutableListOf<String>()
    db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
        .use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
    tables.forEach { table ->
        db.query("PRAGMA foreign_key_list('${table.replace("'", "''")}')").use { cursor ->
            while (cursor.moveToNext()) {
                require(cursor.getString(cursor.getColumnIndexOrThrow("table")) != EVIDENCE_TABLE) {
                    "unexpected foreign key depends on the v10 Evidence table"
                }
            }
        }
    }
}

private data class ColumnShape(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int = 0,
)

private fun col(name: String, type: String, notNull: Boolean, primaryKey: Int = 0) =
    ColumnShape(name, type, notNull, primaryKey)

private fun assertTableShape(
    db: SupportSQLiteDatabase,
    table: String,
    expected: List<ColumnShape>,
) {
    require(tableExists(db, table)) { "required v10 table is missing: $table" }
    val actual = mutableListOf<ColumnShape>()
    db.query("PRAGMA table_info('$table')").use { cursor ->
        while (cursor.moveToNext()) {
            actual += ColumnShape(
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("type")).uppercase(Locale.ROOT),
                notNull = cursor.getLong(cursor.getColumnIndexOrThrow("notnull")) == 1L,
                primaryKeyPosition = cursor.getLong(cursor.getColumnIndexOrThrow("pk")).toInt(),
            )
        }
    }
    require(actual == expected) { "schema mismatch in $table: $actual" }
}

private fun assertIndex(
    db: SupportSQLiteDatabase,
    table: String,
    name: String,
    columns: List<String>,
    unique: Boolean = false,
) {
    var foundUnique: Boolean? = null
    db.query("PRAGMA index_list('$table')").use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == name) {
                foundUnique = cursor.getLong(cursor.getColumnIndexOrThrow("unique")) == 1L
                break
            }
        }
    }
    require(foundUnique == unique) { "missing or mismatched index $name" }
    val actualColumns = mutableListOf<String>()
    db.query("PRAGMA index_info('$name')").use { cursor ->
        while (cursor.moveToNext()) {
            actualColumns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
    }
    require(actualColumns == columns) { "index $name has unexpected columns: $actualColumns" }
}

private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
    db.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }

private fun migrationTimestamp(db: SupportSQLiteDatabase): String =
    db.query("SELECT $MIGRATION_ARCHIVE_TIMESTAMP_SQL").use { cursor ->
        require(cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_STRING)
        cursor.getString(0)
    }

private fun readEvidence(db: SupportSQLiteDatabase): List<EvidenceSnapshot> {
    val rows = mutableListOf<EvidenceSnapshot>()
    db.query("SELECT * FROM $EVIDENCE_TABLE ORDER BY id COLLATE BINARY").use { cursor ->
        while (cursor.moveToNext()) {
            val raw = ReleaseEvidenceEntity(
                id = cursor.requiredString("id"),
                canonicalFingerprint = "",
                identityKey = cursor.requiredString("identityKey"),
                sourceType = cursor.requiredString("sourceType"),
                sourceUrl = cursor.requiredString("sourceUrl"),
                sourceHash = cursor.requiredString("sourceHash"),
                parserVersion = cursor.requiredString("parserVersion"),
                observedAt = cursor.requiredString("observedAt"),
                sourceReportedAt = cursor.optionalString("sourceReportedAt"),
                approximateTime = cursor.strictBoolean("approximateTime"),
                siteIdentifierPayload = cursor.optionalString("siteIdentifierPayload"),
                sourceSeason = cursor.optionalInt("sourceSeason"),
                navigationSeason = cursor.optionalInt("navigationSeason"),
                installmentPayload = cursor.requiredString("installmentPayload"),
                languageTrack = cursor.optionalString("languageTrack"),
                evidenceType = cursor.requiredString("evidenceType"),
                scheduleCondition = cursor.requiredString("scheduleCondition"),
                confidenceSource = cursor.strictDouble("confidenceSource"),
                confidenceIdentity = cursor.strictDouble("confidenceIdentity"),
                confidenceInstallment = cursor.strictDouble("confidenceInstallment"),
                confidenceLanguageTrack = cursor.strictDouble("confidenceLanguageTrack"),
                confidenceTiming = cursor.strictDouble("confidenceTiming"),
            )
            val domain = raw.toDomainOrNull(validateCanonicalFingerprint = false)
                ?: error("malformed v10 Evidence row: ${raw.id}")
            require(domain.id.isNotBlank() && domain.sourceHash.isNotBlank()) {
                "blank v10 Evidence identity field"
            }
            val fingerprint = ReleaseEvidenceFingerprintV2.compute(domain)
            val idKind = classifyHistoricalId(domain)
            rows += EvidenceSnapshot(raw, domain, fingerprint, idKind)
        }
    }
    return rows
}

private fun classifyHistoricalId(evidence: ReleaseEvidence): HistoricalIdKind {
    if (ReleaseEvidenceFingerprintV2.isV2Id(evidence.id)) {
        require(evidence.id == ReleaseEvidenceFingerprintV2.evidenceId(evidence)) {
            "invalid v2 Evidence ID suffix: ${evidence.id}"
        }
        return HistoricalIdKind.V2
    }
    val oldPrefix = "${evidence.sourceType.name.lowercase(Locale.ROOT)}:"
    if (evidence.id.startsWith(oldPrefix)) {
        require(evidence.id == legacyEvidenceId(evidence)) {
            "legacy-looking Evidence ID disagrees with its payload: ${evidence.id}"
        }
        return HistoricalIdKind.LEGACY
    }
    return HistoricalIdKind.OPAQUE
}

internal fun legacyEvidenceId(evidence: ReleaseEvidence): String? {
    if (!evidence.sourceType.isAniWorld) return null
    val site = evidence.siteIdentifier ?: return null
    val track = evidence.languageTrack ?: return null
    return listOf(
        evidence.sourceType.name.lowercase(Locale.ROOT),
        evidence.sourceHash.take(16),
        site.canonicalId,
        evidence.installment.stableKey,
        track.name.lowercase(Locale.ROOT),
    ).joinToString(":")
}

private fun selectCanonical(rows: List<EvidenceSnapshot>): EvidenceSnapshot {
    require(rows.isNotEmpty()) { "empty fingerprint group" }
    if (rows.size == 1) return rows.single()
    rows.forEach { row ->
        require(ReleaseEvidenceFingerprintV2.isSemanticallyCompatible(rows.first().domain, row.domain)) {
            "Evidence fingerprint collision or semantic mismatch"
        }
    }
    require(rows.size == 2 && rows.count { it.idKind == HistoricalIdKind.LEGACY } == 1 &&
        rows.count { it.idKind == HistoricalIdKind.V2 } == 1
    ) { "unsupported duplicate Evidence ID group" }
    return rows.single { it.idKind == HistoricalIdKind.LEGACY }
}

private fun readAndPlanDecisions(
    db: SupportSQLiteDatabase,
    canonicalByOldId: Map<String, EvidenceSnapshot>,
): List<DecisionRewrite> {
    val rewrites = mutableListOf<DecisionRewrite>()
    db.query("SELECT * FROM $DECISION_TABLE ORDER BY identityKey COLLATE BINARY").use { cursor ->
        while (cursor.moveToNext()) {
            val raw = ReleaseDecisionEntity(
                identityKey = cursor.requiredString("identityKey"),
                siteIdentifierPayload = cursor.optionalString("siteIdentifierPayload"),
                sourceSeason = cursor.optionalInt("sourceSeason"),
                navigationSeason = cursor.optionalInt("navigationSeason"),
                installmentPayload = cursor.requiredString("installmentPayload"),
                languageTrack = cursor.optionalString("languageTrack"),
                phase = cursor.requiredString("phase"),
                scheduleCondition = cursor.requiredString("scheduleCondition"),
                authority = cursor.requiredString("authority"),
                contributingEvidenceIdsPayload = cursor.requiredString("contributingEvidenceIdsPayload"),
                authoritativeEvidenceIdsPayload = cursor.requiredString("authoritativeEvidenceIdsPayload"),
                releaseAt = cursor.optionalString("releaseAt"),
                lastObservedAt = cursor.optionalString("lastObservedAt"),
                decidedAt = cursor.requiredString("decidedAt"),
                revision = cursor.strictLong("revision"),
                diagnosticsPayload = cursor.requiredString("diagnosticsPayload"),
            )
            val decision = raw.toDomainOrNull() ?: error("malformed v10 Decision: ${raw.identityKey}")
            require(packFields(decision.contributingEvidenceIds) == raw.contributingEvidenceIdsPayload)
            require(packFields(decision.authoritativeEvidenceIds) == raw.authoritativeEvidenceIdsPayload)
            require(packFields(decision.diagnostics) == raw.diagnosticsPayload)
            require(decision.authoritativeEvidenceIds.all(decision.contributingEvidenceIds::contains)) {
                "authoritative Decision Evidence is not contributing"
            }
            if (decision.phase == ReleasePhase.RELEASED) {
                require(decision.authoritativeEvidenceIds.isNotEmpty()) {
                    "RELEASED Decision has no authoritative Evidence"
                }
            }

            fun canonicalize(ids: List<String>): List<String> = ids.map { id ->
                val row = canonicalByOldId[id] ?: error("orphan Decision Evidence reference: $id")
                require(row.domain.identityKey == decision.identityKey) {
                    "Decision references Evidence for a different identity"
                }
                row.raw.id
            }.distinct()

            val contributing = canonicalize(decision.contributingEvidenceIds)
            val authoritative = canonicalize(decision.authoritativeEvidenceIds)
            require(authoritative.all(contributing::contains)) {
                "canonical authoritative Decision Evidence is not contributing"
            }
            val rewritten = raw.copy(
                contributingEvidenceIdsPayload = packFields(contributing),
                authoritativeEvidenceIdsPayload = packFields(authoritative),
            )
            require(rewritten.toDomainOrNull() != null) { "canonicalized Decision is invalid" }
            rewrites += DecisionRewrite(raw, rewritten)
        }
    }
    return rewrites
}

private fun readAndPlanRevisions(
    db: SupportSQLiteDatabase,
    canonicalByOldId: Map<String, EvidenceSnapshot>,
): List<RevisionRewrite> {
    val snapshots = mutableListOf<RevisionSnapshot>()
    db.query("SELECT * FROM $FORECAST_TABLE ORDER BY revisionId").use { cursor ->
        while (cursor.moveToNext()) {
            val raw = ReleaseForecastRevisionEntity(
                revisionId = cursor.strictLong("revisionId"),
                identityKey = cursor.requiredString("identityKey"),
                evidenceId = cursor.requiredString("evidenceId"),
                forecastAt = cursor.requiredString("forecastAt"),
                observedAt = cursor.requiredString("observedAt"),
                approximateTime = cursor.strictBoolean("approximateTime"),
                sourceHash = cursor.requiredString("sourceHash"),
                parserVersion = cursor.requiredString("parserVersion"),
            )
            require(raw.revisionId > 0L) { "invalid ForecastRevision id" }
            val revision = raw.toDomainOrNull() ?: error("malformed ForecastRevision ${raw.revisionId}")
            val evidence = canonicalByOldId[raw.evidenceId]
                ?: error("orphan ForecastRevision Evidence reference: ${raw.evidenceId}")
            val expected = ReleaseForecastRevision.fromEvidence(evidence.domain)
                ?: error("ForecastRevision does not derive from Calendar forecast Evidence")
            require(revision == expected) { "ForecastRevision disagrees with its Evidence" }
            snapshots += RevisionSnapshot(raw, evidence.raw.id)
        }
    }

    return snapshots.groupBy(RevisionSnapshot::canonicalEvidenceId)
        .toSortedMap()
        .map { (canonicalEvidenceId, group) ->
            val canonical = canonicalByOldId.getValue(canonicalEvidenceId)
            val canonicalRevision = ReleaseForecastRevision.fromEvidence(canonical.domain)
                ?: error("canonical Evidence cannot derive a ForecastRevision")
            val existingCanonical = group.filter { it.raw.evidenceId == canonicalEvidenceId }
            require(existingCanonical.size <= 1) { "duplicate canonical ForecastRevision" }
            val selected = existingCanonical.singleOrNull() ?: group.minWith(
                compareBy<RevisionSnapshot> { Instant.parse(it.raw.observedAt) }
                    .thenBy { it.raw.revisionId },
            )
            val active = canonicalRevision.toEntity().copy(revisionId = selected.raw.revisionId)
            val archived = group.asSequence()
                .filter { it.raw.revisionId != selected.raw.revisionId || it.raw != active }
                .map(RevisionSnapshot::raw)
                .toList()
            RevisionRewrite(active = active, archived = archived)
        }
}

private fun readRevisionSequence(db: SupportSQLiteDatabase): Long {
    db.query("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(FORECAST_TABLE)).use { cursor ->
        return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }
}

private fun replaceEvidenceTable(
    db: SupportSQLiteDatabase,
    canonicalRows: Collection<EvidenceSnapshot>,
    timestamp: String,
) {
    // timestamp is consumed by archive/alias rows; keeping it in this signature ensures a single
    // preflight snapshot and makes accidental non-transactional calls harder to introduce.
    require(timestamp.isNotBlank())
    db.execSQL(
        """
        CREATE TABLE v3_release_evidence_v11 (
            id TEXT NOT NULL,
            canonicalFingerprint TEXT NOT NULL,
            identityKey TEXT NOT NULL,
            sourceType TEXT NOT NULL,
            sourceUrl TEXT NOT NULL,
            sourceHash TEXT NOT NULL,
            parserVersion TEXT NOT NULL,
            observedAt TEXT NOT NULL,
            sourceReportedAt TEXT,
            approximateTime INTEGER NOT NULL,
            siteIdentifierPayload TEXT,
            sourceSeason INTEGER,
            navigationSeason INTEGER,
            installmentPayload TEXT NOT NULL,
            languageTrack TEXT,
            evidenceType TEXT NOT NULL,
            scheduleCondition TEXT NOT NULL,
            confidenceSource REAL NOT NULL,
            confidenceIdentity REAL NOT NULL,
            confidenceInstallment REAL NOT NULL,
            confidenceLanguageTrack REAL NOT NULL,
            confidenceTiming REAL NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
    )
    canonicalRows.sortedBy { it.raw.id }.forEach { row ->
        val e = row.raw
        db.execSQL(
            """
            INSERT INTO v3_release_evidence_v11 (
                id, canonicalFingerprint, identityKey, sourceType, sourceUrl, sourceHash,
                parserVersion, observedAt, sourceReportedAt, approximateTime,
                siteIdentifierPayload, sourceSeason, navigationSeason, installmentPayload,
                languageTrack, evidenceType, scheduleCondition, confidenceSource,
                confidenceIdentity, confidenceInstallment, confidenceLanguageTrack, confidenceTiming
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                e.id, row.fingerprint, e.identityKey, e.sourceType, e.sourceUrl, e.sourceHash,
                e.parserVersion, e.observedAt, e.sourceReportedAt, e.approximateTime.asSqlInt(),
                e.siteIdentifierPayload, e.sourceSeason, e.navigationSeason, e.installmentPayload,
                e.languageTrack, e.evidenceType, e.scheduleCondition, e.confidenceSource,
                e.confidenceIdentity, e.confidenceInstallment, e.confidenceLanguageTrack,
                e.confidenceTiming,
            ),
        )
    }
    db.execSQL("DROP TABLE $EVIDENCE_TABLE")
    db.execSQL("ALTER TABLE v3_release_evidence_v11 RENAME TO $EVIDENCE_TABLE")
}

private fun writeDecisionRewrites(db: SupportSQLiteDatabase, rewrites: List<DecisionRewrite>) {
    rewrites.forEach { rewrite ->
        db.execSQL(
            "UPDATE $DECISION_TABLE SET contributingEvidenceIdsPayload = ?, " +
                "authoritativeEvidenceIdsPayload = ? WHERE identityKey = ?",
            arrayOf(
                rewrite.rewritten.contributingEvidenceIdsPayload,
                rewrite.rewritten.authoritativeEvidenceIdsPayload,
                rewrite.original.identityKey,
            ),
        )
    }
}

private fun applyRevisionRewrites(db: SupportSQLiteDatabase, rewrites: List<RevisionRewrite>) {
    val archivedIds = rewrites.flatMap { it.archived }.map { it.revisionId }
    require(archivedIds.distinct().size == archivedIds.size) { "duplicate archived revision IDs" }

    // Free unique evidenceId values before re-pointing a chosen row to the canonical Evidence.
    rewrites.flatMap { it.archived }
        .filter { archived -> rewrites.none { it.active.revisionId == archived.revisionId } }
        .forEach { archived ->
            db.execSQL("DELETE FROM $FORECAST_TABLE WHERE revisionId = ?", arrayOf(archived.revisionId))
        }
    rewrites.forEach { rewrite ->
        val e = rewrite.active
        db.execSQL(
            "UPDATE $FORECAST_TABLE SET identityKey = ?, evidenceId = ?, forecastAt = ?, " +
                "observedAt = ?, approximateTime = ?, sourceHash = ?, parserVersion = ? " +
                "WHERE revisionId = ?",
            arrayOf<Any?>(
                e.identityKey, e.evidenceId, e.forecastAt, e.observedAt,
                e.approximateTime.asSqlInt(), e.sourceHash, e.parserVersion, e.revisionId,
            ),
        )
    }
}

private fun createAliasAndArchiveTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE v3_evidence_alias (
            aliasId TEXT NOT NULL,
            canonicalEvidenceId TEXT NOT NULL,
            canonicalFingerprint TEXT NOT NULL,
            aliasKind TEXT NOT NULL,
            createdAt TEXT NOT NULL,
            PRIMARY KEY(aliasId),
            FOREIGN KEY(canonicalEvidenceId) REFERENCES v3_release_evidence(id)
                ON UPDATE RESTRICT ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE v3_evidence_duplicate_archive (
            originalId TEXT NOT NULL,
            canonicalEvidenceId TEXT NOT NULL,
            canonicalFingerprint TEXT NOT NULL,
            identityKey TEXT NOT NULL,
            sourceType TEXT NOT NULL,
            sourceUrl TEXT NOT NULL,
            sourceHash TEXT NOT NULL,
            parserVersion TEXT NOT NULL,
            observedAt TEXT NOT NULL,
            sourceReportedAt TEXT,
            approximateTime INTEGER NOT NULL,
            siteIdentifierPayload TEXT,
            sourceSeason INTEGER,
            navigationSeason INTEGER,
            installmentPayload TEXT NOT NULL,
            languageTrack TEXT,
            evidenceType TEXT NOT NULL,
            scheduleCondition TEXT NOT NULL,
            confidenceSource REAL NOT NULL,
            confidenceIdentity REAL NOT NULL,
            confidenceInstallment REAL NOT NULL,
            confidenceLanguageTrack REAL NOT NULL,
            confidenceTiming REAL NOT NULL,
            archivedAt TEXT NOT NULL,
            PRIMARY KEY(originalId),
            FOREIGN KEY(canonicalEvidenceId) REFERENCES v3_release_evidence(id)
                ON UPDATE RESTRICT ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE v3_forecast_revision_archive (
            originalRevisionId INTEGER NOT NULL,
            canonicalRevisionId INTEGER NOT NULL,
            identityKey TEXT NOT NULL,
            evidenceId TEXT NOT NULL,
            forecastAt TEXT NOT NULL,
            observedAt TEXT NOT NULL,
            approximateTime INTEGER NOT NULL,
            sourceHash TEXT NOT NULL,
            parserVersion TEXT NOT NULL,
            archivedAt TEXT NOT NULL,
            PRIMARY KEY(originalRevisionId),
            FOREIGN KEY(canonicalRevisionId) REFERENCES v3_forecast_revision(revisionId)
                ON UPDATE RESTRICT ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
}

private fun writeAliasesAndEvidenceArchives(
    db: SupportSQLiteDatabase,
    displaced: List<Pair<EvidenceSnapshot, EvidenceSnapshot>>,
    timestamp: String,
) {
    displaced.sortedBy { it.first.raw.id }.forEach { (original, canonical) ->
        val kind = when (original.idKind) {
            HistoricalIdKind.LEGACY -> "MIGRATED_LEGACY"
            HistoricalIdKind.V2 -> "MIGRATED_V2"
            HistoricalIdKind.OPAQUE -> error("opaque Evidence cannot be displaced")
        }
        db.execSQL(
            "INSERT INTO v3_evidence_alias " +
                "(aliasId, canonicalEvidenceId, canonicalFingerprint, aliasKind, createdAt) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf(original.raw.id, canonical.raw.id, canonical.fingerprint, kind, timestamp),
        )
        val e = original.raw
        db.execSQL(
            """
            INSERT INTO v3_evidence_duplicate_archive (
                originalId, canonicalEvidenceId, canonicalFingerprint, identityKey, sourceType,
                sourceUrl, sourceHash, parserVersion, observedAt, sourceReportedAt,
                approximateTime, siteIdentifierPayload, sourceSeason, navigationSeason,
                installmentPayload, languageTrack, evidenceType, scheduleCondition,
                confidenceSource, confidenceIdentity, confidenceInstallment,
                confidenceLanguageTrack, confidenceTiming, archivedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                e.id, canonical.raw.id, canonical.fingerprint, e.identityKey, e.sourceType,
                e.sourceUrl, e.sourceHash, e.parserVersion, e.observedAt, e.sourceReportedAt,
                e.approximateTime.asSqlInt(), e.siteIdentifierPayload, e.sourceSeason,
                e.navigationSeason, e.installmentPayload, e.languageTrack, e.evidenceType,
                e.scheduleCondition, e.confidenceSource, e.confidenceIdentity,
                e.confidenceInstallment, e.confidenceLanguageTrack, e.confidenceTiming, timestamp,
            ),
        )
    }
}

private fun writeRevisionArchives(
    db: SupportSQLiteDatabase,
    rewrites: List<RevisionRewrite>,
    timestamp: String,
) {
    rewrites.flatMap { rewrite ->
        rewrite.archived.map { archived -> rewrite.active.revisionId to archived }
    }.sortedBy { it.second.revisionId }.forEach { (canonicalId, original) ->
        db.execSQL(
            """
            INSERT INTO v3_forecast_revision_archive (
                originalRevisionId, canonicalRevisionId, identityKey, evidenceId, forecastAt,
                observedAt, approximateTime, sourceHash, parserVersion, archivedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                original.revisionId, canonicalId, original.identityKey, original.evidenceId,
                original.forecastAt, original.observedAt, original.approximateTime.asSqlInt(),
                original.sourceHash, original.parserVersion, timestamp,
            ),
        )
    }
}

private fun createV11Indexes(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE INDEX idx_v3_evidence_identity_observed ON $EVIDENCE_TABLE(identityKey, observedAt)",
    )
    db.execSQL(
        "CREATE INDEX idx_v3_evidence_source_observed ON $EVIDENCE_TABLE(sourceType, observedAt)",
    )
    db.execSQL("CREATE INDEX idx_v3_evidence_hash ON $EVIDENCE_TABLE(sourceHash)")
    db.execSQL(
        "CREATE INDEX idx_v3_evidence_type_observed ON $EVIDENCE_TABLE(evidenceType, observedAt)",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX idx_v3_evidence_fingerprint ON $EVIDENCE_TABLE(canonicalFingerprint)",
    )
    db.execSQL(
        "CREATE INDEX idx_v3_alias_canonical_evidence ON v3_evidence_alias(canonicalEvidenceId)",
    )
    db.execSQL("CREATE INDEX idx_v3_alias_fingerprint ON v3_evidence_alias(canonicalFingerprint)")
    db.execSQL(
        "CREATE INDEX idx_v3_evidence_archive_canonical " +
            "ON v3_evidence_duplicate_archive(canonicalEvidenceId)",
    )
    db.execSQL(
        "CREATE INDEX idx_v3_forecast_archive_canonical " +
            "ON v3_forecast_revision_archive(canonicalRevisionId)",
    )
}

private fun validateMigratedRows(
    db: SupportSQLiteDatabase,
    canonicalByFingerprint: Map<String, EvidenceSnapshot>,
    canonicalByOldId: Map<String, EvidenceSnapshot>,
    displacedRows: List<Pair<EvidenceSnapshot, EvidenceSnapshot>>,
    decisionRewrites: List<DecisionRewrite>,
    revisionRewrites: List<RevisionRewrite>,
) {
    require(count(db, EVIDENCE_TABLE) == canonicalByFingerprint.size.toLong())
    require(count(db, "v3_evidence_alias") == displacedRows.size.toLong())
    require(count(db, "v3_evidence_duplicate_archive") == displacedRows.size.toLong())
    require(count(db, DECISION_TABLE) == decisionRewrites.size.toLong())
    require(count(db, FORECAST_TABLE) == revisionRewrites.size.toLong())
    require(count(db, "v3_forecast_revision_archive") ==
        revisionRewrites.sumOf { it.archived.size }.toLong())

    db.query("SELECT * FROM $EVIDENCE_TABLE ORDER BY id COLLATE BINARY").use { cursor ->
        while (cursor.moveToNext()) {
            val id = cursor.requiredString("id")
            val fingerprint = cursor.requiredString("canonicalFingerprint")
            require(ReleaseEvidenceFingerprintV2.isValidFingerprint(fingerprint))
            val planned = canonicalByOldId[id] ?: error("unexpected active Evidence ID")
            require(planned.raw.id == id && planned.fingerprint == fingerprint)
            val actual = readActiveEvidence(cursor)
            require(actual.toDomainOrNull() != null) { "invalid migrated active Evidence: $id" }
        }
    }

    db.query("SELECT aliasId, canonicalEvidenceId, canonicalFingerprint, aliasKind, createdAt FROM v3_evidence_alias").use { cursor ->
        while (cursor.moveToNext()) {
            val aliasId = cursor.requiredString("aliasId")
            val canonicalId = cursor.requiredString("canonicalEvidenceId")
            val fingerprint = cursor.requiredString("canonicalFingerprint")
            val expected = canonicalByOldId[aliasId] ?: error("unexpected alias ID")
            require(expected.raw.id == canonicalId && expected.fingerprint == fingerprint)
            require(canonicalByOldId[canonicalId]?.raw?.id == canonicalId)
            require(!physicalEvidenceExists(db, aliasId)) { "alias collides with an active Evidence ID" }
            val kind = cursor.requiredString("aliasKind")
            require(kind == "MIGRATED_LEGACY" || kind == "MIGRATED_V2")
            Instant.parse(cursor.requiredString("createdAt"))
        }
    }

    val expectedReleasedCount = decisionRewrites.count { it.rewritten.toDomainOrNull()?.phase == ReleasePhase.RELEASED }
    val actualReleasedCount = db.query(
        "SELECT COUNT(*) FROM $DECISION_TABLE WHERE phase = 'RELEASED'",
    ).use { it.moveToFirst(); it.getLong(0).toInt() }
    require(expectedReleasedCount == actualReleasedCount)
    db.query("SELECT evidenceId FROM $FORECAST_TABLE").use { cursor ->
        while (cursor.moveToNext()) {
            require(physicalEvidenceExists(db, cursor.getString(0))) {
                "dangling active ForecastRevision reference"
            }
        }
    }
    db.query("SELECT * FROM $DECISION_TABLE").use { cursor ->
        while (cursor.moveToNext()) {
            val entity = readDecisionEntity(cursor)
            val decision = entity.toDomainOrNull() ?: error("invalid migrated Decision")
            require(decision.contributingEvidenceIds.all { id -> physicalEvidenceExists(db, id) }) {
                "dangling contributing Decision reference"
            }
            require(decision.authoritativeEvidenceIds.all { id -> physicalEvidenceExists(db, id) }) {
                "dangling authoritative Decision reference"
            }
            decision.contributingEvidenceIds.forEach { id ->
                val identity = db.query(
                    "SELECT identityKey FROM $EVIDENCE_TABLE WHERE id = ?",
                    arrayOf(id),
                ).use { evidenceCursor ->
                    require(evidenceCursor.moveToFirst())
                    evidenceCursor.getString(0)
                }
                require(identity == decision.identityKey) { "Decision identity mismatch after migration" }
            }
        }
    }
    db.query("PRAGMA foreign_key_check").use { cursor ->
        require(!cursor.moveToFirst()) { "foreign key violations after v11 migration" }
    }
}

private fun readDecisionEntity(cursor: Cursor): ReleaseDecisionEntity = ReleaseDecisionEntity(
    identityKey = cursor.requiredString("identityKey"),
    siteIdentifierPayload = cursor.optionalString("siteIdentifierPayload"),
    sourceSeason = cursor.optionalInt("sourceSeason"),
    navigationSeason = cursor.optionalInt("navigationSeason"),
    installmentPayload = cursor.requiredString("installmentPayload"),
    languageTrack = cursor.optionalString("languageTrack"),
    phase = cursor.requiredString("phase"),
    scheduleCondition = cursor.requiredString("scheduleCondition"),
    authority = cursor.requiredString("authority"),
    contributingEvidenceIdsPayload = cursor.requiredString("contributingEvidenceIdsPayload"),
    authoritativeEvidenceIdsPayload = cursor.requiredString("authoritativeEvidenceIdsPayload"),
    releaseAt = cursor.optionalString("releaseAt"),
    lastObservedAt = cursor.optionalString("lastObservedAt"),
    decidedAt = cursor.requiredString("decidedAt"),
    revision = cursor.strictLong("revision"),
    diagnosticsPayload = cursor.requiredString("diagnosticsPayload"),
)

private fun readActiveEvidence(cursor: Cursor): ReleaseEvidenceEntity = ReleaseEvidenceEntity(
    id = cursor.requiredString("id"),
    canonicalFingerprint = cursor.requiredString("canonicalFingerprint"),
    identityKey = cursor.requiredString("identityKey"),
    sourceType = cursor.requiredString("sourceType"),
    sourceUrl = cursor.requiredString("sourceUrl"),
    sourceHash = cursor.requiredString("sourceHash"),
    parserVersion = cursor.requiredString("parserVersion"),
    observedAt = cursor.requiredString("observedAt"),
    sourceReportedAt = cursor.optionalString("sourceReportedAt"),
    approximateTime = cursor.strictBoolean("approximateTime"),
    siteIdentifierPayload = cursor.optionalString("siteIdentifierPayload"),
    sourceSeason = cursor.optionalInt("sourceSeason"),
    navigationSeason = cursor.optionalInt("navigationSeason"),
    installmentPayload = cursor.requiredString("installmentPayload"),
    languageTrack = cursor.optionalString("languageTrack"),
    evidenceType = cursor.requiredString("evidenceType"),
    scheduleCondition = cursor.requiredString("scheduleCondition"),
    confidenceSource = cursor.strictDouble("confidenceSource"),
    confidenceIdentity = cursor.strictDouble("confidenceIdentity"),
    confidenceInstallment = cursor.strictDouble("confidenceInstallment"),
    confidenceLanguageTrack = cursor.strictDouble("confidenceLanguageTrack"),
    confidenceTiming = cursor.strictDouble("confidenceTiming"),
)

private fun physicalEvidenceExists(db: SupportSQLiteDatabase, id: String): Boolean =
    db.query("SELECT 1 FROM $EVIDENCE_TABLE WHERE id = ? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }

private fun count(db: SupportSQLiteDatabase, table: String): Long =
    db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

private fun ensureRevisionSequence(db: SupportSQLiteDatabase, originalSequence: Long) {
    val maximumId = db.query("SELECT COALESCE(MAX(revisionId), 0) FROM $FORECAST_TABLE")
        .use { it.moveToFirst(); it.getLong(0) }
    val target = maxOf(originalSequence, maximumId)
    val current = readRevisionSequence(db)
    if (current < target) {
        db.execSQL(
            "UPDATE sqlite_sequence SET seq = ? WHERE name = ?",
            arrayOf<Any?>(target, FORECAST_TABLE),
        )
        if (readRevisionSequence(db) < target) {
            db.execSQL(
                "INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)",
                arrayOf<Any?>(FORECAST_TABLE, target),
            )
        }
    }
}

private fun Boolean.asSqlInt(): Int = if (this) 1 else 0

private fun Cursor.requiredString(column: String): String {
    val index = getColumnIndexOrThrow(column)
    require(getType(index) == Cursor.FIELD_TYPE_STRING) { "$column must be stored as TEXT" }
    return getString(index)
}

private fun Cursor.optionalString(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_STRING -> getString(index)
        else -> error("$column must be stored as TEXT or NULL")
    }
}

private fun Cursor.strictLong(column: String): Long {
    val index = getColumnIndexOrThrow(column)
    require(getType(index) == Cursor.FIELD_TYPE_INTEGER) { "$column must be stored as INTEGER" }
    return getLong(index)
}

private fun Cursor.strictDouble(column: String): Double {
    val index = getColumnIndexOrThrow(column)
    require(getType(index) == Cursor.FIELD_TYPE_FLOAT || getType(index) == Cursor.FIELD_TYPE_INTEGER) {
        "$column must be stored as a number"
    }
    return getDouble(index).also { require(it.isFinite()) { "$column must be finite" } }
}

private fun Cursor.optionalInt(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(index).toInt().also {
            require(it.toLong() == getLong(index)) { "$column is outside the Int range" }
        }
        else -> error("$column must be stored as INTEGER or NULL")
    }
}

private fun Cursor.strictBoolean(column: String): Boolean {
    val value = strictLong(column)
    require(value == 0L || value == 1L) { "$column must be stored as 0 or 1" }
    return value == 1L
}

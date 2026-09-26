package com.axiel7.anihyou.release.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseForecastRevision
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.repository.RoomReleaseDecisionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseEvidenceRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseIntelligencePersistence
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseNames = listOf(
        "release-persistence-v8-to-v11-test.db",
        "release-persistence-v10-to-v11-test.db",
        "release-persistence-invalid-id-test.db",
        "release-persistence-mismatch-test.db",
        "release-persistence-malformed-decision-test.db",
        "release-persistence-orphan-revision-test.db",
        "release-persistence-orphan-decision-test.db",
        "release-persistence-invalid-boolean-test.db",
        "release-persistence-opaque-duplicate-test.db",
    )
    private val observedAt = Instant.parse("2026-09-11T12:00:00Z")

    @get:Rule
    @JvmField
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ReleaseDatabase::class.java,
        emptyList(),
    )

    @After
    fun cleanup() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun realVersionEightPathPreservesR2DataAndReopensAtEleven() {
        val databaseName = databaseNames[0]
        val versionEight = migrationTestHelper.createDatabase(databaseName, 8)
        try {
            val values = ContentValues().apply {
                put("streamKey", "aniworld/snapshot/EPISODE/1/DE_DUB")
                put("providerId", "aniworld")
                put("stableSeriesKey", "snapshot")
                put("releaseKind", "EPISODE")
                put("sourceSeason", 1)
                put("languageTrack", "DE_DUB")
                put("confirmationsPayload", "[]")
                put("forecastsPayload", "[]")
                put("freshnessStatus", "FRESH")
                put("lastAttemptAt", observedAt.toString())
                put("lastSuccessAt", observedAt.toString())
                put("freshnessObservedAt", observedAt.toString())
                put("parserVersion", "wp03b1-test")
                put("sourceHash", "hash")
                putNull("freshnessDiagnostic")
                putNull("mappingPayload")
                put("sourcePresent", 1)
                put("sourceRoot", "https://aniworld.to")
                put("snapshotObservedAt", observedAt.toString())
            }
            assertTrue(versionEight.insert("provider_snapshot", SQLiteDatabase.CONFLICT_NONE, values) > 0L)
        } finally {
            versionEight.close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            RELEASE_MIGRATION_1_2,
            RELEASE_MIGRATION_2_3,
            RELEASE_MIGRATION_3_4,
            RELEASE_MIGRATION_4_5,
            RELEASE_MIGRATION_5_6,
            RELEASE_MIGRATION_6_7,
            RELEASE_MIGRATION_7_8,
            RELEASE_MIGRATION_8_9,
            RELEASE_MIGRATION_9_10,
            RELEASE_MIGRATION_10_11,
        )
        try {
            val objects = objectNames(migrated)
            assertTrue(objects.containsAll(v11Objects))
            assertEquals(
                "hash",
                migrated.query(
                    "SELECT sourceHash FROM provider_snapshot " +
                        "WHERE streamKey = 'aniworld/snapshot/EPISODE/1/DE_DUB'",
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null },
            )
            assertEquals("https://aniworld.to", migrated.query(
                "SELECT sourceRoot FROM provider_snapshot " +
                    "WHERE streamKey = 'aniworld/snapshot/EPISODE/1/DE_DUB'",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null })
        } finally {
            migrated.close()
        }

        val reopened = Room.databaseBuilder(
            context,
            ReleaseDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals("hash", reopened.releaseDao()
                    .getProviderSnapshot("aniworld/snapshot/EPISODE/1/DE_DUB")?.sourceHash)
                assertEquals(11, reopened.releaseDao().getSchemaMeta("release_schema")?.schemaVersion)
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun realVersionTenPathConsolidatesAliasesArchivesAndRecoversMissingDecisionAfterRestart() = runBlocking {
        val databaseName = databaseNames[1]
        val calendarLegacy = legacyEvidence(
            evidence("calendar-duplicate", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "calendar-sha-full-0001", observedAt,
                Instant.parse("2026-09-12T20:00:00Z")),
        )
        val calendarV2 = v2Evidence(calendarLegacy.copy(
            id = "pending",
            observedAt = observedAt.plusSeconds(300),
            parserVersion = "parser-v2",
            siteIdentifier = calendarLegacy.siteIdentifier!!.copy(
                lastValidatedAt = observedAt.plusSeconds(300),
            ),
        ))

        val releasedLegacy = legacyEvidence(
            evidence("released-duplicate", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, "recent-sha-full-0001", observedAt,
                observedAt.minusSeconds(30)),
        )
        val releasedV2 = v2Evidence(releasedLegacy.copy(
            id = "pending",
            observedAt = observedAt.plusSeconds(120),
            parserVersion = "parser-v2",
        ))

        val recoveryLegacy = legacyEvidence(
            evidence("recovery-duplicate", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, "recovery-sha-full-0001", observedAt,
                observedAt.minusSeconds(20)),
        )
        val recoveryV2 = v2Evidence(recoveryLegacy.copy(
            id = "pending",
            observedAt = observedAt.plusSeconds(180),
            parserVersion = "parser-v2",
        ))

        val rewriteLegacy = legacyEvidence(
            evidence("forecast-rewrite", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "rewrite-sha-full-0001", observedAt,
                Instant.parse("2026-09-18T20:00:00Z")),
        )
        val rewriteV2 = v2Evidence(rewriteLegacy.copy(
            id = "pending",
            observedAt = observedAt.plusSeconds(240),
            parserVersion = "parser-v2",
        ))

        val v2Singleton = v2Evidence(
            evidence("v2-singleton", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "singleton-sha-full", observedAt,
                Instant.parse("2026-09-15T20:00:00Z")),
        )
        val runtimeLegacy = legacyEvidence(
            evidence("runtime-legacy-singleton", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, "runtime-legacy-hash-full", observedAt,
                observedAt.minusSeconds(15)),
        )
        val runtimeV2 = v2Evidence(runtimeLegacy.copy(
            id = "pending",
            observedAt = observedAt.plusSeconds(600),
            parserVersion = "runtime-parser-v2",
        ))
        val sharedPrefix = "1234567890abcdef"
        val prefixLegacy = legacyEvidence(
            evidence("prefix-collision", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, sharedPrefix + "a".repeat(48), observedAt,
                observedAt.minusSeconds(5)),
        )
        val prefixV2 = v2Evidence(
            evidence("prefix-collision", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, sharedPrefix + "b".repeat(48), observedAt,
                observedAt.minusSeconds(5)),
        )

        val calendarDecisionBase = AniWorldReleaseAuthorityReducer().reduce(null, calendarLegacy)
        val aliasedDecision = calendarDecisionBase.copy(
            contributingEvidenceIds = listOf(calendarLegacy.id, calendarV2.id),
            authoritativeEvidenceIds = emptyList(),
        )
        val releasedDecision = AniWorldReleaseAuthorityReducer().reduce(null, releasedLegacy)
        assertEquals(ReleasePhase.RELEASED, releasedDecision.phase)

        val versionTen = migrationTestHelper.createDatabase(databaseName, 10)
        try {
            listOf(
                calendarLegacy, calendarV2, releasedLegacy, releasedV2,
                recoveryLegacy, recoveryV2, rewriteLegacy, rewriteV2,
                v2Singleton, runtimeLegacy, prefixLegacy, prefixV2,
            ).forEach { seedEvidence(versionTen, it) }
            // Insert v2 first to prove canonical evidence wins over earlier revision ID/time.
            seedForecastRevision(versionTen, calendarV2)
            seedForecastRevision(versionTen, calendarLegacy)
            // This group has only a v2 revision, so the selected revision must be rewritten
            // to the legacy canonical Evidence and its original values archived.
            seedForecastRevision(versionTen, rewriteV2)
            seedForecastRevision(versionTen, v2Singleton)
            seedDecision(versionTen, aliasedDecision)
            seedDecision(versionTen, releasedDecision)
        } finally {
            versionTen.close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            RELEASE_MIGRATION_10_11,
        )
        try {
            assertTrue(objectNames(migrated).containsAll(v11Objects))
            assertEquals(8L, scalarLong(migrated, "SELECT COUNT(*) FROM v3_release_evidence"))
            assertEquals(4L, scalarLong(migrated, "SELECT COUNT(*) FROM v3_evidence_alias"))
            assertEquals(4L, scalarLong(migrated, "SELECT COUNT(*) FROM v3_evidence_duplicate_archive"))
            assertEquals(3L, scalarLong(migrated, "SELECT COUNT(*) FROM v3_forecast_revision"))
            assertEquals(2L, scalarLong(migrated, "SELECT COUNT(*) FROM v3_forecast_revision_archive"))
            assertEquals(11L, scalarLong(migrated,
                "SELECT schemaVersion FROM schema_meta WHERE key = 'release_schema'"))
            migrated.query(
                "SELECT canonicalFingerprint FROM v3_release_evidence WHERE identityKey = ?",
                arrayOf(prefixLegacy.identityKey),
            ).use { cursor ->
                val fingerprints = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(2, fingerprints.size)
                assertEquals(2, fingerprints.distinct().size)
            }

            assertEquals(
                calendarLegacy.id,
                migrated.query(
                    "SELECT canonicalEvidenceId FROM v3_evidence_alias WHERE aliasId = ?",
                    arrayOf(calendarV2.id),
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) },
            )
            migrated.query(
                "SELECT sourceUrl, parserVersion, observedAt FROM v3_evidence_duplicate_archive " +
                    "WHERE originalId = ?",
                arrayOf(calendarV2.id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(calendarV2.sourceUrl, cursor.getString(0))
                assertEquals(calendarV2.parserVersion, cursor.getString(1))
                assertEquals(calendarV2.observedAt.toString(), cursor.getString(2))
            }
            migrated.query(
                "SELECT evidenceId, canonicalRevisionId FROM v3_forecast_revision_archive " +
                    "WHERE evidenceId = ?",
                arrayOf(calendarV2.id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(calendarV2.id, cursor.getString(0))
            }
            migrated.query(
                "SELECT evidenceId, observedAt, parserVersion FROM v3_forecast_revision " +
                    "WHERE evidenceId = ?",
                arrayOf(rewriteLegacy.id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(rewriteLegacy.observedAt.toString(), cursor.getString(1))
                assertEquals(rewriteLegacy.parserVersion, cursor.getString(2))
            }
            migrated.query(
                "SELECT evidenceId, observedAt, parserVersion FROM v3_forecast_revision_archive " +
                    "WHERE evidenceId = ?",
                arrayOf(rewriteV2.id),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(rewriteV2.observedAt.toString(), cursor.getString(1))
                assertEquals(rewriteV2.parserVersion, cursor.getString(2))
            }
            migrated.query(
                "SELECT contributingEvidenceIdsPayload FROM v3_release_decision WHERE identityKey = ?",
                arrayOf(calendarLegacy.identityKey),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(listOf(calendarLegacy.id), unpackFields(cursor.getString(0)))
            }
        } finally {
            migrated.close()
        }

        var database = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        try {
            val evidenceRepository = RoomReleaseEvidenceRepository(database)
            assertEquals(calendarLegacy, evidenceRepository.findById(calendarV2.id))
            assertFalse(evidenceRepository.append(calendarV2))
            assertFalse(evidenceRepository.append(runtimeV2))
            assertEquals(runtimeLegacy, evidenceRepository.findById(runtimeV2.id))
            assertEquals("RUNTIME_V2", scalarString(database.openHelper.writableDatabase,
                "SELECT aliasKind FROM v3_evidence_alias WHERE aliasId = ?", arrayOf(runtimeV2.id)))
            assertEquals(runtimeLegacy.observedAt.toString(), scalarString(
                database.openHelper.writableDatabase,
                "SELECT observedAt FROM v3_release_evidence WHERE id = ?",
                arrayOf(runtimeLegacy.id),
            ))
            assertEquals(runtimeLegacy.parserVersion, scalarString(
                database.openHelper.writableDatabase,
                "SELECT parserVersion FROM v3_release_evidence WHERE id = ?",
                arrayOf(runtimeLegacy.id),
            ))
            assertEquals(releasedLegacy, evidenceRepository.findById(releasedLegacy.id))
            val releasedAfterMigration = RoomReleaseDecisionRepository(database)
                .get(releasedLegacy.identityKey)
            assertEquals(ReleasePhase.RELEASED, releasedAfterMigration?.phase)
            assertEquals(listOf(releasedLegacy.id), releasedAfterMigration?.authoritativeEvidenceIds)

            val recovered = RoomReleaseIntelligencePersistence(
                database,
                AniWorldReleaseAuthorityReducer(),
            ).persist(listOf(SourceResult.Success(value = listOf(recoveryV2))))
            assertEquals(1, recovered.size)
            val recoveredDecision = RoomReleaseDecisionRepository(database)
                .get(recoveryLegacy.identityKey)
            assertNotNull(recoveredDecision)
            assertEquals(listOf(recoveryLegacy.id), recoveredDecision?.contributingEvidenceIds)
            assertEquals(1L, scalarLong(database.openHelper.writableDatabase,
                "SELECT COUNT(*) FROM v3_release_decision WHERE identityKey = '${recoveryLegacy.identityKey}'"))
            val stableRevision = recoveredDecision?.revision
            val aliasCreatedAt = scalarString(database.openHelper.writableDatabase,
                "SELECT createdAt FROM v3_evidence_alias WHERE aliasId = ?", arrayOf(recoveryV2.id))

            database.close()
            database = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName)
                .allowMainThreadQueries().build()
            val replay = RoomReleaseIntelligencePersistence(
                database,
                AniWorldReleaseAuthorityReducer(),
            ).persist(listOf(SourceResult.Success(value = listOf(recoveryV2))))
            val afterReplay = RoomReleaseDecisionRepository(database).get(recoveryLegacy.identityKey)
            assertEquals(listOf(recoveryLegacy.id), afterReplay?.contributingEvidenceIds)
            assertEquals(stableRevision, afterReplay?.revision)
            assertEquals(aliasCreatedAt, scalarString(database.openHelper.writableDatabase,
                "SELECT createdAt FROM v3_evidence_alias WHERE aliasId = ?", arrayOf(recoveryV2.id)))
            assertEquals(8L, scalarLong(database.openHelper.writableDatabase,
                "SELECT COUNT(*) FROM v3_release_evidence"))
            assertEquals(5L, scalarLong(database.openHelper.writableDatabase,
                "SELECT COUNT(*) FROM v3_evidence_alias"))
            assertEquals(1, replay.size)
            assertEquals(stableRevision, replay.single().revision)
            assertEquals(1, RoomReleaseEvidenceRepository(database)
                .observeForecastFor(calendarLegacy.identityKey).first().size)

            val sequenceEvidence = v2Evidence(
                evidence("post-migration-sequence", ReleaseSourceType.ANIWORLD_CALENDAR,
                    ReleaseEvidenceType.FORECAST, "sequence-sha-full", observedAt.plusSeconds(900),
                    Instant.parse("2026-09-20T20:00:00Z")),
            )
            assertTrue(RoomReleaseEvidenceRepository(database).append(sequenceEvidence))
            assertTrue(scalarLong(database.openHelper.writableDatabase,
                "SELECT MAX(revisionId) FROM v3_forecast_revision") > 4L)
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidV2SuffixRollsBackAndV10DatabaseCanBeReopened() {
        assertMigrationRollsBack(databaseNames[2]) { db ->
            val valid = evidence("invalid-v2", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "invalid-suffix-full-hash", observedAt,
                observedAt.plusSeconds(600))
            val corrupted = valid.copy(
                id = "aniworld-v3:ANIWORLD_CALENDAR:" + "0".repeat(64),
            )
            seedEvidence(db, corrupted)
        }
    }

    @Test
    fun nonHashedSemanticMismatchFailsClosed() {
        assertMigrationRollsBack(databaseNames[3]) { db ->
            val legacy = legacyEvidence(evidence("url-mismatch", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "same-fingerprint-hash", observedAt,
                observedAt.plusSeconds(600)))
            val v2 = v2Evidence(legacy.copy(
                id = "pending",
                sourceUrl = "https://aniworld.to/anime/stream/different-url",
            ))
            seedEvidence(db, legacy)
            seedEvidence(db, v2)
        }
    }

    @Test
    fun malformedPackedDecisionFailsClosed() {
        assertMigrationRollsBack(databaseNames[4]) { db ->
            val evidence = v2Evidence(evidence("bad-list", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, "bad-list-hash", observedAt,
                observedAt.minusSeconds(30)))
            seedEvidence(db, evidence)
            val valid = AniWorldReleaseAuthorityReducer().reduce(null, evidence).toEntity()
            seedDecisionEntity(db, valid.copy(contributingEvidenceIdsPayload = "not-packed"))
        }
    }

    @Test
    fun orphanForecastRevisionFailsClosed() {
        assertMigrationRollsBack(databaseNames[5]) { db ->
            val evidence = v2Evidence(evidence("orphan-revision", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "orphan-revision-hash", observedAt,
                observedAt.plusSeconds(600)))
            seedEvidence(db, evidence)
            val orphan = ReleaseForecastRevision.fromEvidence(evidence)!!.copy(
                id = "missing-evidence",
                evidenceId = "missing-evidence",
            )
            seedForecastRevisionEntity(db, orphan.toEntity())
        }
    }

    @Test
    fun orphanDecisionReferenceFailsClosed() {
        assertMigrationRollsBack(databaseNames[6]) { db ->
            val evidence = v2Evidence(evidence("orphan-decision", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, "orphan-decision-hash", observedAt,
                observedAt.minusSeconds(30)))
            seedEvidence(db, evidence)
            val row = AniWorldReleaseAuthorityReducer().reduce(null, evidence)
            seedDecision(db, row.copy(
                contributingEvidenceIds = listOf("missing-evidence"),
                authoritativeEvidenceIds = listOf("missing-evidence"),
            ))
        }
    }

    @Test
    fun invalidBooleanStorageFailsClosed() {
        assertMigrationRollsBack(databaseNames[7]) { db ->
            val evidence = v2Evidence(evidence("invalid-boolean", ReleaseSourceType.ANIWORLD_CALENDAR,
                ReleaseEvidenceType.FORECAST, "invalid-boolean-hash", observedAt,
                observedAt.plusSeconds(600)))
            seedEvidence(db, evidence, approximateOverride = 2)
        }
    }

    @Test
    fun ambiguousOpaqueDuplicateGroupFailsClosed() {
        assertMigrationRollsBack(databaseNames[8]) { db ->
            val base = evidence("opaque-duplicate", ReleaseSourceType.ANIWORLD_RECENT,
                ReleaseEvidenceType.CONFIRMATION, "opaque-duplicate-hash", observedAt,
                observedAt.minusSeconds(10))
            seedEvidence(db, base.copy(id = "opaque-history-a"))
            seedEvidence(db, base.copy(id = "opaque-history-b"))
        }
    }

    private fun assertMigrationRollsBack(
        databaseName: String,
        seed: (SupportSQLiteDatabase) -> Unit,
    ) {
        val versionTen = migrationTestHelper.createDatabase(databaseName, 10)
        try {
            seed(versionTen)
        } finally {
            versionTen.close()
        }

        var failure: Throwable? = null
        try {
            migrationTestHelper.runMigrationsAndValidate(
                databaseName,
                11,
                true,
                RELEASE_MIGRATION_10_11,
            ).close()
        } catch (error: Throwable) {
            failure = error
        }
        assertNotNull("corrupt v10 data must reject the migration", failure)

        val reopened = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            assertEquals(10L, reopened.rawQuery("PRAGMA user_version", null).use {
                assertTrue(it.moveToFirst())
                it.getLong(0)
            })
            assertTrue(reopened.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'v3_release_evidence'",
                null,
            ).use { it.moveToFirst() })
            assertEquals(0L, reopened.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'v3_evidence_alias'",
                null,
            ).use { it.moveToFirst(); it.getLong(0) })
        } finally {
            reopened.close()
        }
    }

    private fun seedEvidence(
        database: SupportSQLiteDatabase,
        evidence: ReleaseEvidence,
        approximateOverride: Int? = null,
    ) {
        val row = evidence.toEntity()
        val values = ContentValues().apply {
            put("id", row.id)
            put("identityKey", row.identityKey)
            put("sourceType", row.sourceType)
            put("sourceUrl", row.sourceUrl)
            put("sourceHash", row.sourceHash)
            put("parserVersion", row.parserVersion)
            put("observedAt", row.observedAt)
            putNullableString("sourceReportedAt", row.sourceReportedAt)
            put("approximateTime", approximateOverride ?: if (row.approximateTime) 1 else 0)
            putNullableString("siteIdentifierPayload", row.siteIdentifierPayload)
            putNullableInt("sourceSeason", row.sourceSeason)
            putNullableInt("navigationSeason", row.navigationSeason)
            put("installmentPayload", row.installmentPayload)
            putNullableString("languageTrack", row.languageTrack)
            put("evidenceType", row.evidenceType)
            put("scheduleCondition", row.scheduleCondition)
            put("confidenceSource", row.confidenceSource)
            put("confidenceIdentity", row.confidenceIdentity)
            put("confidenceInstallment", row.confidenceInstallment)
            put("confidenceLanguageTrack", row.confidenceLanguageTrack)
            put("confidenceTiming", row.confidenceTiming)
        }
        assertTrue(database.insert("v3_release_evidence", SQLiteDatabase.CONFLICT_NONE, values) > 0L)
    }

    private fun seedDecision(database: SupportSQLiteDatabase, decision: ReleaseDecision) =
        seedDecisionEntity(database, decision.toEntity())

    private fun seedDecisionEntity(database: SupportSQLiteDatabase, row: ReleaseDecisionEntity) {
        val values = ContentValues().apply {
            put("identityKey", row.identityKey)
            putNullableString("siteIdentifierPayload", row.siteIdentifierPayload)
            putNullableInt("sourceSeason", row.sourceSeason)
            putNullableInt("navigationSeason", row.navigationSeason)
            put("installmentPayload", row.installmentPayload)
            putNullableString("languageTrack", row.languageTrack)
            put("phase", row.phase)
            put("scheduleCondition", row.scheduleCondition)
            put("authority", row.authority)
            put("contributingEvidenceIdsPayload", row.contributingEvidenceIdsPayload)
            put("authoritativeEvidenceIdsPayload", row.authoritativeEvidenceIdsPayload)
            putNullableString("releaseAt", row.releaseAt)
            putNullableString("lastObservedAt", row.lastObservedAt)
            put("decidedAt", row.decidedAt)
            put("revision", row.revision)
            put("diagnosticsPayload", row.diagnosticsPayload)
        }
        assertTrue(database.insert(DECISION_TABLE, SQLiteDatabase.CONFLICT_NONE, values) > 0L)
    }

    private fun seedForecastRevision(database: SupportSQLiteDatabase, evidence: ReleaseEvidence) {
        val revision = ReleaseForecastRevision.fromEvidence(evidence)!!
        seedForecastRevisionEntity(database, revision.toEntity())
    }

    private fun seedForecastRevisionEntity(
        database: SupportSQLiteDatabase,
        row: ReleaseForecastRevisionEntity,
    ) {
        val values = ContentValues().apply {
            put("identityKey", row.identityKey)
            put("evidenceId", row.evidenceId)
            put("forecastAt", row.forecastAt)
            put("observedAt", row.observedAt)
            put("approximateTime", if (row.approximateTime) 1 else 0)
            put("sourceHash", row.sourceHash)
            put("parserVersion", row.parserVersion)
        }
        assertTrue(database.insert(FORECAST_TABLE, SQLiteDatabase.CONFLICT_NONE, values) > 0L)
    }

    private fun evidence(
        slug: String,
        sourceType: ReleaseSourceType,
        evidenceType: ReleaseEvidenceType,
        sourceHash: String,
        observedAt: Instant,
        sourceReportedAt: Instant,
    ): ReleaseEvidence {
        val identitySource = when (sourceType) {
            ReleaseSourceType.ANIWORLD_CALENDAR -> AniWorldIdentitySourceType.CALENDAR
            ReleaseSourceType.ANIWORLD_RECENT -> AniWorldIdentitySourceType.RECENT
            else -> error("unsupported migration fixture source")
        }
        return ReleaseEvidence(
            id = "pending",
            sourceType = sourceType,
            sourceUrl = "https://aniworld.to/anime/stream/$slug",
            sourceHash = sourceHash,
            parserVersion = "parser-v1",
            observedAt = observedAt,
            sourceReportedAt = sourceReportedAt,
            approximateTime = sourceType == ReleaseSourceType.ANIWORLD_CALENDAR,
            siteIdentifier = AniWorldSiteIdentifier(
                slug = slug,
                sourceType = identitySource,
                firstSeenAt = observedAt,
                lastValidatedAt = observedAt,
            ),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = evidenceType,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
    }

    private fun v2Evidence(evidence: ReleaseEvidence): ReleaseEvidence =
        evidence.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(evidence))

    private fun legacyEvidence(evidence: ReleaseEvidence): ReleaseEvidence =
        evidence.copy(id = legacyEvidenceId(evidence)!!)

    private fun objectNames(database: SupportSQLiteDatabase): Set<String> = buildSet {
        database.query("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun scalarLong(
        database: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): Long = database.query(sql, args).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun scalarString(
        database: SupportSQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): String? = database.query(sql, args).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun ContentValues.putNullableString(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableInt(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private companion object {
        const val DECISION_TABLE = "v3_release_decision"
        const val FORECAST_TABLE = "v3_forecast_revision"
        val v11Objects = setOf(
            "v3_release_evidence",
            "v3_release_decision",
            "v3_source_health",
            "v3_forecast_revision",
            "v3_evidence_alias",
            "v3_evidence_duplicate_archive",
            "v3_forecast_revision_archive",
            "idx_v3_evidence_identity_observed",
            "idx_v3_evidence_source_observed",
            "idx_v3_evidence_hash",
            "idx_v3_evidence_type_observed",
            "idx_v3_evidence_fingerprint",
            "idx_v3_alias_canonical_evidence",
            "idx_v3_alias_fingerprint",
            "idx_v3_evidence_archive_canonical",
            "idx_v3_forecast_archive_canonical",
            "idx_v3_forecast_evidence",
        )
    }
}

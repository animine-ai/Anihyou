package com.axiel7.anihyou.release.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseDatabaseMigrationV8ToV10Test {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "release-persistence-v8-to-v10-test.db"
    private val streamKey = "aniworld/snapshot/EPISODE/1/DE_DUB"
    private val observedAt = Instant.parse("2026-09-11T12:00:00Z").toString()

    @get:Rule
    @JvmField
    val migrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ReleaseDatabase::class.java,
        emptyList(),
    )

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionEightPreservesR2DataCreatesV3ObjectsAndReopens() {
        val versionEight = migrationTestHelper.createDatabase(databaseName, 8)
        try {
            val values = ContentValues().apply {
                put("streamKey", streamKey)
                put("providerId", "aniworld")
                put("stableSeriesKey", "snapshot")
                put("releaseKind", "EPISODE")
                put("sourceSeason", 1)
                put("languageTrack", "DE_DUB")
                put("confirmationsPayload", "[]")
                put("forecastsPayload", "[]")
                put("freshnessStatus", "FRESH")
                put("lastAttemptAt", observedAt)
                put("lastSuccessAt", observedAt)
                put("freshnessObservedAt", observedAt)
                put("parserVersion", "wp03b1-test")
                put("sourceHash", "hash")
                putNull("freshnessDiagnostic")
                putNull("mappingPayload")
                put("sourcePresent", 1)
                put("sourceRoot", "https://aniworld.to")
                put("snapshotObservedAt", observedAt)
            }
            assertEquals(
                1L,
                versionEight.insert("provider_snapshot", SQLiteDatabase.CONFLICT_NONE, values),
            )
        } finally {
            versionEight.close()
        }

        val migrated = migrationTestHelper.runMigrationsAndValidate(
            databaseName,
            10,
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
        )
        try {
            val objects = objectNames(migrated)
            assertTrue(objects.contains("v3_external_mapping"))
            assertTrue(objects.contains("v3_mapping_attempt"))
            assertTrue(objects.contains("idx_v3_mapping_series_provider"))
            assertTrue(objects.contains("idx_v3_mapping_status_validation"))
            assertTrue(objects.contains("idx_v3_attempt_subject_time"))
            assertTrue(objects.contains("idx_v3_attempt_result_time"))
            assertTrue(objects.contains("v3_release_evidence"))
            assertTrue(objects.contains("v3_release_decision"))
            assertTrue(objects.contains("v3_source_health"))
            assertTrue(objects.contains("v3_forecast_revision"))
            assertTrue(objects.contains("idx_v3_evidence_identity_observed"))
            assertTrue(objects.contains("idx_v3_evidence_source_observed"))
            assertTrue(objects.contains("idx_v3_evidence_hash"))
            assertTrue(objects.contains("idx_v3_evidence_type_observed"))
            assertTrue(objects.contains("idx_v3_decision_phase_authority"))
            assertTrue(objects.contains("idx_v3_decision_decided"))
            assertTrue(objects.contains("idx_v3_decision_track_phase"))
            assertTrue(objects.contains("idx_v3_health_status_attempt"))
            assertTrue(objects.contains("idx_v3_forecast_identity_observed"))
            assertTrue(objects.contains("idx_v3_forecast_evidence"))
            assertEquals("hash", migrated.query("SELECT sourceHash FROM provider_snapshot WHERE streamKey = '$streamKey'").use {
                if (!it.moveToFirst()) null else it.getString(0)
            })
        } finally {
            migrated.close()
        }

        val reopened = androidx.room.Room.databaseBuilder(
            context,
            ReleaseDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        try {
            runBlocking {
                assertEquals("hash", reopened.releaseDao().getProviderSnapshot(streamKey)?.sourceHash)
                assertEquals(
                    10,
                    reopened.releaseDao().getSchemaMeta("release_schema")?.schemaVersion,
                )
            }
        } finally {
            reopened.close()
        }
    }

    private fun objectNames(database: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> =
        buildSet {
            database.query(
                "SELECT name FROM sqlite_master WHERE type IN ('table', 'index')",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
}

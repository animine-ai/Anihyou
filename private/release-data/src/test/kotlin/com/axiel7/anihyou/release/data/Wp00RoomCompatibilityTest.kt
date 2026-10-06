package com.axiel7.anihyou.release.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Wp00RoomCompatibilityTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "wp00-room-compatibility.db"

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromOneToTwoPreservesDataAndReopens() {
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        createVersionOneDatabase(databaseFile)

        val database = Room.databaseBuilder(context, Wp00SmokeDatabase::class.java, databaseName)
            .addMigrations(WP00_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val migrated = database.smokeDao().get(1)
            assertNotNull(migrated)
            assertEquals("legacy-value", migrated?.value)
            assertEquals("", migrated?.proof)
            database.smokeDao().insert(Wp00SmokeEntity(2, "phase0", "ksp"))
        } finally {
            database.close()
        }

        val reopened = Room.databaseBuilder(context, Wp00SmokeDatabase::class.java, databaseName)
            .addMigrations(WP00_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("legacy-value", reopened.smokeDao().get(1)?.value)
            assertEquals("ksp", reopened.smokeDao().get(2)?.proof)
            assertEquals("phase0", Wp00DataCompatibilityProbe.crossModuleEcho("phase0"))
        } finally {
            reopened.close()
        }
    }

    private fun createVersionOneDatabase(file: File) {
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS wp00_release_smoke " +
                    "(id INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(id))",
            )
            database.execSQL(
                "INSERT INTO wp00_release_smoke(id, value) VALUES(1, 'legacy-value')",
            )
            database.version = 1
            assertTrue(database.isOpen)
        } finally {
            database.close()
        }
    }
}

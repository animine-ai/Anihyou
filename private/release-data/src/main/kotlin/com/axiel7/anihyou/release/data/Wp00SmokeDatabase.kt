package com.axiel7.anihyou.release.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.axiel7.anihyou.release.core.Wp00CoreCompatibilityProbe

@Entity(tableName = "wp00_release_smoke")
data class Wp00SmokeEntity(
    @PrimaryKey val id: Int,
    val value: String,
    @ColumnInfo(defaultValue = "''") val proof: String = "",
)

@Dao
interface Wp00SmokeDao {
    @Insert
    fun insert(entity: Wp00SmokeEntity)

    @Query("SELECT * FROM wp00_release_smoke WHERE id = :id")
    fun get(id: Int): Wp00SmokeEntity?
}

@Database(
    entities = [Wp00SmokeEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class Wp00SmokeDatabase : RoomDatabase() {
    abstract fun smokeDao(): Wp00SmokeDao
}

val WP00_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE wp00_release_smoke ADD COLUMN proof TEXT NOT NULL DEFAULT ''")
    }
}

object Wp00DataCompatibilityProbe {
    fun crossModuleEcho(value: String): String = Wp00CoreCompatibilityProbe.echo(value)
}

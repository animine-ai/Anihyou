package com.axiel7.anihyou.release.data.db

import androidx.room.Entity

@Entity(tableName = "sync_generation")
data class SyncGenerationEntity(
    @androidx.room.PrimaryKey val generationKey: String,
    val generation: Long,
    val updatedAt: String,
)

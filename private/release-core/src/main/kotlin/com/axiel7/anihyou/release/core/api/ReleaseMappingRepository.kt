package com.axiel7.anihyou.release.core.api

import kotlinx.coroutines.flow.Flow

data class ReleaseMappingStatus(
    val streamKey: String,
    val mediaId: Int?,
    val confidence: String?,
    val evidence: String?,
    val origin: String?,
)

interface ReleaseMappingRepository {
    fun observeMappings(): Flow<List<ReleaseMappingStatus>>

    suspend fun setManualMapping(
        streamKey: String,
        mediaId: Int,
        evidence: String,
    )

    suspend fun resetToAutomatic(streamKey: String): Boolean

    suspend fun clearAutomaticMappings(): Int
}

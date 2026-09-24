package com.axiel7.anihyou.release.core.api

import kotlinx.coroutines.flow.Flow

enum class ReleaseGermanTrack {
    DE_SUB,
    DE_DUB,
}

data class ReleaseProviderPreferences(
    val selectedProvider: String? = null,
    val preferredTrack: ReleaseGermanTrack = ReleaseGermanTrack.DE_SUB,
    val notificationsEnabled: Boolean = false,
)

interface ReleasePreferencesRepository {
    val releasePreferences: Flow<ReleaseProviderPreferences>

    suspend fun setProviderEnabled(enabled: Boolean)

    suspend fun setPreferredTrack(track: ReleaseGermanTrack)

    suspend fun setNotificationsEnabled(enabled: Boolean)
}

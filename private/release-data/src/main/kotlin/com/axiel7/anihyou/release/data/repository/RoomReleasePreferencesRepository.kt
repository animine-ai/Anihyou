package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.api.ReleasePreferencesRepository
import com.axiel7.anihyou.release.core.api.ReleaseProviderPreferences
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.data.preferences.ReleasePreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReleasePreferencesRepository(
    private val store: ReleasePreferencesStore,
) : ReleasePreferencesRepository {
    override val releasePreferences: Flow<ReleaseProviderPreferences> =
        store.preferences.map { preferences ->
            ReleaseProviderPreferences(
                selectedProvider = preferences.selectedProvider?.value,
                preferredTrack = preferences.preferredTrack.toReleaseGermanTrack(),
                notificationsEnabled = preferences.notificationsEnabled,
            )
        }

    override suspend fun setProviderEnabled(enabled: Boolean) {
        store.update { preferences ->
            preferences.copy(
                selectedProvider = if (enabled) ProviderId("aniworld") else null,
            )
        }
    }

    override suspend fun setPreferredTrack(track: ReleaseGermanTrack) {
        store.setPreferredTrack(track.toLanguageTrack())
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.setNotificationsEnabled(enabled)
    }
}

private fun LanguageTrack.toReleaseGermanTrack(): ReleaseGermanTrack = when (this) {
    LanguageTrack.DE_SUB -> ReleaseGermanTrack.DE_SUB
    LanguageTrack.DE_DUB -> ReleaseGermanTrack.DE_DUB
}

private fun ReleaseGermanTrack.toLanguageTrack(): LanguageTrack = when (this) {
    ReleaseGermanTrack.DE_SUB -> LanguageTrack.DE_SUB
    ReleaseGermanTrack.DE_DUB -> LanguageTrack.DE_DUB
}

package com.axiel7.anihyou.release.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.aniWorldReleasePreferencesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "aniworld_release_preferences")

data class ReleasePreferences(
    val selectedProvider: ProviderId? = null,
    val preferredTrack: LanguageTrack = LanguageTrack.DE_SUB,
    val notificationsEnabled: Boolean = false,
    val calendarFilter: String = DEFAULT_CALENDAR_FILTER,
) {
    companion object {
        const val DEFAULT_CALENDAR_FILTER = "all"
    }
}

class ReleasePreferencesStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.aniWorldReleasePreferencesDataStore)

    val preferences: Flow<ReleasePreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            ReleasePreferences(
                selectedProvider = values[SELECTED_PROVIDER]?.let { raw ->
                    runCatching { ProviderId(raw) }.getOrNull()
                },
                preferredTrack = values[PREFERRED_TRACK]?.let { raw ->
                    runCatching { LanguageTrack.valueOf(raw) }.getOrNull()
                } ?: LanguageTrack.DE_SUB,
                notificationsEnabled = values[NOTIFICATIONS_ENABLED] ?: false,
                calendarFilter = values[CALENDAR_FILTER]
                    ?.takeUnless { it.isBlank() }
                    ?: ReleasePreferences.DEFAULT_CALENDAR_FILTER,
            )
        }

    suspend fun update(transform: (ReleasePreferences) -> ReleasePreferences) {
        dataStore.edit { values ->
            val updated = transform(values.toReleasePreferences())
            if (updated.selectedProvider == null) {
                values.remove(SELECTED_PROVIDER)
            } else {
                values[SELECTED_PROVIDER] = updated.selectedProvider.value
            }
            values[PREFERRED_TRACK] = updated.preferredTrack.name
            values[NOTIFICATIONS_ENABLED] = updated.notificationsEnabled
            values[CALENDAR_FILTER] = updated.calendarFilter
                .trim()
                .takeUnless { it.isBlank() }
                ?: ReleasePreferences.DEFAULT_CALENDAR_FILTER
        }
    }

    suspend fun setSelectedProvider(providerId: ProviderId?) {
        update { it.copy(selectedProvider = providerId) }
    }

    suspend fun setPreferredTrack(track: LanguageTrack) {
        update { it.copy(preferredTrack = track) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        update { it.copy(notificationsEnabled = enabled) }
    }

    suspend fun setCalendarFilter(filter: String) {
        update { it.copy(calendarFilter = filter) }
    }

    private fun Preferences.toReleasePreferences(): ReleasePreferences =
        ReleasePreferences(
            selectedProvider = this[SELECTED_PROVIDER]?.let { raw ->
                runCatching { ProviderId(raw) }.getOrNull()
            },
            preferredTrack = this[PREFERRED_TRACK]?.let { raw ->
                runCatching { LanguageTrack.valueOf(raw) }.getOrNull()
            } ?: LanguageTrack.DE_SUB,
            notificationsEnabled = this[NOTIFICATIONS_ENABLED] ?: false,
            calendarFilter = this[CALENDAR_FILTER]
                ?.takeUnless { it.isBlank() }
                ?: ReleasePreferences.DEFAULT_CALENDAR_FILTER,
        )

    private companion object {
        val SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val PREFERRED_TRACK = stringPreferencesKey("preferred_track")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val CALENDAR_FILTER = stringPreferencesKey("calendar_filter")
    }
}

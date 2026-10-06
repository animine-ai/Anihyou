package com.axiel7.anihyou.release.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleasePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferenceFile = File(
        context.cacheDir,
        "release-preferences-test-" + System.nanoTime() + ".preferences_pb",
    )

    @After
    fun cleanup() {
        preferenceFile.delete()
    }

    @Test
    fun defaultsUpdateAndReopenRemainDeterministic() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = firstScope,
            produceFile = { preferenceFile },
        )
        val first = ReleasePreferencesStore(dataStore)
        try {
            assertEquals(ReleasePreferences(), first.preferences.first())
            first.update {
                it.copy(
                    selectedProvider = ProviderId("aniworld"),
                    preferredTrack = LanguageTrack.DE_DUB,
                    notificationsEnabled = true,
                    calendarFilter = "future",
                )
            }
            assertEquals(
                ReleasePreferences(
                    selectedProvider = ProviderId("aniworld"),
                    preferredTrack = LanguageTrack.DE_DUB,
                    notificationsEnabled = true,
                    calendarFilter = "future",
                ),
                first.preferences.first(),
            )

            val reopened = ReleasePreferencesStore(dataStore)
            assertEquals(
                ReleasePreferences(
                    selectedProvider = ProviderId("aniworld"),
                    preferredTrack = LanguageTrack.DE_DUB,
                    notificationsEnabled = true,
                    calendarFilter = "future",
                ),
                reopened.preferences.first(),
            )
        } finally {
            firstScope.cancel()
        }
    }

    @Test
    fun clearingProviderRestoresOnlyProviderDefault() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ReleasePreferencesStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { preferenceFile },
            ),
        )
        try {
            store.update {
                ReleasePreferences(
                    selectedProvider = ProviderId("aniworld"),
                    preferredTrack = LanguageTrack.DE_DUB,
                    notificationsEnabled = true,
                    calendarFilter = "all",
                )
            }
            store.setSelectedProvider(null)
            assertEquals(null, store.preferences.first().selectedProvider)
            assertEquals(LanguageTrack.DE_DUB, store.preferences.first().preferredTrack)
            assertEquals(true, store.preferences.first().notificationsEnabled)
        } finally {
            scope.cancel()
        }
    }
}

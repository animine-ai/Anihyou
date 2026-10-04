package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.preferences.ReleasePreferencesStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomReleaseNotificationDecisionTest {
    @Test fun aColdCacheIsUnknownAndAProvenNegativeIsIneligible() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java).allowMainThreadQueries().build()
        val preferences = ReleasePreferencesStore(context)
        var account = ReleaseAccountContext(7)
        val gate = RoomReleaseNotificationGate(database, preferences, ReleaseAccountContextProvider { account })
        try {
            preferences.setSelectedProvider(ProviderId("aniworld"))
            assertEquals(ReleaseDeliveryDecision.UNKNOWN, gate.evaluateReleaseDelivery(7, 42, "valid-identity", Installment.Episode(2)))
            account = ReleaseAccountContext(7, mapOf(42 to 2))
            assertEquals(ReleaseDeliveryDecision.INELIGIBLE, gate.evaluateReleaseDelivery(7, 42, "valid-identity", Installment.Episode(2)))
            account = ReleaseAccountContext(8)
            assertEquals(ReleaseDeliveryDecision.INELIGIBLE, gate.evaluateReleaseDelivery(7, 42, "valid-identity", Installment.Episode(2)))
            account = ReleaseAccountContext(null)
            assertEquals(ReleaseDeliveryDecision.UNKNOWN, gate.evaluateReleaseDelivery(7, 42, "valid-identity", Installment.Episode(2)))
            preferences.setSelectedProvider(null)
            assertEquals(ReleaseDeliveryDecision.INELIGIBLE, gate.evaluateReleaseDelivery(7, 42, "valid-identity", Installment.Episode(2)))
        } finally { preferences.setSelectedProvider(null); database.close() }
    }
}

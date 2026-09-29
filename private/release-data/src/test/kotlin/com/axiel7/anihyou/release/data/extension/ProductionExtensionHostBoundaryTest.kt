package com.axiel7.anihyou.release.data.extension

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductionExtensionHostBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun absentProductionPinsKeepExtensionHostInertAndRejectProvisioningCalls() {
        val boundary = ProductionExtensionHostBoundary.create(context, configuration = null)

        assertFalse(boundary.provisioned)
        assertNull(boundary.release)
        assertNull(boundary.navigation)

        assertTrue(runCatching { boundary.acceptRoot(byteArrayOf(), Instant.EPOCH) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { boundary.acceptIndex(byteArrayOf(), Instant.EPOCH) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { boundary.install(context.cacheDir, "de.aniworld", Instant.EPOCH) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { boundary.promoteHealthy(Instant.EPOCH) }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { boundary.rollback(Instant.EPOCH) }
            .exceptionOrNull() is IllegalArgumentException)
    }
}

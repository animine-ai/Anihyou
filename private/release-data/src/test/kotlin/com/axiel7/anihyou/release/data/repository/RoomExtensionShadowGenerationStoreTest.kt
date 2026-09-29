package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionReceipt
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.ExpectedSourceInstance
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomExtensionShadowGenerationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "extension-shadow-generation-test.db"
    private val startedAt = Instant.parse("2026-09-29T08:00:00Z")
    private val completedAt = startedAt.plusSeconds(12)

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun workIdClaimIsDurableAndCommitPersistsReceiptAndCycleExactlyOnce() = runBlocking {
        val database = openDatabase()
        try {
            val reconciliation = RoomReleaseReconciliationRepository(database)
            reconciliation.importBaseline()
            val store = RoomExtensionShadowGenerationStore(database, reconciliation, processEpoch = "process-one")
            val token = (store.claim("work-one", startedAt) as ExtensionShadowGenerationClaim.Acquired).token
            assertEquals(ExtensionShadowGenerationClaim.Busy, store.claim("work-two", startedAt))

            val cycle = cycle(token.cycleId, startedAt, completedAt)
            val health = listOf(SourceHealth(
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                status = SourceHealthStatus.HEALTHY,
                lastAttemptAt = completedAt,
                lastSuccessAt = completedAt,
                parserVersion = "aniworld-v3-extension-v1",
                sourceHash = "fixture-hash",
            ))
            assertTrue(store.commit(token, cycle, receipt(token), health))
            assertFalse(store.commit(token, cycle, receipt(token), health))
            assertEquals(ExtensionShadowGenerationClaim.AlreadyCommitted, store.claim("work-one", completedAt))

            val row = database.aniworldPollDao().generation(token.executionGenerationId)!!
            assertEquals("COMMITTED", row.state)
            assertEquals(token.cycleId, row.cycleId)
            assertTrue(row.manifestPayload.contains("extension-shadow-receipt-v1"))
            assertTrue(row.manifestPayload.contains("fixture-package-digest"))
            assertEquals(64, row.manifestDigest.length)
            assertEquals(SourceHealthStatus.HEALTHY.name,
                database.aniworldPollDao().health(ReleaseSourceType.ANIWORLD_RECENT.name)!!.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun processRestartFencesOldOwnerAndRetryKeepsLogicalCycleIdentity() = runBlocking {
        val database = openDatabase()
        try {
            val reconciliation = RoomReleaseReconciliationRepository(database)
            reconciliation.importBaseline()
            val oldStore = RoomExtensionShadowGenerationStore(database, reconciliation, processEpoch = "process-old")
            val oldToken = (oldStore.claim("work-restart", startedAt) as ExtensionShadowGenerationClaim.Acquired).token

            val newStore = RoomExtensionShadowGenerationStore(database, reconciliation, processEpoch = "process-new")
            val newToken = (newStore.claim("work-restart", completedAt) as ExtensionShadowGenerationClaim.Acquired).token
            assertEquals(oldToken.cycleId, newToken.cycleId)
            assertTrue(oldToken.executionGenerationId != newToken.executionGenerationId)
            assertEquals("ABORTED", database.aniworldPollDao().generation(oldToken.executionGenerationId)!!.state)
            assertFalse(oldStore.commit(oldToken, cycle(oldToken.cycleId, startedAt, completedAt),
                receipt(oldToken), emptyList()))
            assertEquals("RUNNING", database.aniworldPollDao().generation(newToken.executionGenerationId)!!.state)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): ReleaseDatabase = Room.databaseBuilder(
        context, ReleaseDatabase::class.java, databaseName,
    ).allowMainThreadQueries().build()

    private fun cycle(id: String, started: Instant, completed: Instant) = CompletedObservationCycle(
        id = id,
        scopeId = RoomExtensionShadowGenerationStore.SCOPE_ID,
        startedAt = started,
        completedAt = completed,
        policy = AbsencePolicySnapshot(),
        sources = listOf(CycleSourceObservation(
            instanceId = "aw:extension:recent:v1",
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            targetKey = "aniworld:list:recent",
            track = null,
            result = CycleResult.INCOMPLETE,
            health = SourceHealthStatus.UNAVAILABLE,
            observedAt = completed,
        )),
        manifest = listOf(ExpectedSourceInstance("aw:extension:recent:v1",
            ReleaseSourceType.ANIWORLD_RECENT, "aniworld:list:recent", null, false)),
    )

    private fun receipt(token: ExtensionShadowGenerationToken) = ExtensionExecutionReceipt(
        receiptId = "fixture-receipt",
        generationId = token.executionGenerationId,
        extensionId = ExtensionId.parse("de.aniworld"),
        providerId = ProviderId.parse("de.aniworld"),
        publisherId = "fixture-publisher",
        signingKeyId = "fixture-key",
        trustRootVersion = 2,
        packageDigest = "fixture-package-digest",
        manifestDigest = "fixture-manifest-digest",
        moduleDigest = "fixture-module-digest",
        releaseSequence = 7,
        abiVersion = 1,
        policyVersion = 1,
        runtimeVersion = "fixture-runtime",
        startedAt = startedAt.toString(),
        completedAt = completedAt.toString(),
    )
}

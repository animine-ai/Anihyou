package de.kiyori.ep02

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.room.Room
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.extension.FileExtensionProductPolicyRepository
import com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore
import com.axiel7.anihyou.release.data.extension.InstalledExtensionAccess
import com.axiel7.anihyou.release.data.repository.ProductionExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.data.repository.RoomReleasePresentationRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseProjectionRepository
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Second instrumentation invocation after the runner force-stops the first host process. */
class Ep07RestartInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val report = runBlocking { prove(targetContext) }
            result.putString("ep07Restart", report.toString())
            result.putString("stream", "EP07_RESTART_PASS\n")
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("stream", "EP07_RESTART_FAIL\n" + android.util.Log.getStackTraceString(error))
            finish(0, result)
        }
    }

    private suspend fun prove(context: Context): JSONObject {
        val marker = JSONObject(File(context.filesDir, "ep07-restart-marker.json").readText())
        check(marker.getInt("seedPid") != Process.myPid()) { "proof did not cross a process boundary" }
        val receipt = FileProviderNavigationStateStore(File(context.cacheDir, marker.getString("navigationDirectory")))
        val source = requireNotNull(receipt.state.value.source)
        val policy = FileExtensionProductPolicyRepository(File(context.cacheDir, marker.getString("policyDirectory"))) { true }
        check(policy.policy.value.activeReleaseSource == source)
        check(receipt.state.value.installments.isNotEmpty())
        // Reverify the signed TEST chain in the restarted process. LKG never substitutes for trust.
        val verified = Ep05TestTrustBridge.verify(File(context.cacheDir, "ep05-test-chain"))
        check(verified.packageDigest == receipt.state.value.packageDigest)
        val installed = object : InstalledExtensionAccess {
            override suspend fun loadInstalled(key: ExtensionSelectionKey) = verified.takeIf { key == source }
        }
        val sources = object : ExtensionSourceRepository {
            override val sources = MutableStateFlow(listOf(ExtensionSource(
                id = source.sourceId, url = "https://fixture.example/index.json", origin = "ep07-signed-test",
                enabled = true, status = ExtensionSourceStatus.CURRENT,
                extensions = listOf(SourceExtension(
                    extensionId = source.extensionId, displayName = verified.displayName, version = "1.0.0-test.1",
                    digest = verified.packageDigest, releaseSequence = verified.releaseSequence,
                    capabilities = verified.grantedRoles.map { it.name }, installedDigest = verified.packageDigest,
                    activationAllowed = true, providerId = source.providerId, publisherId = source.publisherId,
                )),
            )))
            override suspend fun add(url: String) = AddExtensionSourceResult.InvalidUrl
            override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun refresh(sourceId: String) = Unit
            override suspend fun refreshEnabled() = false
            override suspend fun activate(sourceId: String, extensionId: String) = Unit
        }
        val database = Room.databaseBuilder(context, ReleaseDatabase::class.java, marker.getString("databaseName")).build()
        try {
            // Read accepted data before any refresh is scheduled or network/runtime is available.
            val rows = database.reconciliationDao().projectionPage(256, 0)
            check(rows.size == marker.getInt("projectionCount") && rows.isNotEmpty())
            check(sha256(rows.toString()) == marker.getString("projectionHash"))
            val calendar = RoomReleasePresentationRepository(RoomReleaseProjectionRepository(database), database, policy, sources)
                .currentCalendar(null, java.time.LocalDate.of(2026, 9, 18)..java.time.LocalDate.of(2026, 10, 16))
            check(calendar.isNotEmpty() && calendar.any { it.sourceDate == java.time.LocalDate.of(2026, 9, 30) })
            val mapping = requireNotNull(database.releaseDao().getExternalMapping(marker.getString("mappingKey"), "anilist"))
            check(mapping.externalId == marker.getString("mappingId") && mapping.mappingSource == "MANUAL" &&
                mapping.confidence == "EXACT" && mapping.mappingStatus == "ACTIVE" && mapping.validatedAt != null)
            var delegateCalls = 0
            val delegate = object : WorkScopedShadowRefreshCoordinator {
                override suspend fun refresh() = refreshForWork("restart")
                override suspend fun refreshForWork(workId: String): ShadowRefreshOutcome {
                    delegateCalls++
                    error("fresh process restart must not invoke network or runtime")
                }
            }
            // The prior stale proof advanced its controlled clock by two hours; keep that clock domain.
            val clock = Clock.fixed(Instant.parse(marker.getString("proofNow")), ZoneOffset.UTC)
            val coordinator = ProductionExtensionReleaseRefreshCoordinator(sources, policy, installed, receipt, delegate, clock)
            Ep07WorkManagerProof.verifyRetainedPeriodic(context, coordinator, marker.getString("periodicWorkId"))
            val outcome = Ep07WorkManagerProof.due(context, coordinator, twice = true)
            check(outcome == ShadowRefreshOutcome.Skipped("extension-data-fresh") && delegateCalls == 0)
            check(database.reconciliationDao().projectionPage(256, 0) == rows)
            return JSONObject().put("status", "PASS").put("testTrustOnly", true)
                .put("seedPid", marker.getInt("seedPid")).put("restartPid", Process.myPid())
                .put("persistedRowsReadBeforeScheduling", true).put("acceptedRows", rows.size)
                .put("mappingAvailableBeforeRefresh", true).put("signedPackageReverified", true)
                .put("productCalendarDatesAvailableBeforeRefresh", true)
                .put("actualProductWorkManagerWorker", true).put("freshSkipsNetworkAndRuntime", true)
                .put("periodicWorkSurvivedProcessKill", true).put("retainedPeriodicWorkId", marker.getString("periodicWorkId"))
                .put("processKillProof", true)
        } finally { database.close() }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

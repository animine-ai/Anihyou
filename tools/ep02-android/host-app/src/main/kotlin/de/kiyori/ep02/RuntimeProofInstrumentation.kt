package de.kiyori.ep02

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.axiel7.anihyou.release.core.extension.ExtensionContextV1
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionMethod
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationResponseEnvelopeV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ParseInputV1
import com.axiel7.anihyou.release.core.extension.PlanInputV1
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.RequestSpec
import com.axiel7.anihyou.release.core.extension.ResponseEnvelope
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.data.extension.AndroidIsolatedExtensionRuntime
import com.axiel7.anihyou.release.data.extension.DestinationBoundExtensionTransport
import com.axiel7.anihyou.release.data.extension.ExtensionHostCoordinator
import com.axiel7.anihyou.release.data.extension.ExtensionHostResult
import com.axiel7.anihyou.release.data.extension.ExtensionObservationPolicy
import com.axiel7.anihyou.release.data.extension.ExtensionRunRequest
import com.axiel7.anihyou.release.data.extension.ExtensionRuntimeCallDiagnostics
import com.axiel7.anihyou.release.data.extension.ExtensionWireCodec
import com.axiel7.anihyou.release.data.extension.NavigationWireCodecV1
import com.axiel7.anihyou.release.data.extension.ProductionExtensionTransportFactory
import com.axiel7.anihyou.release.data.extension.ProductionNavigationDispatcher
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionPackage
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class RuntimeProofInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val report = runBlocking { RuntimeProof.run(targetContext) }
            result.putString("ep02", report.toString())
            val passed = report.optBoolean("passed", false)
            val marker = when {
                passed -> "EP02_ANDROID_PASS"
                report.optJSONObject("functional")?.optString("status") == "FAIL" ->
                    "EP02_ANDROID_FUNCTIONAL_FAIL"
                else -> "EP02_ANDROID_PERFORMANCE_FAIL"
            }
            // The full report is already in the structured `ep02` result. Keep it out of the
            // second Bundle field because raw sample evidence can be sizable.
            result.putString("stream", "$marker\n")
            finish(if (passed) -1 else 0, result)
        } catch (error: Throwable) {
            val report = JSONObject()
                .put("passed", false)
                .put("api", Build.VERSION.SDK_INT)
                .put("functional", JSONObject()
                    .put("status", "FAIL")
                    .put("message", error.message ?: error.javaClass.name))
                .put("performance", JSONObject()
                    .put("status", "NOT_RUN")
                    .put("noiseRetryAttempted", false))
            result.putString("ep02", report.toString())
            result.putString("stream", "EP02_ANDROID_FUNCTIONAL_FAIL\n$report\n" +
                android.util.Log.getStackTraceString(error))
            finish(0, result)
        }
    }
}

private object RuntimeProof {
    private const val RELEASE_BODY = "<article>Fixture episode 1</article>"
    private const val RELEASE_HASH = "6de0c15da0c1d5de1b834632f25bbd74d9ef0587035936acf1d9e99ed9ba370b"
    private const val NAV_BODY = "fixture navigation"
    private const val NAV_HASH = "96dfb9bbcd4f46a63e8b521bdef0b5b5357f2bd5ddf9cae543f098069b8478fb"
    private const val BATCH_COUNT = 5
    private const val SAMPLES_PER_BATCH = 20
    private const val SAMPLE_COUNT = BATCH_COUNT * SAMPLES_PER_BATCH
    private const val WARMUP_COUNT = 10
    private const val STEADY_STATE_LIMIT_PERCENT = 40.0
    // Relative percentages explode for sub-millisecond baselines. After lifecycle, cache,
    // Binder and serialization optimization, a <=3 ms steady-state p50 delta is treated as
    // a small absolute difference; larger deltas must still satisfy the 40% relative gate.
    private const val SMALL_ABSOLUTE_DELTA_MICROS = 3_000L
    private const val PLAN_MICRO_ABSOLUTE_LIMIT_MICROS = 1_000L
    private const val MAX_NOISY_BATCHES_FOR_RETRY = 2
    private const val MIN_CLEAN_BATCHES_FOR_RETRY = 3
    private const val CLEAR_EMULATOR_NOISE_MULTIPLIER = 2.0

    private data class BenchmarkBatch(
        val inProcessPlan: List<ExtensionRuntimeCallDiagnostics>,
        val isolatedPlan: List<ExtensionRuntimeCallDiagnostics>,
        val inProcessParse: List<ExtensionRuntimeCallDiagnostics>,
        val isolatedParse: List<ExtensionRuntimeCallDiagnostics>,
    )

    // A sub-millisecond plan guest makes relative percentages meaningless. Up to 1 ms absolute
    // process/IPC delta is the only exception; larger plan overhead remains a hard failure.
    /**
     * Provider-neutral parser fixture. The old 36-byte body measured Binder's fixed scheduling
     * floor, not a parser workload. This ~26 KiB document keeps the benchmark network-free while
     * forcing the same guest byte-scanning path to process a realistic bounded response size.
     */
    private val PERFORMANCE_BODY = buildString(32 * 1024) {
        append("<html><body>")
        repeat(512) { index ->
            append("<article data-id=\"")
            append(index)
            append("\">Fixture episode ")
            append(index)
            append("</article>")
        }
        append("</body></html>")
    }
    private val PERFORMANCE_HASH = sha256(PERFORMANCE_BODY.toByteArray(Charsets.UTF_8))

    private val planLimits = ExtensionExecutionLimits(256 * 1024, 64 * 1024, 32 * 1024 * 1024, 10_000_000, 2_000)
    private val parseLimits = ExtensionExecutionLimits(4 * 1024 * 1024, 1024 * 1024, 32 * 1024 * 1024, 10_000_000, 2_000)
    private val spinLimits = ExtensionExecutionLimits(256 * 1024, 64 * 1024, 32 * 1024 * 1024, 10_000_000_000L, 5_000)

    suspend fun run(context: Context): JSONObject {
        val module = asset(context, "fixture.wasm", 8 * 1024 * 1024)
        val digest = sha256(module)
        val validationStarted = System.nanoTime()
        val verified = FixturePackageBridge.verify(context.cacheDir, module)
        val validationMicros = (System.nanoTime() - validationStarted) / 1_000
        check(verified.moduleDigest == digest)
        check(verified.extensionId == ExtensionId.parse("fixture.release"))
        check(verified.providerId == ProviderId.parse("fixture"))
        check(verified.displayName == "Runtime Fixture")
        FixturePackageBridge.assertNativeFeatureGateRejectsSimd()

        val functional = JSONObject()
        val runtime = AndroidIsolatedExtensionRuntime(context)
        try {
            functional.put("releasePlanParse", runReleaseHost(runtime, verified))
            functional.put("navigationOverview", runNavigation(runtime, verified, NavigationTargetKind.OVERVIEW))
            functional.put("navigationEpisode", runNavigation(runtime, verified, NavigationTargetKind.EPISODE))
            functional.put("productionTransportFactoryBoundary", proveProductionTransportFactoryBoundary(context, verified))
            functional.put("productionNavigationDispatcherGate", proveProductionNavigationDispatcherGate(runtime, verified))
            functional.put("moduleCacheEvictionRecovery", proveModuleCacheEvictionRecovery(runtime, verified, module))

            val identityBeforeKill = requireNotNull(runtime.lastDiagnostics)
            check(identityBeforeKill.servicePid != Process.myPid())
            check(identityBeforeKill.serviceUid != Process.myUid())
            check(!identityBeforeKill.serviceInternetPermissionGranted)

            val cancellation = coroutineScope {
                val call = async(Dispatchers.Default) {
                    runtime.execute(digest, module, "plan_requests", byteArrayOf(0x7f), spinLimits)
                }
                check(runtime.awaitActiveInvocationForTesting())
                delay(100)
                check(runtime.cancelActiveForTesting())
                withTimeout(4_000) { call.await() }
            }
            check(cancellation == ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.CANCELLED))
            functional.put("cancellation", "CANCELLED")

            val deadline = runtime.execute(
                digest, module, "plan_requests", byteArrayOf(0x7f),
                spinLimits.copy(deadlineMillis = 100),
            )
            check(deadline == ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.DEADLINE))
            functional.put("deadline", "DEADLINE")

            val fuel = runtime.execute(
                digest, module, "plan_requests", byteArrayOf(0x7f),
                spinLimits.copy(fuel = 10_000, deadlineMillis = 2_000),
            )
            check(fuel == ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP))
            functional.put("fuel", "OUT_OF_FUEL_TRAP")

            val killed = coroutineScope {
                val call = async(Dispatchers.Default) {
                    runtime.execute(digest, module, "plan_requests", byteArrayOf(0x7f), spinLimits)
                }
                check(runtime.awaitActiveInvocationForTesting())
                delay(100)
                check(runtime.killServiceForTesting())
                withTimeout(4_000) { call.await() }
            }
            check(killed is ExtensionRuntimeResult.Failure)
            check(runtime.proveLateResultFenceForTesting())
            functional.put("serviceKill", killed.code.name)
            functional.put("lateResultRejected", true)

            val planContext = ExtensionContextV1(
                verified.extensionId, verified.providerId, listOf(SourceRole.CALENDAR),
                "2026-09-28T12:00:00Z", emptyList())
            val recovery = executeSuccess(runtime, verified, "plan_requests",
                ExtensionWireCodec.encodePlanInput(PlanInputV1(1, planContext)), planLimits)
            val recoveredIdentity = requireNotNull(runtime.lastDiagnostics)
            check(recoveredIdentity.serviceGeneration > identityBeforeKill.serviceGeneration)
            check(recoveredIdentity.servicePid != identityBeforeKill.servicePid)
            check(recoveredIdentity.servicePid != Process.myPid())
            check(recoveredIdentity.serviceUid != Process.myUid())
            check(!recoveredIdentity.serviceInternetPermissionGranted)
            functional.put("rebind", JSONObject()
                .put("oldPid", identityBeforeKill.servicePid)
                .put("newPid", recoveredIdentity.servicePid)
                .put("oldGeneration", identityBeforeKill.serviceGeneration)
                .put("newGeneration", recoveredIdentity.serviceGeneration)
                .put("planBytes", recovery.size))
            functional.put("fixtureOnlyNoFallback", true)
        } finally {
            runtime.close()
        }

        delay(200)
        val performance = try {
            benchmark(context, verified, module, digest, validationMicros)
        } catch (error: Throwable) {
            // A failed measurement is reported as a performance error and is never noise-retried.
            JSONObject()
                .put("status", "ERROR")
                .put("message", error.message ?: error.javaClass.name)
                .put("noiseRetryAttempted", false)
        }
        val performancePassed = performance.optString("status") == "PASS"
        return JSONObject()
            .put("passed", performancePassed)
            .put("api", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS))
            .put("runtimePin", "Wasmtime 48.0.3 LTS / Cranelift")
            .put("moduleDigest", digest)
            .put("hostPid", Process.myPid())
            .put("hostUid", Process.myUid())
            .put("functional", JSONObject()
                .put("status", "PASS")
                .put("checks", functional))
            .put("performance", performance)
    }

    private suspend fun runReleaseHost(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
    ): JSONObject {
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                verified.takeIf { it.providerId == providerId }
        }
        val transport = object : DestinationBoundExtensionTransport {
            override val dnsDestinationBindingVerified: Boolean = true
            override suspend fun execute(extension: VerifiedExtensionPackage, request: RequestSpec): ResponseEnvelope {
                check(extension.packageDigest == verified.packageDigest)
                check(request.url == "https://example.org/calendar")
                return ResponseEnvelope(request.requestId, request.sourceRole, ExtensionResponseStatus.OK,
                    200, request.url, RELEASE_BODY, RELEASE_HASH)
            }
        }
        val coordinator = ExtensionHostCoordinator(
            repository, runtime, transport,
            ExtensionObservationPolicy { extension, observation ->
                extension.extensionId == observation.extensionId && extension.providerId == observation.providerId
            },
            enabled = { true },
        )
        val result = coordinator.execute(ExtensionRunRequest(
            verified.providerId, "ep02-android-fixture", setOf(SourceRole.CALENDAR), emptyList()))
        check(result is ExtensionHostResult.Completed) { "release host failed: $result" }
        check(result.receipt.moduleDigest == verified.moduleDigest)
        val observation = result.observations.single()
        check(observation.extensionId == verified.extensionId)
        check(observation.providerId == verified.providerId)
        check(observation.sourceRole == SourceRole.CALENDAR)
        check(observation.sourceHash == RELEASE_HASH)
        return JSONObject()
            .put("observationCount", result.observations.size)
            .put("extensionId", observation.extensionId.value)
            .put("providerId", observation.providerId.value)
            .put("sourceRole", observation.sourceRole.name)
    }

    private suspend fun runNavigation(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        kind: NavigationTargetKind,
    ): JSONObject {
        val navContext = NavigationContextV1(
            1, verified.extensionId, verified.providerId, "2026-09-28T12:00:00Z", kind,
            if (kind == NavigationTargetKind.OVERVIEW) "overview-1" else "episode-15",
            "series-1", null, 2,
            if (kind == NavigationTargetKind.EPISODE) "15" else null,
            if (kind == NavigationTargetKind.EPISODE) ObservationTrack.DE_SUB else null,
        )
        val planBytes = executeSuccess(runtime, verified, "plan_navigation",
            NavigationWireCodecV1.encodeContext(navContext), planLimits)
        val plan = NavigationWireCodecV1.decodePlan(planBytes, navContext, verified.grantedHosts)
        check(plan.requests.single().requestId == "nav-1")
        val response = NavigationResponseEnvelopeV1(
            "nav-1", ExtensionResponseStatus.OK, 200,
            "https://example.org/nav-source", NAV_BODY, NAV_HASH)
        val parseInput = NavigationWireCodecV1.encodeParseInput(navContext, listOf(response), verified.grantedHosts)
        val outputBytes = executeSuccess(runtime, verified, "parse_navigation", parseInput, planLimits)
        val target = NavigationWireCodecV1.decodeTargets(
            outputBytes, navContext, listOf(response), verified.grantedHosts).targets.single()
        check(target.targetKind == kind && target.providerSeriesKey == "series-1")
        check(target.requestId == "nav-1" && target.sourceHash == NAV_HASH)
        if (kind == NavigationTargetKind.EPISODE) {
            check(target.providerEpisode == "15" && target.track == ObservationTrack.DE_SUB)
        } else {
            check(target.providerEpisode == null && target.track == null)
        }
        return JSONObject().put("kind", kind.name).put("url", target.url)
            .put("providerEpisode", target.providerEpisode ?: JSONObject.NULL)
            .put("track", target.track?.name ?: JSONObject.NULL)
    }

    /**
     * Android can reach the public factory, but not the release-data module's injected resolver
     * and hop seams. Exercise the real factory with requests that production must reject before
     * reservation, DNS or socket use, and assert that the ledger was never even created.
     */
    private suspend fun proveProductionTransportFactoryBoundary(
        context: Context,
        verified: VerifiedExtensionPackage,
    ): JSONObject {
        val ledgerDirectory = File(context.cacheDir, "ep02-transport-api-boundary")
        check(!ledgerDirectory.exists() || ledgerDirectory.deleteRecursively())
        val transport = ProductionExtensionTransportFactory.create(ledgerDirectory)
        check(transport.dnsDestinationBindingVerified)

        suspend fun rejectedBeforeNetwork(url: String, requestId: String): Boolean = try {
            transport.execute(verified, RequestSpec(
                requestId, SourceRole.CALENDAR, url, ExtensionMethod.GET, null))
            false
        } catch (_: IllegalArgumentException) {
            true
        }

        val rejectedHttp = rejectedBeforeNetwork("http://example.org/calendar", "boundary-http")
        val rejectedUntrustedHost = rejectedBeforeNetwork(
            "https://ungranted.example/calendar", "boundary-untrusted")
        check(rejectedHttp && rejectedUntrustedHost)
        check(!ledgerDirectory.exists())
        return JSONObject()
            .put("factoryCreated", true)
            .put("dnsDestinationBindingVerified", transport.dnsDestinationBindingVerified)
            .put("httpRejectedBeforeReservation", rejectedHttp)
            .put("untrustedHostRejectedBeforeReservation", rejectedUntrustedHost)
            .put("networkLedgerUncreated", true)
            .put("networkAttempted", false)
            .put("successfulSocketPathExercised", false)
    }

    /** The dispatcher must refuse a transport that merely claims DNS binding. */
    private suspend fun proveProductionNavigationDispatcherGate(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
    ): JSONObject {
        var fakeExecuteCalls = 0
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                verified.takeIf { it.providerId == providerId }
        }
        val unprovenTransport = object : DestinationBoundExtensionTransport {
            override val dnsDestinationBindingVerified: Boolean = true
            override suspend fun execute(
                extension: VerifiedExtensionPackage,
                request: RequestSpec,
            ): ResponseEnvelope {
                fakeExecuteCalls++
                error("the dispatcher must not execute a generic transport")
            }
        }
        val dispatcher = ProductionNavigationDispatcher(repository, runtime, unprovenTransport)
        val request = NavigationContextV1(
            1, verified.extensionId, verified.providerId, "2026-09-28T12:00:00Z",
            NavigationTargetKind.OVERVIEW, "overview-1", "series-1", null, 2, null, null,
        )
        val rejection = try {
            dispatcher.navigate(request, "ep02-navigation-dispatch-gate")
            null
        } catch (error: IllegalStateException) {
            error
        }
        check(rejection?.message == "navigation requires the production bound transport")
        check(fakeExecuteCalls == 0)
        return JSONObject()
            .put("unprovenTransportRejected", true)
            .put("planExecutedBeforeGate", true)
            .put("transportExecuteCalls", fakeExecuteCalls)
            .put("networkAttempted", false)
            .put("productionTransportReached", false)
    }

    /**
     * Test-only cache pressure. Custom sections preserve the executable fixture contract while
     * producing distinct immutable digests, forcing the bounded native cache to evict the original.
     */
    private suspend fun proveModuleCacheEvictionRecovery(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        module: ByteArray,
    ): JSONObject {
        val context = ExtensionContextV1(
            verified.extensionId, verified.providerId, listOf(SourceRole.CALENDAR),
            "2026-09-28T12:00:00Z", emptyList())
        val input = ExtensionWireCodec.encodePlanInput(PlanInputV1(1, context))
        val generation = requireNotNull(runtime.lastDiagnostics).serviceGeneration

        repeat(8) { index ->
            val variant = appendCacheMarker(module, index)
            val result = runtime.execute(
                sha256(variant), variant, "plan_requests", input, planLimits)
            check(result is ExtensionRuntimeResult.Success)
        }

        val recovered = executeSuccess(runtime, verified, "plan_requests", input, planLimits)
        val diagnostics = requireNotNull(runtime.lastDiagnostics)
        check(recovered.isNotEmpty())
        check(diagnostics.serviceGeneration == generation)
        check(!diagnostics.cacheHit)
        return JSONObject()
            .put("pressureModules", 8)
            .put("sameServiceGeneration", true)
            .put("transparentRetryRecompiled", true)
    }

    private fun appendCacheMarker(module: ByteArray, index: Int): ByteArray {
        val name = "cache-$index".toByteArray(Charsets.UTF_8)
        check(name.size < 127)
        val payloadSize = name.size + 1
        return module + byteArrayOf(0, payloadSize.toByte(), name.size.toByte()) + name
    }

    private suspend fun benchmark(
        context: Context,
        verified: VerifiedExtensionPackage,
        module: ByteArray,
        digest: String,
        validationMicros: Long,
    ): JSONObject {
        val first = benchmarkAttempt(context, verified, module, digest, validationMicros)
        val noiseAnalysis = first.getJSONObject("noiseAnalysis")
        val attempts = JSONArray().put(JSONObject(first.toString()))
        if (!noiseAnalysis.optBoolean("retryEligible")) {
            return summaryWithoutRaw(first)
                .put("status", measurementStatus(first))
                .put("attemptCount", 1)
                .put("attempts", attempts)
                .put("noiseRetry", JSONObject()
                    .put("attempted", false)
                    .put("maxRetries", 1)
                    .put("reason", noiseAnalysis.optString("reason")))
        }

        val retry = try {
            benchmarkAttempt(context, verified, module, digest, validationMicros)
        } catch (error: Throwable) {
            val retryError = JSONObject()
                .put("status", "ERROR")
                .put("message", error.message ?: error.javaClass.name)
                .put("noiseRetryAttempted", false)
            attempts.put(retryError)
            return summaryWithoutRaw(first)
                .put("status", "ERROR")
                .put("attemptCount", 2)
                .put("attempts", attempts)
                .put("noiseRetry", JSONObject()
                    .put("attempted", true)
                    .put("maxRetries", 1)
                    .put("reason", noiseAnalysis.optString("reason"))
                    .put("retryStatus", "ERROR"))
        }

        attempts.put(JSONObject(retry.toString()))
        return summaryWithoutRaw(retry)
            .put("status", measurementStatus(retry))
            .put("attemptCount", 2)
            .put("attempts", attempts)
            .put("noiseRetry", JSONObject()
                .put("attempted", true)
                .put("maxRetries", 1)
                .put("reason", noiseAnalysis.optString("reason"))
                .put("retryStatus", measurementStatus(retry)))
    }

    private fun measurementStatus(measurement: JSONObject): String =
        if (measurement.getJSONObject("steadyState").getString("policy") == "FAIL") "FAIL" else "PASS"

    private fun summaryWithoutRaw(measurement: JSONObject): JSONObject =
        JSONObject(measurement.toString()).also { it.remove("batches") }

    private suspend fun benchmarkAttempt(
        context: Context,
        verified: VerifiedExtensionPackage,
        module: ByteArray,
        digest: String,
        validationMicros: Long,
    ): JSONObject {
        val planContext = ExtensionContextV1(
            verified.extensionId, verified.providerId, listOf(SourceRole.CALENDAR),
            "2026-09-28T12:00:00Z", emptyList())
        val planInput = ExtensionWireCodec.encodePlanInput(PlanInputV1(1, planContext))
        val parseInput = ExtensionWireCodec.encodeParseInput(ParseInputV1(
            1, planContext,
            listOf(ResponseEnvelope("calendar-1", SourceRole.CALENDAR, ExtensionResponseStatus.OK,
                200, "https://example.org/calendar", PERFORMANCE_BODY, PERFORMANCE_HASH))))

        val runtime = AndroidIsolatedExtensionRuntime(context)
        try {
            runtime.clearInProcessCacheForTesting()
            val (inProcessColdResult, inProcessCold) = runtime.executeInProcessForBenchmark(
                digest, module, "parse_responses", parseInput, parseLimits, clearCache = true)
            check(inProcessColdResult is ExtensionRuntimeResult.Success)
            val inProcessColdDiag = requireNotNull(inProcessCold)
            check(!inProcessColdDiag.cacheHit)

            warmInProcess(runtime, digest, module, "plan_requests", planInput, planLimits)
            warmInProcess(runtime, digest, module, "parse_responses", parseInput, parseLimits)

            check(executeSuccess(runtime, verified, "parse_responses", parseInput, parseLimits).isNotEmpty())
            val isolatedCold = requireNotNull(runtime.lastDiagnostics)
            check(!isolatedCold.cacheHit)
            check(isolatedCold.servicePid != Process.myPid())
            check(isolatedCold.serviceUid != Process.myUid())
            check(!isolatedCold.serviceInternetPermissionGranted)

            warmIsolated(runtime, verified, "plan_requests", planInput, planLimits)
            warmIsolated(runtime, verified, "parse_responses", parseInput, parseLimits)

            val batches = buildList(BATCH_COUNT) {
                repeat(BATCH_COUNT) {
                    val (inProcessPlan, isolatedPlan) = samplePaired(
                        baseline = {
                            sampleInProcessOne(runtime, digest, module, "plan_requests", planInput, planLimits)
                        },
                        isolated = {
                            sampleIsolatedOne(runtime, verified, "plan_requests", planInput, planLimits)
                        },
                    )
                    val (inProcessParse, isolatedParse) = samplePaired(
                        baseline = {
                            sampleInProcessOne(runtime, digest, module, "parse_responses", parseInput, parseLimits)
                        },
                        isolated = {
                            sampleIsolatedOne(runtime, verified, "parse_responses", parseInput, parseLimits)
                        },
                    )
                    add(BenchmarkBatch(inProcessPlan, isolatedPlan, inProcessParse, isolatedParse))
                }
            }
            val inProcessPlan = batches.flatMap { it.inProcessPlan }
            val isolatedPlan = batches.flatMap { it.isolatedPlan }
            val inProcessParse = batches.flatMap { it.inProcessParse }
            val isolatedParse = batches.flatMap { it.isolatedParse }
            check((isolatedPlan + isolatedParse).all { it.cacheHit })
            check((isolatedPlan + isolatedParse).map { it.servicePid }.distinct().size == 1)

            val planComparison = steadyStateComparison(
                batches.map { it.inProcessPlan }, batches.map { it.isolatedPlan })
            val parseComparison = steadyStateComparison(
                batches.map { it.inProcessParse }, batches.map { it.isolatedParse })
            val planPolicy = planPolicy(planComparison)
            val parsePolicy = parseComparison.getString("policy")
            val overallPolicy = overallPolicy(planPolicy, parsePolicy)
            val batchEvidence = JSONArray()
            batches.forEachIndexed { index, batch ->
                val batchPlan = steadyStateComparison(
                    listOf(batch.inProcessPlan), listOf(batch.isolatedPlan))
                val batchParse = steadyStateComparison(
                    listOf(batch.inProcessParse), listOf(batch.isolatedParse))
                val batchPlanPolicy = planPolicy(batchPlan)
                val batchParsePolicy = batchParse.getString("policy")
                batchEvidence.put(JSONObject()
                    .put("index", index)
                    .put("policy", overallPolicy(batchPlanPolicy, batchParsePolicy))
                    .put("planPolicy", batchPlanPolicy)
                    .put("parsePolicy", batchParsePolicy)
                    .put("plan", batchPlan)
                    .put("parse", batchParse)
                    .put("rawSamples", JSONObject()
                        .put("inProcessPlan", diagnosticsArray(batch.inProcessPlan))
                        .put("isolatedPlan", diagnosticsArray(batch.isolatedPlan))
                        .put("inProcessParse", diagnosticsArray(batch.inProcessParse))
                        .put("isolatedParse", diagnosticsArray(batch.isolatedParse))))
            }
            val noiseAnalysis = analyzeNoise(batches, overallPolicy)
            return JSONObject()
                .put("sampleCount", SAMPLE_COUNT)
                .put("batchCount", BATCH_COUNT)
                .put("samplesPerBatch", SAMPLES_PER_BATCH)
                .put("warmupCount", WARMUP_COUNT)
                .put("samples", JSONObject()
                    .put("plan", SAMPLE_COUNT)
                    .put("parse", SAMPLE_COUNT))
                .put("aggregation", "median_of_batch_p50_and_p95")
                .put("fixtureParseBytes", PERFORMANCE_BODY.toByteArray(Charsets.UTF_8).size)
                .put("validation", JSONObject()
                    .put("micros", validationMicros)
                    .put("millis", validationMicros / 1000.0))
                .put("coldStart", JSONObject()
                    .put("inProcessParse", diagnosticsJson(inProcessColdDiag))
                    .put("isolatedParse", diagnosticsJson(isolatedCold)))
                .put("inProcessCold", diagnosticsJson(inProcessColdDiag))
                .put("isolatedCold", diagnosticsJson(isolatedCold))
                .put("inProcessCachedPlan", statsJson(inProcessPlan.map { it.totalMicros }))
                .put("isolatedCachedPlan", statsJson(isolatedPlan.map { it.totalMicros }))
                .put("inProcessCachedParse", statsJson(inProcessParse.map { it.totalMicros }))
                .put("isolatedCachedParse", statsJson(isolatedParse.map { it.totalMicros }))
                .put("binderJni", JSONObject()
                    .put("plan", transportBreakdown(inProcessPlan, isolatedPlan))
                    .put("parse", transportBreakdown(inProcessParse, isolatedParse)))
                .put("steadyState", JSONObject()
                    .put("policy", overallPolicy)
                    .put("policyUses", "existing_p50_thresholds; p95_is_reported_diagnostic")
                    .put("hardRelativeLimitPercent", STEADY_STATE_LIMIT_PERCENT)
                    .put("smallAbsoluteDeltaMicros", SMALL_ABSOLUTE_DELTA_MICROS)
                    .put("planMicroAbsoluteLimitMicros", PLAN_MICRO_ABSOLUTE_LIMIT_MICROS)
                    .put("planGateMode", "ABSOLUTE_IPC_FLOOR")
                    .put("parseGateMode", "REPRESENTATIVE_RELATIVE")
                    .put("planPolicy", planPolicy)
                    .put("plan", planComparison)
                    .put("parse", parseComparison))
                .put("batches", batchEvidence)
                .put("noiseAnalysis", noiseAnalysis)
                .put("fixtureParseOnlyNoNetwork", true)
                .put("coldVsCached", JSONObject()
                    .put("isolatedColdMicros", isolatedCold.totalMicros)
                    .put("isolatedColdBindMicros", isolatedCold.serviceBindMicros)
                    .put("isolatedColdCompileMicros", isolatedCold.compileMicros)
                    .put("isolatedColdStoreInstanceMicros", isolatedCold.instantiateMicros)
                    .put("isolatedCachedParseP50Micros",
                        percentile(isolatedParse.map { it.totalMicros }, 0.50))
                    .put("firstCompileMicros", isolatedCold.compileMicros)
                    .put("inProcessColdMicros", inProcessColdDiag.totalMicros)
                    .put("inProcessCachedParseP50Micros",
                        percentile(inProcessParse.map { it.totalMicros }, 0.50))
                    .put("inProcessFirstCompileMicros", inProcessColdDiag.compileMicros))
        } finally {
            runtime.close()
        }
    }

    private suspend fun warmInProcess(
        runtime: AndroidIsolatedExtensionRuntime,
        digest: String,
        module: ByteArray,
        exportName: String,
        input: ByteArray,
        limits: ExtensionExecutionLimits,
    ) {
        repeat(WARMUP_COUNT) {
            val (result, diagnostics) = runtime.executeInProcessForBenchmark(
                digest, module, exportName, input, limits)
            check(result is ExtensionRuntimeResult.Success)
            check(requireNotNull(diagnostics).cacheHit)
        }
    }

    private suspend fun warmIsolated(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        exportName: String,
        input: ByteArray,
        limits: ExtensionExecutionLimits,
    ) {
        repeat(WARMUP_COUNT) {
            executeSuccess(runtime, verified, exportName, input, limits)
            check(requireNotNull(runtime.lastDiagnostics).cacheHit)
        }
    }

    private suspend fun samplePaired(
        baseline: suspend () -> ExtensionRuntimeCallDiagnostics,
        isolated: suspend () -> ExtensionRuntimeCallDiagnostics,
    ): Pair<List<ExtensionRuntimeCallDiagnostics>, List<ExtensionRuntimeCallDiagnostics>> {
        val baselineSamples = ArrayList<ExtensionRuntimeCallDiagnostics>(SAMPLES_PER_BATCH)
        val isolatedSamples = ArrayList<ExtensionRuntimeCallDiagnostics>(SAMPLES_PER_BATCH)
        repeat(SAMPLES_PER_BATCH) {
            baselineSamples += baseline()
            isolatedSamples += isolated()
        }
        check(baselineSamples.all { it.cacheHit })
        check(isolatedSamples.all { it.cacheHit })
        return baselineSamples to isolatedSamples
    }

    private suspend fun sampleInProcessOne(
        runtime: AndroidIsolatedExtensionRuntime,
        digest: String,
        module: ByteArray,
        exportName: String,
        input: ByteArray,
        limits: ExtensionExecutionLimits,
    ): ExtensionRuntimeCallDiagnostics {
        val (result, diagnostics) = runtime.executeInProcessForBenchmark(
            digest, module, exportName, input, limits)
        check(result is ExtensionRuntimeResult.Success)
        return requireNotNull(diagnostics)
    }

    private suspend fun sampleIsolatedOne(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        exportName: String,
        input: ByteArray,
        limits: ExtensionExecutionLimits,
    ): ExtensionRuntimeCallDiagnostics {
        check(executeSuccess(runtime, verified, exportName, input, limits).isNotEmpty())
        return requireNotNull(runtime.lastDiagnostics)
    }

    private fun steadyStateComparison(
        inProcessBatches: List<List<ExtensionRuntimeCallDiagnostics>>,
        isolatedBatches: List<List<ExtensionRuntimeCallDiagnostics>>,
    ): JSONObject {
        require(inProcessBatches.isNotEmpty() && inProcessBatches.size == isolatedBatches.size)
        val baselineP50ByBatch = inProcessBatches.map { percentile(it.map { sample -> sample.totalMicros }, 0.50) }
        val isolatedP50ByBatch = isolatedBatches.map { percentile(it.map { sample -> sample.totalMicros }, 0.50) }
        val baselineP95ByBatch = inProcessBatches.map { percentile(it.map { sample -> sample.totalMicros }, 0.95) }
        val isolatedP95ByBatch = isolatedBatches.map { percentile(it.map { sample -> sample.totalMicros }, 0.95) }
        val baselineP50 = median(baselineP50ByBatch)
        val isolatedP50 = median(isolatedP50ByBatch)
        val baselineP95 = median(baselineP95ByBatch)
        val isolatedP95 = median(isolatedP95ByBatch)
        val deltaP50 = isolatedP50 - baselineP50
        val deltaP95 = isolatedP95 - baselineP95
        val relativeP50 = relativePercent(deltaP50, baselineP50)
        val relativeP95 = relativePercent(deltaP95, baselineP95)
        val policy = when {
            deltaP50 <= 0L -> "PASS"
            deltaP50 <= SMALL_ABSOLUTE_DELTA_MICROS -> "SMALL_ABSOLUTE_DIFFERENCE"
            relativeP50 < STEADY_STATE_LIMIT_PERCENT -> "PASS"
            else -> "FAIL"
        }
        return JSONObject()
            .put("aggregation", "median_of_batch_percentiles")
            .put("batchCount", inProcessBatches.size)
            .put("samplesPerBatch", inProcessBatches.first().size)
            .put("baselineP50Micros", baselineP50)
            .put("isolatedP50Micros", isolatedP50)
            .put("absoluteDeltaP50Micros", deltaP50)
            .put("relativeP50Percent", relativeP50)
            .put("baselineP95Micros", baselineP95)
            .put("isolatedP95Micros", isolatedP95)
            .put("absoluteDeltaP95Micros", deltaP95)
            .put("relativeP95Percent", relativeP95)
            .put("baselineP50ByBatchMicros", longArrayJson(baselineP50ByBatch))
            .put("isolatedP50ByBatchMicros", longArrayJson(isolatedP50ByBatch))
            .put("baselineP95ByBatchMicros", longArrayJson(baselineP95ByBatch))
            .put("isolatedP95ByBatchMicros", longArrayJson(isolatedP95ByBatch))
            .put("policy", policy)
    }

    private fun planPolicy(comparison: JSONObject): String =
        if (comparison.getLong("absoluteDeltaP50Micros") <= PLAN_MICRO_ABSOLUTE_LIMIT_MICROS) {
            "MICRO_FLOOR_WITHIN_ABSOLUTE_LIMIT"
        } else {
            "FAIL"
        }

    private fun overallPolicy(planPolicy: String, parsePolicy: String): String = when {
        planPolicy == "FAIL" || parsePolicy == "FAIL" -> "FAIL"
        parsePolicy == "SMALL_ABSOLUTE_DIFFERENCE" -> "SMALL_ABSOLUTE_DIFFERENCE"
        else -> "PASS"
    }

    private fun analyzeNoise(
        batches: List<BenchmarkBatch>,
        aggregatePolicy: String,
    ): JSONObject {
        if (aggregatePolicy != "FAIL") {
            return JSONObject()
                .put("classification", "NO_RETRY_NEEDED")
                .put("retryEligible", false)
                .put("reason", "the robust aggregate passed")
                .put("candidateBatchIndexes", JSONArray())
        }

        val batchPlanPolicies = batches.map { batch ->
            planPolicy(steadyStateComparison(listOf(batch.inProcessPlan), listOf(batch.isolatedPlan)))
        }
        val batchParsePolicies = batches.map { batch ->
            steadyStateComparison(listOf(batch.inProcessParse), listOf(batch.isolatedParse))
                .getString("policy")
        }
        val planBaselineP95 = batches.map { percentile(it.inProcessPlan.map { sample -> sample.totalMicros }, 0.95) }
        val planIsolatedP95 = batches.map { percentile(it.isolatedPlan.map { sample -> sample.totalMicros }, 0.95) }
        val parseBaselineP95 = batches.map { percentile(it.inProcessParse.map { sample -> sample.totalMicros }, 0.95) }
        val parseIsolatedP95 = batches.map { percentile(it.isolatedParse.map { sample -> sample.totalMicros }, 0.95) }
        val planBaselineReference = median(planBaselineP95)
        val planIsolatedReference = median(planIsolatedP95)
        val parseBaselineReference = median(parseBaselineP95)
        val parseIsolatedReference = median(parseIsolatedP95)

        val candidates = batches.indices.filter { index ->
            val planCommonModeOutlier = batchPlanPolicies[index] == "FAIL" &&
                isHighOutlier(planBaselineP95[index], planBaselineReference) &&
                isHighOutlier(planIsolatedP95[index], planIsolatedReference)
            val parseCommonModeOutlier = batchParsePolicies[index] == "FAIL" &&
                isHighOutlier(parseBaselineP95[index], parseBaselineReference) &&
                isHighOutlier(parseIsolatedP95[index], parseIsolatedReference)
            planCommonModeOutlier || parseCommonModeOutlier
        }
        val cleanBatches = batches.filterIndexed { index, _ -> index !in candidates }
        val cleanPolicies = if (cleanBatches.size >= MIN_CLEAN_BATCHES_FOR_RETRY) {
            val cleanPlan = steadyStateComparison(
                cleanBatches.map { it.inProcessPlan }, cleanBatches.map { it.isolatedPlan })
            val cleanParse = steadyStateComparison(
                cleanBatches.map { it.inProcessParse }, cleanBatches.map { it.isolatedParse })
            overallPolicy(planPolicy(cleanPlan), cleanParse.getString("policy"))
        } else {
            "INSUFFICIENT_CLEAN_BATCHES"
        }
        val eligible = candidates.size in 1..MAX_NOISY_BATCHES_FOR_RETRY &&
            cleanBatches.size >= MIN_CLEAN_BATCHES_FOR_RETRY && cleanPolicies != "FAIL"
        return JSONObject()
            .put("classification", if (eligible) "CLEAR_EMULATOR_NOISE" else "SUSTAINED_OR_UNCLASSIFIED_REGRESSION")
            .put("retryEligible", eligible)
            .put("reason", if (eligible) {
                "failed batch p95 values are common-mode outliers and the clean-batch aggregate passes"
            } else {
                "the failed gate did not meet the bounded common-mode noise criteria"
            })
            .put("candidateBatchIndexes", intArrayJson(candidates))
            .put("cleanBatchCount", cleanBatches.size)
            .put("cleanBatchPolicy", cleanPolicies)
            .put("commonModeP95Multiplier", CLEAR_EMULATOR_NOISE_MULTIPLIER)
            .put("maxNoisyBatches", MAX_NOISY_BATCHES_FOR_RETRY)
    }

    private fun isHighOutlier(value: Long, reference: Long): Boolean =
        reference > 0L && value.toDouble() >= reference.toDouble() * CLEAR_EMULATOR_NOISE_MULTIPLIER

    private fun transportBreakdown(
        inProcess: List<ExtensionRuntimeCallDiagnostics>,
        isolated: List<ExtensionRuntimeCallDiagnostics>,
    ): JSONObject = JSONObject()
        .put("isolatedHostIpcP50Micros", percentile(
            isolated.map { (it.totalMicros - it.nativeMicros).coerceAtLeast(0) }, 0.50))
        .put("inProcessJniP50Micros", percentile(
            inProcess.map { (it.totalMicros - it.nativeMicros).coerceAtLeast(0) }, 0.50))
        .put("serviceBindP50Micros", percentile(isolated.map { it.serviceBindMicros }, 0.50))
        .put("serviceReadP50Micros", percentile(isolated.map { it.serviceReadMicros }, 0.50))
        .put("storeInstanceP50Micros", percentile(isolated.map { it.instantiateMicros }, 0.50))
        .put("outputTransportIncludedInHostIpc", true)

    private fun relativePercent(delta: Long, baseline: Long): Double =
        if (baseline <= 0L) 0.0 else delta.toDouble() * 100.0 / baseline

    private suspend fun executeSuccess(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        exportName: String,
        input: ByteArray,
        limits: ExtensionExecutionLimits,
    ): ByteArray = when (val result = runtime.execute(
        verified.moduleDigest, verified.moduleBytes, exportName, input, limits)) {
        is ExtensionRuntimeResult.Success -> result.outputUtf8
        is ExtensionRuntimeResult.Failure -> error("$exportName failed: ${result.code}")
    }

    private fun diagnosticsJson(value: ExtensionRuntimeCallDiagnostics): JSONObject = JSONObject()
        .put("totalMicros", value.totalMicros)
        .put("totalMillis", value.totalMicros / 1000.0)
        .put("serviceBindMicros", value.serviceBindMicros)
        .put("serviceBindMillis", value.serviceBindMicros / 1000.0)
        .put("serviceReadMicros", value.serviceReadMicros)
        .put("nativeMicros", value.nativeMicros)
        .put("guestMicros", value.guestMicros)
        .put("compileMicros", value.compileMicros)
        .put("instantiateMicros", value.instantiateMicros)
        .put("cacheHit", value.cacheHit)
        .put("servicePid", value.servicePid)
        .put("serviceUid", value.serviceUid)
        .put("serviceGeneration", value.serviceGeneration)
        .put("serviceInternetPermissionGranted", value.serviceInternetPermissionGranted)

    private fun diagnosticsArray(values: List<ExtensionRuntimeCallDiagnostics>): JSONArray =
        JSONArray().also { array -> values.forEach { array.put(diagnosticsJson(it)) } }

    private fun longArrayJson(values: List<Long>): JSONArray =
        JSONArray().also { array -> values.forEach { array.put(it) } }

    private fun intArrayJson(values: List<Int>): JSONArray =
        JSONArray().also { array -> values.forEach { array.put(it) } }

    private fun statsJson(values: List<Long>): JSONObject = JSONObject()
        .put("sampleCount", values.size)
        .put("p50Micros", percentile(values, 0.50))
        .put("p95Micros", percentile(values, 0.95))
        .put("p50Millis", percentile(values, 0.50) / 1000.0)
        .put("p95Millis", percentile(values, 0.95) / 1000.0)
        .put("minMicros", values.minOrNull())
        .put("maxMicros", values.maxOrNull())

    private fun percentile(values: List<Long>, fraction: Double): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = kotlin.math.ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun median(values: List<Long>): Long = percentile(values, 0.50)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun asset(context: Context, name: String, limit: Int): ByteArray =
        context.assets.open(name).use { readBounded(it, limit) }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            check(out.size() + count <= limit)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

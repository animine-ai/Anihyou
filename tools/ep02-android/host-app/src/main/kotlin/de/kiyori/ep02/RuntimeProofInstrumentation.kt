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
import com.axiel7.anihyou.release.core.extension.ExtensionReportOutcome
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.InstallmentV1
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationResponseEnvelopeV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationClaimKind
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.extension.ObservationScheduleMarker
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
import com.axiel7.anihyou.release.data.extension.ExtensionHostFailureCode
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
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
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
    private val aniWorldParseLimits = ExtensionExecutionLimits(4 * 1024 * 1024, 1024 * 1024, 32 * 1024 * 1024, 25_000_000, 2_000)
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
        val httpsFixture = LocalHttpsFixtureServer(context)
        httpsFixture.start()
        val runtime = AndroidIsolatedExtensionRuntime(context)
        try {
            val releaseTransport = freshProductionTransport(context, "release")
            val httpsProofTransport = freshProductionTransport(context, "https-proof")
            val navigationTransport = freshProductionTransport(context, "navigation")
            functional.put("releasePlanParse", runReleaseHost(runtime, verified, releaseTransport, httpsFixture))
            functional.put("productionHttpsSocketProof", proveProductionHttpsSocketPath(
                verified, httpsProofTransport, httpsFixture))
            functional.put("productionNavigationDispatch", runProductionNavigationDispatch(
                runtime, verified, navigationTransport, httpsFixture))
            functional.put("ep06ProductPolicy", Ep06ProductPolicyProof.run(
                context, runtime, verified, freshProductionTransport(context, "ep-six-product"), httpsFixture))
            functional.put("navigationOverview", runNavigation(runtime, verified, NavigationTargetKind.OVERVIEW))
            functional.put("navigationEpisode", runNavigation(runtime, verified, NavigationTargetKind.EPISODE))
            functional.put("productionTransportFactoryBoundary", proveProductionTransportFactoryBoundary(context, verified))
            functional.put("productionNavigationDispatcherGate", proveProductionNavigationDispatcherGate(runtime, verified))
            functional.put("moduleCacheEvictionRecovery", proveModuleCacheEvictionRecovery(runtime, verified, module))
            functional.put("ep07UpdateRollback", Ep07UpdateRollbackProof.run(context, runtime, module))

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
            runCatching { asset(context, "aniworld.wasm", 8 * 1024 * 1024) }.getOrNull()?.let { aniWorldModule ->
                functional.put("ep04AniWorld", runAniWorldProof(context, runtime, aniWorldModule))
                val ep05Canary = Ep05CanaryProof.run(context, runtime, verified, httpsFixture)
                functional.put("ep05Canary", ep05Canary)
                functional.put("ep06SingleSourceWorker", ep05Canary.getJSONObject("ep06SingleSourceWorker"))
            }
            functional.put("fixtureOnlyNoFallback", true)
        } finally {
            runtime.close()
            httpsFixture.close()
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

    private suspend fun runAniWorldProof(
        context: Context,
        runtime: AndroidIsolatedExtensionRuntime,
        module: ByteArray,
    ): JSONObject {
        val sourceCommit = String(
            asset(context, "aniworld-source-commit.txt", 128), Charsets.UTF_8).trim()
        val verified = FixturePackageBridge.verifyAniWorld(context.cacheDir, module, sourceCommit)
        check(verified.extensionId == ExtensionId.parse("de.aniworld"))
        check(verified.providerId == ProviderId.parse("aniworld"))
        check(verified.displayName == "AniWorld")
        check(verified.grantedHosts == setOf("aniworld.to"))
        check(verified.moduleDigest == sha256(module))

        // These assets are copied from `release-extentions/tools/aniworld_vectors.py` by the
        // EP04 workflow. Run the generated wire inputs verbatim through the real isolated guest.
        val vectorDirectory = "aniworld-inputs/"
        val vectorLimit = aniWorldParseLimits.maxInputBytes
        fun vector(name: String): ByteArray = asset(
            context, "$vectorDirectory$name-input.json", vectorLimit)

        val vectorCaseNames = JSONArray(String(
            asset(context, "$vectorDirectory" + "cases.json", 256 * 1024), Charsets.UTF_8))
            .let { cases -> (0 until cases.length()).map { cases.getJSONObject(it).getString("name") }.toSet() }
        val requiredVectors = setOf(
            "release-plan", "calendar-release-parse", "recent-release-parse",
            "postponement-release-parse", "direct-release-parse", "bot-release-parse",
            "truncated-release-parse", "malformed-date-release-parse",
            "unknown-track-release-parse", "missing-direct-release-parse",
            "overview-plan", "overview-parse", "episode-plan", "episode-parse",
            "canonical-mismatch-episode-parse", "missing-episode-parse",
            "unavailable-dub-episode-parse",
        )
        check(vectorCaseNames.containsAll(requiredVectors)) {
            "generated AniWorld vector catalog is missing ${requiredVectors - vectorCaseNames}"
        }

        val releasePlanInputBytes = vector("release-plan")
        val releasePlanInput = ExtensionWireCodec.decodePlanInput(releasePlanInputBytes)
        check(releasePlanInput.context.extensionId == verified.extensionId)
        check(releasePlanInput.context.providerId == verified.providerId)
        check(releasePlanInput.context.sourceRoles.toSet() == SourceRole.entries.toSet())
        check(releasePlanInput.context.targets.single().targetToken == "t1")

        // First use this module digest only after a deliberately mismatched, syntactically valid
        // digest has been rejected. That exercises the runtime's module/digest binding before
        // the isolated process can cache this module under its real digest.
        val wrongModuleDigest = (if (verified.moduleDigest.first() == '0') "1" else "0") +
            verified.moduleDigest.drop(1)
        val moduleDigestMismatch = runtime.execute(
            wrongModuleDigest, module, "plan_requests", releasePlanInputBytes, planLimits)
        check(moduleDigestMismatch is ExtensionRuntimeResult.Failure &&
            moduleDigestMismatch.code == ExtensionRuntimeErrorCode.INVALID_INPUT)

        // Cancel an actual AniWorld operation after the isolated service has accepted it.
        // A cold module compile keeps this synchronization independent of short parse timing.
        val realGuestCancellation = coroutineScope {
            val call = async(Dispatchers.Default) {
                runtime.execute(verified.moduleDigest, verified.moduleBytes, "parse_responses",
                    vector("recent-145-rows-large-dom-release-parse"), aniWorldParseLimits)
            }
            check(runtime.awaitActiveInvocationForTesting())
            call.cancel()
            val failure = runCatching { withTimeout(4_000) { call.await() } }.exceptionOrNull()
            check(failure is CancellationException)
            true
        }
        check(runtime.proveLateResultFenceForTesting())

        val releasePlanBytes = executeSuccess(
            runtime, verified, "plan_requests", releasePlanInputBytes, planLimits)
        val releasePlan = ExtensionWireCodec.decodePlanOutput(
            releasePlanBytes, releasePlanInput.context)
        val expectedReleaseUrls = mapOf(
            SourceRole.CALENDAR to "https://aniworld.to/animekalender",
            SourceRole.RECENT to "https://aniworld.to/neue-episoden",
            SourceRole.POSTPONEMENT to "https://aniworld.to/support/frage/anime-verschiebungen",
            SourceRole.DIRECT to "https://aniworld.to/anime/stream/fixture-series/staffel-1/episode-1",
        )
        check(releasePlan.requests.size == expectedReleaseUrls.size)
        check(releasePlan.requests.map { it.sourceRole }.toSet() == expectedReleaseUrls.keys)
        releasePlan.requests.forEach { request ->
            check(request.url == expectedReleaseUrls.getValue(request.sourceRole))
            check(request.requestId == when (request.sourceRole) {
                SourceRole.CALENDAR -> "calendar"
                SourceRole.RECENT -> "recent"
                SourceRole.POSTPONEMENT -> "postponement"
                SourceRole.DIRECT -> "direct-0"
            })
            if (request.sourceRole == SourceRole.DIRECT) {
                check(request.targetToken == "t1")
            } else {
                check(request.targetToken == null)
            }
        }

        suspend fun parseVector(name: String) = run {
            val inputBytes = vector(name)
            val input = ExtensionWireCodec.decodeParseInput(inputBytes)
            check(input.context.extensionId == verified.extensionId)
            check(input.context.providerId == verified.providerId)
            val outputBytes = executeSuccess(
                runtime, verified, "parse_responses", inputBytes, aniWorldParseLimits)
            ExtensionWireCodec.decodeParseOutput(outputBytes, input)
        }

        val calendar = parseVector("calendar-release-parse")
        check(calendar.observations.size == 2)
        check(calendar.observations.all {
            it.sourceRole == SourceRole.CALENDAR && it.claimKind == ObservationClaimKind.FORECAST &&
                it.approximate && it.parsedTimestamp == null && it.providerSeriesKey == "fixture-series"
        })
        check(calendar.observations.map { it.track }.toSet() == setOf(ObservationTrack.DE_SUB, ObservationTrack.DE_DUB))
        check(calendar.responseReports.single().outcome == ExtensionReportOutcome.SUCCESS)

        val recent = parseVector("recent-release-parse")
        check(recent.observations.size == 2)
        check(recent.observations.all {
            it.sourceRole == SourceRole.RECENT && it.claimKind == ObservationClaimKind.RELEASE_LISTING &&
                !it.approximate && it.providerSeriesKey == "fixture-series"
        })
        check(recent.observations.map { it.track }.toSet() == setOf(ObservationTrack.DE_SUB, ObservationTrack.DE_DUB))
        check(recent.responseReports.single().outcome == ExtensionReportOutcome.SUCCESS)

        val postponement = parseVector("postponement-release-parse")
        check(postponement.observations.size == 2)
        check(postponement.observations.all {
            it.sourceRole == SourceRole.POSTPONEMENT && it.claimKind == ObservationClaimKind.CORRECTION &&
                it.scheduleMarker == ObservationScheduleMarker.POSTPONED && it.providerSeriesKey == null
        })
        check(postponement.observations.map { it.track }.toSet() == setOf(ObservationTrack.DE_SUB, ObservationTrack.DE_DUB))
        check(postponement.responseReports.single().outcome == ExtensionReportOutcome.SUCCESS)

        val direct = parseVector("direct-release-parse")
        check(direct.observations.size == 1)
        check(direct.observations.single().let {
            it.sourceRole == SourceRole.DIRECT && it.claimKind == ObservationClaimKind.DIRECT_AVAILABILITY &&
                it.providerSeriesKey == "fixture-series" && it.sourceSeason == 1 &&
                it.installment == InstallmentV1(ObservationInstallmentKind.EPISODE, "1") &&
                it.track == ObservationTrack.DE_SUB
        })
        check(direct.responseReports.single().outcome == ExtensionReportOutcome.SUCCESS)

        val failClosedCases = JSONObject()
        suspend fun assertFailClosed(
            name: String,
            expectedOutcome: ExtensionReportOutcome,
        ) {
            val output = parseVector(name)
            check(output.observations.isEmpty()) { "$name emitted ${output.observations.size} observations" }
            check(output.responseReports.single().outcome == expectedOutcome) { "$name outcome was ${output.responseReports}" }
            failClosedCases.put(name, expectedOutcome.name)
        }
        assertFailClosed("bot-release-parse", ExtensionReportOutcome.FAILURE)
        assertFailClosed("truncated-release-parse", ExtensionReportOutcome.FAILURE)
        assertFailClosed("malformed-date-release-parse", ExtensionReportOutcome.PARTIAL)
        assertFailClosed("unknown-track-release-parse", ExtensionReportOutcome.PARTIAL)
        assertFailClosed("missing-direct-release-parse", ExtensionReportOutcome.FAILURE)

        suspend fun runNavigationPlan(name: String): Pair<NavigationContextV1, List<String>> {
            val inputBytes = vector(name)
            val navContext = navigationContextFromVector(inputBytes)
            check(navContext.extensionId == verified.extensionId && navContext.providerId == verified.providerId)
            val outputBytes = executeSuccess(
                runtime, verified, "plan_navigation", inputBytes, planLimits)
            val plan = NavigationWireCodecV1.decodePlan(
                outputBytes, navContext, verified.grantedHosts)
            return navContext to plan.requests.map { it.url }
        }

        suspend fun runNavigationParse(name: String) = run {
            val inputBytes = vector(name)
            val navContext = navigationContextFromVector(inputBytes)
            val responses = navigationResponsesFromVector(inputBytes)
            val outputBytes = executeSuccess(
                runtime, verified, "parse_navigation", inputBytes, parseLimits.copy(maxOutputBytes = 64 * 1024))
            NavigationWireCodecV1.decodeTargets(
                outputBytes, navContext, responses, verified.grantedHosts).targets
        }

        val (overviewContext, overviewUrls) = runNavigationPlan("overview-plan")
        check(overviewContext.targetKind == NavigationTargetKind.OVERVIEW)
        check(overviewUrls == listOf("https://aniworld.to/anime/stream/fixture-series"))
        val overviewTargets = runNavigationParse("overview-parse")
        check(overviewTargets.single().let {
            it.targetKind == NavigationTargetKind.OVERVIEW &&
                it.url == "https://aniworld.to/anime/stream/fixture-series" && it.providerEpisode == null
        })

        val (episodeContext, episodeUrls) = runNavigationPlan("episode-plan")
        check(episodeContext.targetKind == NavigationTargetKind.EPISODE)
        check(episodeUrls == listOf(expectedReleaseUrls.getValue(SourceRole.DIRECT)))
        val episodeTargets = runNavigationParse("episode-parse")
        check(episodeTargets.single().let {
            it.targetKind == NavigationTargetKind.EPISODE && it.url == expectedReleaseUrls.getValue(SourceRole.DIRECT) &&
                it.providerEpisode == "1" && it.track == ObservationTrack.DE_SUB
        })

        suspend fun assertNavigationFailClosed(name: String) {
            val targets = runNavigationParse(name)
            check(targets.isEmpty()) { "$name returned navigation targets: $targets" }
            failClosedCases.put(name, "NO_TARGET")
        }
        assertNavigationFailClosed("canonical-mismatch-episode-parse")
        assertNavigationFailClosed("missing-episode-parse")
        assertNavigationFailClosed("unavailable-dub-episode-parse")

        // The coordinator sees the real verified package and real isolated guest. Only its HTTP
        // boundary is replaced with generated response vectors, so this proof performs no network.
        val roleResponses = listOf(
            "calendar-release-parse", "recent-release-parse",
            "postponement-release-parse", "direct-release-parse",
        ).map { name ->
            ExtensionWireCodec.decodeParseInput(vector(name)).responses.single()
        }.associateBy { it.sourceRole }
        val vectorTransport = object : DestinationBoundExtensionTransport {
            override val dnsDestinationBindingVerified: Boolean = true
            override suspend fun execute(
                extension: VerifiedExtensionPackage,
                request: RequestSpec,
            ): ResponseEnvelope {
                check(extension.packageDigest == verified.packageDigest)
                val response = roleResponses.getValue(request.sourceRole)
                check(response.requestId == request.requestId)
                check(response.finalUrl == request.url)
                if (request.sourceRole == SourceRole.DIRECT) check(request.targetToken == "t1")
                return response
            }
        }
        fun coordinator(transport: DestinationBoundExtensionTransport) = ExtensionHostCoordinator(
            repository = object : VerifiedExtensionRepository {
                override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                    verified.takeIf { it.providerId == providerId }
            },
            runtime = runtime,
            transport = transport,
            observationPolicy = ExtensionObservationPolicy { extension, observation ->
                extension.extensionId == observation.extensionId && extension.providerId == observation.providerId
            },
            enabled = { true },
            parseFuelByExtensionId = mapOf(verified.extensionId to 25_000_000L),
        )

        val requestGeneration = "ep04-aniworld-vector-generation"
        val serviceGenerationBeforeCoordinator = runtime.lastDiagnostics?.serviceGeneration
        val coordinated = coordinator(vectorTransport).execute(ExtensionRunRequest(
            verified.providerId, requestGeneration, SourceRole.entries.toSet(), releasePlanInput.context.targets))
        check(coordinated is ExtensionHostResult.Completed) { "AniWorld coordinator failed: $coordinated" }
        check(coordinated.receipt.generationId == requestGeneration)
        check(coordinated.receipt.moduleDigest == verified.moduleDigest)
        check(coordinated.receipt.packageDigest == verified.packageDigest)
        check(coordinated.observations.size == 7)
        check(coordinated.observations.groupingBy { it.sourceRole }.eachCount() == mapOf(
            SourceRole.CALENDAR to 2, SourceRole.RECENT to 2,
            SourceRole.POSTPONEMENT to 2, SourceRole.DIRECT to 1,
        ))
        val serviceGenerationAfterCoordinator = requireNotNull(runtime.lastDiagnostics).serviceGeneration
        check(serviceGenerationBeforeCoordinator == serviceGenerationAfterCoordinator)

        val recentResponse = roleResponses.getValue(SourceRole.RECENT)
        val correctRecentHash = sha256(requireNotNull(recentResponse.bodyUtf8).toByteArray(Charsets.UTF_8))
        val wrongRecentHash = if (correctRecentHash.first() == '0') "1" + correctRecentHash.drop(1)
            else "0" + correctRecentHash.drop(1)
        val badDigestTransport = object : DestinationBoundExtensionTransport {
            override val dnsDestinationBindingVerified: Boolean = true
            override suspend fun execute(
                extension: VerifiedExtensionPackage,
                request: RequestSpec,
            ): ResponseEnvelope = recentResponse.copy(sourceHash = wrongRecentHash)
        }
        val badDigestResult = coordinator(badDigestTransport).execute(ExtensionRunRequest(
            verified.providerId, "ep04-aniworld-bad-response-digest", setOf(SourceRole.RECENT), emptyList()))
        check(badDigestResult is ExtensionHostResult.Failed &&
            badDigestResult.code == ExtensionHostFailureCode.HOST_VALIDATION_FAILED)

        val cacheHitPlan = executeSuccess(
            runtime, verified, "plan_requests", releasePlanInputBytes, planLimits)
        check(cacheHitPlan.contentEquals(releasePlanBytes))
        val cacheDiagnostics = requireNotNull(runtime.lastDiagnostics)
        check(cacheDiagnostics.cacheHit)

        // Fuel exhaustion is a deterministic guest abort, independent of scheduler timing. The
        // subsequent ordinary invocation proves that the service and module remain usable.
        val abort = runtime.execute(
            verified.moduleDigest, verified.moduleBytes, "parse_responses", vector("recent-release-parse"),
            aniWorldParseLimits.copy(fuel = 1L, deadlineMillis = 2_000))
        check(abort == ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)) {
            "fuel-limited AniWorld call returned $abort"
        }
        val afterAbort = executeSuccess(
            runtime, verified, "plan_requests", releasePlanInputBytes, planLimits)
        check(ExtensionWireCodec.decodePlanOutput(afterAbort, releasePlanInput.context).requests.size == 4)

        val identityBeforeRestart = requireNotNull(runtime.lastDiagnostics)
        check(!identityBeforeRestart.serviceInternetPermissionGranted)
        check(runtime.killServiceForTesting())
        var recoveredIdentity: ExtensionRuntimeCallDiagnostics? = null
        withTimeout(10_000) {
            while (recoveredIdentity == null) {
                when (val recoveredCall = runtime.execute(
                    verified.moduleDigest, verified.moduleBytes, "plan_requests", releasePlanInputBytes, planLimits)) {
                    is ExtensionRuntimeResult.Success -> {
                        val plan = ExtensionWireCodec.decodePlanOutput(
                            recoveredCall.outputUtf8, releasePlanInput.context)
                        check(plan.requests.size == 4)
                        val diagnostics = requireNotNull(runtime.lastDiagnostics)
                        if (diagnostics.serviceGeneration > identityBeforeRestart.serviceGeneration &&
                            diagnostics.servicePid != identityBeforeRestart.servicePid) {
                            recoveredIdentity = diagnostics
                        }
                    }
                    is ExtensionRuntimeResult.Failure -> delay(10)
                }
            }
        }
        check(!requireNotNull(recoveredIdentity).serviceInternetPermissionGranted)

        val releaseDiagnostics = requireNotNull(runtime.lastDiagnostics)
        check(!releaseDiagnostics.serviceInternetPermissionGranted)
        return JSONObject()
            .put("sourceCommit", sourceCommit)
            .put("moduleDigest", verified.moduleDigest)
            .put("packageDigest", verified.packageDigest)
            .put("generatedVectorCount", vectorCaseNames.size)
            .put("releasePlanRoles", JSONArray(releasePlan.requests.map { it.sourceRole.name }))
            .put("calendarObservations", calendar.observations.size)
            .put("recentObservations", recent.observations.size)
            .put("postponementObservations", postponement.observations.size)
            .put("directObservations", direct.observations.size)
            .put("overviewNavigationUrl", overviewTargets.single().url)
            .put("episodeNavigationUrl", episodeTargets.single().url)
            .put("failClosedVectors", failClosedCases)
            .put("moduleDigestMismatchRejected", moduleDigestMismatch.code.name)
            .put("responseBodyDigestMismatchRejected", badDigestResult.code.name)
            .put("realGuestCoordinator", JSONObject()
                .put("completed", true)
                .put("observationCount", coordinated.observations.size)
                .put("requestGenerationId", requestGeneration)
                .put("receiptGenerationId", coordinated.receipt.generationId)
                .put("receiptGenerationMatchesRequest", coordinated.receipt.generationId == requestGeneration)
                .put("runtimeServiceGenerationBefore", serviceGenerationBeforeCoordinator)
                .put("runtimeServiceGenerationAfter", serviceGenerationAfterCoordinator)
                .put("productionNetworkLedgerUsed", false))
            .put("realGuestCacheHit", JSONObject()
                .put("cacheHit", cacheDiagnostics.cacheHit)
                .put("outputStable", cacheHitPlan.contentEquals(releasePlanBytes)))
            .put("realGuestCancellation", JSONObject()
                .put("enteredIsolatedService", true)
                .put("coroutineCancelled", realGuestCancellation)
                .put("lateResultFenced", true)
                .put("normalCallRecovered", true))
            .put("realGuestFuelAbort", JSONObject()
                .put("fuel", 1)
                .put("runtimeResult", "FAILURE")
                .put("errorCode", ExtensionRuntimeErrorCode.TRAP.name)
                .put("normalCallRecovered", true))
            .put("realGuestRestartRecovery", JSONObject()
                .put("oldPid", identityBeforeRestart.servicePid)
                .put("newPid", requireNotNull(recoveredIdentity).servicePid)
                .put("oldGeneration", identityBeforeRestart.serviceGeneration)
                .put("newGeneration", requireNotNull(recoveredIdentity).serviceGeneration)
                .put("normalCallRecovered", true))
            .put("releaseGuestMicros", releaseDiagnostics.guestMicros)
            .put("releaseCompileMicros", releaseDiagnostics.compileMicros)
            .put("releaseInstantiateMicros", releaseDiagnostics.instantiateMicros)
            .put("isolatedServiceNoInternet", true)
    }

    private fun navigationContextFromVector(bytes: ByteArray): NavigationContextV1 {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val value = root.optJSONObject("context") ?: root
        fun optionalString(key: String): String? = if (value.isNull(key)) null else value.getString(key)
        val track = optionalString("track")?.let(ObservationTrack::valueOf)
        return NavigationContextV1(
            schemaVersion = value.getInt("schemaVersion"),
            extensionId = ExtensionId.parse(value.getString("extensionId")),
            providerId = ProviderId.parse(value.getString("providerId")),
            observedAt = value.getString("observedAt"),
            targetKind = NavigationTargetKind.valueOf(value.getString("targetKind")),
            targetToken = value.getString("targetToken"),
            providerSeriesKey = value.getString("providerSeriesKey"),
            providerRouteHint = optionalString("providerRouteHint"),
            sourceSeason = if (value.isNull("sourceSeason")) null else value.getInt("sourceSeason"),
            providerEpisode = optionalString("providerEpisode"),
            track = track,
        )
    }

    private fun navigationResponsesFromVector(bytes: ByteArray): List<NavigationResponseEnvelopeV1> {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val values = root.getJSONArray("responses")
        return (0 until values.length()).map { index ->
            val response = values.getJSONObject(index)
            fun optionalString(key: String): String? = if (response.isNull(key)) null else response.getString(key)
            NavigationResponseEnvelopeV1(
                requestId = response.getString("requestId"),
                status = ExtensionResponseStatus.valueOf(response.getString("status")),
                httpStatus = if (response.isNull("httpStatus")) null else response.getInt("httpStatus"),
                finalUrl = optionalString("finalUrl"),
                bodyUtf8 = optionalString("bodyUtf8"),
                sourceHash = optionalString("sourceHash"),
            )
        }
    }

    private suspend fun runReleaseHost(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        transport: DestinationBoundExtensionTransport,
        httpsFixture: LocalHttpsFixtureServer,
    ): JSONObject {
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                verified.takeIf { it.providerId == providerId }
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
        check(result.receipt.packageDigest == verified.packageDigest)
        check(result.receipt.generationId == "ep02-android-fixture")
        val observation = result.observations.single()
        check(observation.extensionId == verified.extensionId)
        check(observation.providerId == verified.providerId)
        check(observation.sourceRole == SourceRole.CALENDAR)
        check(observation.sourceHash == RELEASE_HASH)
        val provenance = result.responseProvenance.single()
        check(provenance.requestId == "calendar-1")
        check(provenance.finalUrl == "https://example.org/calendar")
        check(provenance.httpStatus == 200)
        check(provenance.sourceHash == RELEASE_HASH)
        check(provenance.destinationAddress == TEST_PUBLIC_ADDRESS)
        check(provenance.redirectCount == 0)
        check(httpsFixture.pathCount("/calendar") == 1)
        return JSONObject()
            .put("observationCount", result.observations.size)
            .put("extensionId", observation.extensionId.value)
            .put("providerId", observation.providerId.value)
            .put("sourceRole", observation.sourceRole.name)
            .put("productionTransportReached", true)
            .put("successfulSocketPathExercised", true)
            .put("destinationAddress", provenance.destinationAddress)
            .put("sourceHash", provenance.sourceHash)
            .put("packageDigestPinned", result.receipt.packageDigest == verified.packageDigest)
            .put("generationPinned", result.receipt.generationId == "ep02-android-fixture")
    }

    private fun freshProductionTransport(
        context: Context,
        name: String,
    ): DestinationBoundExtensionTransport {
        require(name.matches(Regex("[a-z-]+")))
        val directory = File(context.cacheDir, "ep02-production-https-$name-ledger")
        check(!directory.exists() || directory.deleteRecursively())
        return ProductionExtensionTransportFactory.create(directory)
    }

    /** Executes production DNS/TLS/redirect/body/cancellation policy over a local HTTPS socket. */
    private suspend fun proveProductionHttpsSocketPath(
        verified: VerifiedExtensionPackage,
        transport: DestinationBoundExtensionTransport,
        httpsFixture: LocalHttpsFixtureServer,
    ): JSONObject {
        val expectedAddress = InetAddress.getByName(TEST_PUBLIC_ADDRESS)
        val resolved = InetAddress.getAllByName("example.org").toList()
        check(resolved == listOf(expectedAddress)) {
            "production resolver did not use the emulator-only fixture mapping: $resolved"
        }
        check(transport.dnsDestinationBindingVerified)

        val redirect = transport.execute(verified, RequestSpec(
            "proof-redirect", SourceRole.CALENDAR, "https://example.org/redirect", ExtensionMethod.GET, null))
        check(redirect.status == ExtensionResponseStatus.OK)
        check(redirect.httpStatus == 200)
        check(redirect.finalUrl == "https://example.org/redirect-final")
        check(redirect.sourceHash == RELEASE_HASH)
        check(httpsFixture.pathCount("/redirect") == 1)
        check(httpsFixture.pathCount("/redirect-final") == 1)

        val beforePrivate = httpsFixture.pathCount("/calendar")
        val privateFailure = runCatching {
            transport.execute(verified, RequestSpec(
                "proof-private-destination", SourceRole.CALENDAR,
                "https://private.example.org/calendar", ExtensionMethod.GET, null))
        }.exceptionOrNull()
        check(privateFailure is IllegalArgumentException && privateFailure.message == "forbidden DNS answer")
        check(httpsFixture.pathCount("/calendar") == beforePrivate)

        val beforeWrongHost = httpsFixture.pathCount("/calendar")
        val hostnameMismatch = transport.execute(verified, RequestSpec(
            "proof-tls-hostname", SourceRole.CALENDAR,
            "https://wrong.example.org/calendar", ExtensionMethod.GET, null))
        check(hostnameMismatch.status == ExtensionResponseStatus.TRANSPORT_FAILURE)
        check(hostnameMismatch.httpStatus == null && hostnameMismatch.sourceHash == null)
        check(httpsFixture.pathCount("/calendar") == beforeWrongHost)

        val oversizedFailure = runCatching {
            transport.execute(verified, RequestSpec(
                "proof-body-limit", SourceRole.CALENDAR,
                "https://example.org/large", ExtensionMethod.GET, null))
        }.exceptionOrNull()
        check(oversizedFailure is IllegalArgumentException)
        val bodyStreamAborted = httpsFixture.awaitLargeBodyAbort()
        check(bodyStreamAborted) {
            "bounded response did not stop fixture stream; completed=${httpsFixture.largeBodyCompleted()}"
        }
        check(!httpsFixture.largeBodyCompleted())

        val cancellation = coroutineScope {
            val request = async(Dispatchers.Default) {
                transport.execute(verified, RequestSpec(
                    "proof-body-cancel", SourceRole.CALENDAR,
                    "https://example.org/slow", ExtensionMethod.GET, null))
            }
            val bodyStarted = withContext(Dispatchers.IO) { httpsFixture.awaitSlowBodyStart() }
            check(bodyStarted) { "HTTPS fixture did not reach a streaming response body" }
            delay(150)
            request.cancel()
            val failure = runCatching { request.await() }.exceptionOrNull()
            httpsFixture.releaseSlowBody()
            check(failure is CancellationException) { "body cancellation returned $failure" }
            failure
        }
        check(cancellation is CancellationException)
        check(httpsFixture.awaitSlowBodyAbort())
        check(!httpsFixture.slowBodyCompleted())

        return JSONObject()
            .put("productionTransportReached", true)
            .put("successfulSocketPathExercised", true)
            .put("destinationAddress", expectedAddress.hostAddress)
            .put("redirectRevalidatedAndFollowed", true)
            .put("privateDestinationRejectedBeforeSocket", true)
            .put("tlsHostnameMismatchRejected", true)
            .put("bodyLimitAbortedStreamingResponse", true)
            .put("cancellationDuringBody", true)
            .put("testCaScopedToFixtureApp", true)
            .put("tlsHandshakeFailures", httpsFixture.tlsHandshakeFailureCount())
    }

    private suspend fun runProductionNavigationDispatch(
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        transport: DestinationBoundExtensionTransport,
        httpsFixture: LocalHttpsFixtureServer,
    ): JSONObject {
        val repository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                verified.takeIf { it.providerId == providerId }
        }
        val request = NavigationContextV1(
            1, verified.extensionId, verified.providerId, "2026-09-28T12:00:00Z",
            NavigationTargetKind.OVERVIEW, "overview-1", "series-1", null, 2, null, null,
        )
        val result = ProductionNavigationDispatcher(repository, runtime, transport)
            .navigateWithProvenance(request, "ep02-android-navigation-fixture")
        check(result != null)
        check(result.target.targetKind == NavigationTargetKind.OVERVIEW)
        check(result.target.url == "https://example.org/series/1")
        check(result.target.requestId == "nav-1" && result.target.sourceHash == NAV_HASH)
        val provenance = result.responseProvenance.single()
        check(provenance.requestId == "nav-1")
        check(provenance.finalUrl == "https://example.org/nav-source")
        check(provenance.sourceHash == NAV_HASH)
        check(provenance.destinationAddress == TEST_PUBLIC_ADDRESS)
        check(httpsFixture.pathCount("/nav-source") == 1)
        return JSONObject()
            .put("productionTransportReached", true)
            .put("successfulSocketPathExercised", true)
            .put("targetKind", result.target.targetKind.name)
            .put("targetUrl", result.target.url)
            .put("sourceHash", provenance.sourceHash)
            .put("destinationAddress", provenance.destinationAddress)
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

    private const val TEST_PUBLIC_ADDRESS = "8.8.8.8"

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

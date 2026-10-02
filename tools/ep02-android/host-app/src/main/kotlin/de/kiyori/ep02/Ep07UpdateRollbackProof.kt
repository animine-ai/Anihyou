// This standalone test APK inspects the private installer journal just as the
// retained Java fixture bridges do. It is never included in the product APK.
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package de.kiyori.ep02

import android.content.Context
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.navigation.ProviderCoordinate
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationCoordinator
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationResult
import com.axiel7.anihyou.release.core.source.*
import com.axiel7.anihyou.release.data.extension.*
import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Actual Android signed-source update, trust journal, package-generation and rollback proof. */
internal object Ep07UpdateRollbackProof {
    private const val EXTENSION_ID = "fixture.release"
    private const val PROVIDER_ID = "fixture"
    private const val SOURCE_URL = "https://packages.example.org/ep07"
    private const val TEST_ORIGIN = "https://packages.example.org"
    private const val FIXTURE_TIME = "2026-09-29T12:00:00Z"
    private val NOW: Instant = Instant.parse(FIXTURE_TIME)
    private val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val LIMITS = ExtensionExecutionLimits(
        maxInputBytes = 256 * 1024,
        maxOutputBytes = 64 * 1024,
        memoryBytes = 32 * 1024 * 1024,
        fuel = 10_000_000,
        deadlineMillis = 2_000,
    )

    suspend fun run(context: Context, runtime: AndroidIsolatedExtensionRuntime, baseModule: ByteArray): JSONObject {
        val fixtureDirectory = File(context.cacheDir, "ep07-signed-update-fixtures").apply {
            check(!exists() || deleteRecursively())
            check(mkdirs())
        }
        val generated = Ep07TestChainBridge.generate(fixtureDirectory, baseModule)
        val pinJson = JSONObject(read(fixtureDirectory, "test-pin.json"))
        val pin = AppTrustPin(
            repositoryId = pinJson.getString("repositoryId"),
            initialRootSha256 = pinJson.getString("initialRootSha256"),
            distributionOrigins = pinJson.getJSONArray("distributionOrigins").strings(),
        )
        val anchor = AuthenticatedExtensionSourceAnchor(pin, setOf("example.org"))
        val currentIndex = AtomicReference("index-v1.json")
        val archiveByDigest = linkedMapOf<String, ByteArray>()
        for ((reportKey, archiveName) in listOf(
            "v1" to "v1", "failedV2" to "v2-failure", "v2" to "v2", "v3" to "v3",
        )) {
            val item = generated.getJSONObject(reportKey)
            val bytes = File(fixtureDirectory, "$archiveName.arex").readBytes()
            check(bytes.size.toLong() == item.getLong("packageBytes"))
            check(sha256(bytes) == item.getString("packageDigest"))
            archiveByDigest[item.getString("packageDigest")] = bytes
        }
        val metadataUrls = CopyOnWriteArrayList<String>()
        val archiveFetchUrls = CopyOnWriteArrayList<String>()
        val transport = ExtensionRepositoryTransport { url, allowedOrigins, maxBytes ->
            check(allowedOrigins == setOf(TEST_ORIGIN))
            metadataUrls += url
            val bytes = when (url) {
                "$SOURCE_URL/root.json" -> File(fixtureDirectory, "root.json").readBytes()
                "$SOURCE_URL/index.json" -> File(fixtureDirectory, currentIndex.get()).readBytes()
                else -> {
                    val digest = url.substringAfterLast('/').removeSuffix(".arex")
                    require(url.startsWith("$TEST_ORIGIN/dist/$EXTENSION_ID/") && url.endsWith(".arex"))
                    archiveFetchUrls += url
                    archiveByDigest[digest] ?: error("unexpected fixture archive request: $url")
                }
            }
            require(bytes.size <= maxBytes) { "generated source response exceeded declared cap" }
            bytes.copyOf()
        }
        val stores = CopyOnWriteArrayList<ExtensionInstallStore>()
        val smokeRuns = CopyOnWriteArrayList<JSONObject>()
        val sourceDirectory = File(context.cacheDir, "ep07-signed-update-source").apply {
            check(!exists() || deleteRecursively())
        }

        fun runGuest(extension: VerifiedExtensionPackage): JSONObject = runBlocking {
            val releaseContext = ExtensionContextV1(
                extension.extensionId, extension.providerId, listOf(SourceRole.CALENDAR), FIXTURE_TIME, emptyList(),
            )
            val planBytes = guestCall(runtime, extension, "plan_requests",
                ExtensionWireCodec.encodePlanInput(PlanInputV1(1, releaseContext)))
            val plan = ExtensionWireCodec.decodePlanOutput(planBytes, releaseContext)
            check(plan.requests.size == 1 && plan.requests.single().url == "https://example.org/calendar") {
                "verified package did not return the real Calendar plan: ${plan.requests}"
            }
            val navigationContext = NavigationContextV1(
                schemaVersion = 1,
                extensionId = extension.extensionId,
                providerId = extension.providerId,
                observedAt = FIXTURE_TIME,
                targetKind = NavigationTargetKind.EPISODE,
                targetToken = "ep07-episode-1",
                providerSeriesKey = "series-1",
                providerRouteHint = null,
                sourceSeason = 2,
                providerEpisode = "15",
                track = ObservationTrack.DE_SUB,
            )
            val navigationBytes = guestCall(runtime, extension, "plan_navigation",
                NavigationWireCodecV1.encodeContext(navigationContext))
            val navigationPlan = NavigationWireCodecV1.decodePlan(
                navigationBytes, navigationContext, extension.grantedHosts,
            )
            check(navigationPlan.requests.singleOrNull()?.let {
                it.requestId == "nav-1" && it.url == "https://example.org/nav-source"
            } == true) { "verified package did not return its real navigation plan" }
            val navigationResponse = NavigationResponseEnvelopeV1(
                requestId = "nav-1",
                status = ExtensionResponseStatus.OK,
                httpStatus = 200,
                finalUrl = "https://example.org/nav-source",
                bodyUtf8 = "fixture navigation",
                sourceHash = sha256("fixture navigation".toByteArray(StandardCharsets.UTF_8)),
            )
            val parseInput = NavigationWireCodecV1.encodeParseInput(
                navigationContext, listOf(navigationResponse), extension.grantedHosts,
            )
            val output = guestCall(runtime, extension, "parse_navigation", parseInput)
            val navigationTarget = NavigationWireCodecV1.decodeTargets(
                output, navigationContext, listOf(navigationResponse), extension.grantedHosts,
            ).targets.single()
            check(navigationTarget.url == "https://example.org/series/1/episode/15" &&
                navigationTarget.requestId == "nav-1" && navigationTarget.sourceHash == navigationResponse.sourceHash) {
                "verified package did not return a host-bound episode target"
            }
            JSONObject().put("planRequests", true).put("calendarRequestCount", plan.requests.size)
                .put("calendarUrl", plan.requests.single().url).put("navigation", true)
                .put("navigationTargetKind", NavigationTargetKind.EPISODE.name)
                .put("navigationPlanUrl", navigationPlan.requests.single().url)
                .put("navigationUrl", navigationTarget.url).put("navigationRequestId", navigationTarget.requestId)
                .put("navigationSourceHash", navigationTarget.sourceHash)
                .put("moduleDigest", extension.moduleDigest).put("packageDigest", extension.packageDigest)
                .put("packageGeneration", extension.packageGeneration)
        }

        val repository = FileExtensionSourceRepository(
            directory = sourceDirectory,
            bootstrap = ExtensionSourceTrustBootstrap { source ->
                check(source.origin == TEST_ORIGIN)
                anchor
            },
            transport = transport,
            storeFactory = ExtensionSourceStoreFactory { storeDir, authenticated ->
                ExtensionInstallStore(
                    directory = storeDir,
                    pin = authenticated.pin,
                    verifier = ExtensionPackageVerifier(
                        CombinedWasmModuleProfileVerifier(WasmtimeNativeModuleProfileVerifier()),
                    ),
                    hostRoles = SourceRole.entries.toSet(),
                    hostHosts = authenticated.allowedHosts,
                    policyVersion = 1,
                    runtimeVersion = "wasmtime-48.0.3-cranelift-android",
                    smoke = { candidate ->
                        val result = runGuest(candidate)
                        smokeRuns += result.put("releaseSequence", candidate.releaseSequence)
                        if (candidate.packageDigest == generated.getJSONObject("failedV2").getString("packageDigest")) {
                            throw IllegalStateException("EP07 deterministic post-Wasmtime smoke failure")
                        }
                    },
                ).also(stores::add)
            },
            scheduler = ExtensionSourceScheduler { },
            clock = CLOCK,
            runtimeSupported = true,
        )

        check(repository.add(SOURCE_URL) is AddExtensionSourceResult.Added)
        val sourceId = repository.sources.value.single().id
        repository.activate(sourceId, EXTENSION_ID)
        var sourceEntry = repository.sources.value.single().extensions.single()
        check(sourceEntry.extensionId == EXTENSION_ID && sourceEntry.installedVersion == "1.0.0-test.1")
        val key = ExtensionSelectionKey(sourceId, sourceEntry.extensionId, sourceEntry.publisherId, sourceEntry.providerId)
        val productPolicy = requireNotNull(repository.productPolicy)
        val preferences = ExtensionPreferences(
            enabledTracks = setOf(ObservationTrack.DE_SUB.name),
            preferredTrackOrder = listOf(ObservationTrack.DE_SUB.name),
            languageOrder = listOf("de"),
            visibleInProviderField = true,
        )
        productPolicy.selectActiveSource(key)
        productPolicy.setPreferences(key, preferences)
        productPolicy.selectNavigationProvider(key)
        val releaseGenerationBeforeUpdate = productPolicy.policy.value.releaseGeneration
        val v1Package = requireNotNull(repository.loadInstalled(key))
        val store = stores.single()
        val v1Snapshot = store.snapshot()
        check(v1Package.extensionId == ExtensionId.parse(EXTENSION_ID) && v1Package.providerId == ProviderId.parse(PROVIDER_ID))
        check(v1Package.releaseSequence == 1L && v1Package.packageGeneration == 1L)
        check(v1Snapshot.generations.getValue(EXTENSION_ID).active?.digest == v1Package.packageDigest)
        check(v1Snapshot.generations.getValue(EXTENSION_ID).knownGood?.digest == v1Package.packageDigest)

        val navigationDirectory = File(context.cacheDir, "ep07-installed-navigation").apply {
            check(!exists() || deleteRecursively())
        }
        val gateway = InstalledProviderNavigationGateway(repository, repository, runtime, navigationDirectory)
        val staleV1Provider = NavigationProvider(
            key = key,
            displayName = v1Package.displayName,
            packageDigest = v1Package.packageDigest,
            capabilities = v1Package.navigationCapabilities,
            allowedHosts = v1Package.grantedHosts,
            supportedTracks = setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
            packageGeneration = v1Package.packageGeneration,
        )
        fun navigationContext(extension: VerifiedExtensionPackage) = NavigationContextV1(
            1, extension.extensionId, extension.providerId, FIXTURE_TIME, NavigationTargetKind.EPISODE,
            "ep07-episode-15", "series-1", null, 2, "15", ObservationTrack.DE_SUB,
        )

        currentIndex.set("index-v2-failure.json")
        repository.activate(sourceId, EXTENSION_ID)
        val failedV2 = generated.getJSONObject("failedV2")
        val failedSnapshot = store.snapshot()
        val failedGeneration = failedSnapshot.generations.getValue(EXTENSION_ID)
        check(failedV2.getString("packageDigest") in failedSnapshot.quarantinedDigests)
        check(failedGeneration.active?.digest == v1Package.packageDigest &&
            failedGeneration.knownGood?.digest == v1Package.packageDigest)
        check(failedGeneration.packageGeneration == v1Package.packageGeneration)
        check(failedSnapshot.operations[EXTENSION_ID]?.state == ExtensionUpdateState.UPDATE_FAILED)
        check(failedSnapshot.operations[EXTENSION_ID]?.failure == ExtensionUpdateFailure.SMOKE)

        currentIndex.set("index-v2.json")
        val archiveFetchCountBeforeCatalogRefresh = archiveFetchUrls.size
        repository.refresh(sourceId)
        val firstCatalogProjection = repository.sources.value.single().extensions.single()
        val archiveFetchCountAfterFirstRefresh = archiveFetchUrls.size
        check(firstCatalogProjection.updateAvailable && firstCatalogProjection.updateState == ExtensionUpdateState.UPDATE_AVAILABLE)
        check(firstCatalogProjection.installedVersion == "1.0.0-test.1")
        check(firstCatalogProjection.latestAvailableVersion == "2.0.0-test.3")
        check(archiveFetchCountAfterFirstRefresh == archiveFetchCountBeforeCatalogRefresh)
        repository.refresh(sourceId)
        val repeatedCatalogProjection = repository.sources.value.single().extensions.single()
        val archiveFetchCountAfterRepeatedRefresh = archiveFetchUrls.size
        check(repeatedCatalogProjection.updateAvailable && repeatedCatalogProjection.updateState == ExtensionUpdateState.UPDATE_AVAILABLE)
        check(repeatedCatalogProjection.installedVersion == "1.0.0-test.1")
        check(repeatedCatalogProjection.latestAvailableVersion == "2.0.0-test.3")
        check(archiveFetchCountAfterRepeatedRefresh == archiveFetchCountAfterFirstRefresh)
        val catalogRefreshProof = JSONObject().put("refreshCount", 2)
            .put("metadataRefreshReportedUpdateAvailable", firstCatalogProjection.updateAvailable && repeatedCatalogProjection.updateAvailable)
            .put("updateStateFirst", firstCatalogProjection.updateState.name)
            .put("updateStateRepeated", repeatedCatalogProjection.updateState.name)
            .put("installedVersionFirst", firstCatalogProjection.installedVersion)
            .put("installedVersionRepeated", repeatedCatalogProjection.installedVersion)
            .put("latestAvailableVersionFirst", firstCatalogProjection.latestAvailableVersion)
            .put("latestAvailableVersionRepeated", repeatedCatalogProjection.latestAvailableVersion)
            .put("installedV1Retained", firstCatalogProjection.installedVersion == "1.0.0-test.1" &&
                repeatedCatalogProjection.installedVersion == "1.0.0-test.1")
            .put("latestAvailableV2", firstCatalogProjection.latestAvailableVersion == "2.0.0-test.3" &&
                repeatedCatalogProjection.latestAvailableVersion == "2.0.0-test.3")
            .put("archiveFetchCountBefore", archiveFetchCountBeforeCatalogRefresh)
            .put("archiveFetchCountAfterFirstRefresh", archiveFetchCountAfterFirstRefresh)
            .put("archiveFetchCountAfterRepeatedRefresh", archiveFetchCountAfterRepeatedRefresh)
            .put("noArchiveFetchDuringMetadataRefresh", archiveFetchCountAfterFirstRefresh == archiveFetchCountBeforeCatalogRefresh)
            .put("repeatedUnchangedRefreshFetchedNoArchive", archiveFetchCountAfterRepeatedRefresh == archiveFetchCountAfterFirstRefresh)

        repository.activate(sourceId, EXTENSION_ID)
        val archiveFetchCountAfterV2Install = archiveFetchUrls.size
        sourceEntry = repository.sources.value.single().extensions.single()
        val v2Package = requireNotNull(repository.loadInstalled(key))
        val v2Snapshot = store.snapshot()
        val v2Generation = v2Snapshot.generations.getValue(EXTENSION_ID)
        val v2Receipt = requireNotNull(v2Generation.active)
        check(v2Receipt.version == "2.0.0-test.3" && v2Package.releaseSequence == 3L)
        check(v2Package.packageDigest != v1Package.packageDigest && v2Package.moduleDigest != v1Package.moduleDigest)
        check(v2Package.packageGeneration > v1Package.packageGeneration)
        check(v2Generation.active?.digest == v2Package.packageDigest && v2Generation.knownGood?.digest == v2Package.packageDigest)
        check(store.safePreviousGood(EXTENSION_ID, CLOCK.instant())?.digest == v1Package.packageDigest)
        check(v2Package.extensionId == v1Package.extensionId && v2Package.providerId == v1Package.providerId &&
            v2Package.publisherId == v1Package.publisherId)
        val v2Guest = runGuest(v2Package).put("version", v2Receipt.version)
        val v2NavigationProvider = gateway.providers().single { it.key == key }
        check(v2NavigationProvider.packageDigest == v2Package.packageDigest &&
            v2NavigationProvider.packageGeneration == v2Package.packageGeneration)
        // The coordinator dispatches through this real installed-package gateway and mints a
        // ValidatedNavigationTarget only after the runtime response passes host identity/track/URL checks.
        val v2Resolved = ProviderNavigationCoordinator(gateway, productPolicy).resolve(
            ProviderCoordinate(key, 7, BigDecimal(15), "series-1", 2, "15",
                availableTracks = setOf(ObservationTrack.DE_SUB.name)), NavigationTargetKind.EPISODE,
        )
        check(v2Resolved is ProviderNavigationResult.Ready)
        val v2ResolvedTarget = (v2Resolved as ProviderNavigationResult.Ready).target
        check(v2ResolvedTarget.provider == v2NavigationProvider &&
            v2ResolvedTarget.url == "https://example.org/series/1/episode/15" &&
            v2ResolvedTarget.track == ObservationTrack.DE_SUB)
        val staleV1TokenAfterUpdate = repository.withCurrentGeneration(
            key, v1Package.packageDigest, v1Package.packageGeneration,
        ) { true }
        check(staleV1TokenAfterUpdate == null)
        check(gateway.withCurrentProvider(staleV1Provider) { true } == null)
        check(gateway.dispatch(staleV1Provider, navigationContext(v1Package), "ep07-stale-v1-provider") == null)
        check(repository.sources.value.single().selectionKey(repository.sources.value.single().extensions.single()) == key)
        check(productPolicy.policy.value.activeReleaseSource == key && productPolicy.policy.value.preferredNavigationProvider == key)
        check(productPolicy.policy.value.preferencesFor(key) == preferences)
        check(productPolicy.policy.value.releaseGeneration == releaseGenerationBeforeUpdate)
        val v2NavigationPreferenceProof = JSONObject().put("gatewayListedCurrentV2Provider", true)
            .put("realV2DispatchReturnedTarget", true).put("coordinatorResolvedValidTarget", true)
            .put("targetUrl", v2ResolvedTarget.url).put("packageDigest", v2NavigationProvider.packageDigest)
            .put("packageGeneration", v2NavigationProvider.packageGeneration)
            .put("preferredNavigationProviderPreserved", productPolicy.policy.value.preferredNavigationProvider == key)
            .put("visibleInProviderFieldPreserved", productPolicy.policy.value.preferencesFor(key).visibleInProviderField)

        val safePrevious = requireNotNull(store.safePreviousGood(EXTENSION_ID, CLOCK.instant()))
        val generationBeforeRollback = v2Generation.packageGeneration
        repository.rollback(sourceId, EXTENSION_ID, generationBeforeRollback, safePrevious.digest)
        val rolledBack = store.snapshot()
        val rolledBackGeneration = rolledBack.generations.getValue(EXTENSION_ID)
        val restoredV1 = requireNotNull(repository.loadInstalled(key))
        check(rolledBackGeneration.active?.digest == v1Package.packageDigest && rolledBackGeneration.knownGood?.digest == v1Package.packageDigest)
        check(rolledBackGeneration.previousGood == null && rolledBackGeneration.rollbackUsed)
        check(rolledBackGeneration.packageGeneration > generationBeforeRollback)
        check(rolledBack.releaseHigh[EXTENSION_ID] == v2Package.releaseSequence)
        check(restoredV1.packageDigest == v1Package.packageDigest && restoredV1.packageGeneration == rolledBackGeneration.packageGeneration)
        val restoredV1Guest = runGuest(restoredV1)
        check(restoredV1Guest.getBoolean("planRequests") && restoredV1Guest.getBoolean("navigation"))
        val rolledBackNavigationProvider = gateway.providers().single { it.key == key }
        check(rolledBackNavigationProvider.packageDigest == restoredV1.packageDigest &&
            rolledBackNavigationProvider.packageGeneration == restoredV1.packageGeneration)
        check(productPolicy.policy.value.activeReleaseSource == key && productPolicy.policy.value.preferredNavigationProvider == key &&
            productPolicy.policy.value.preferencesFor(key).visibleInProviderField)
        val staleV1TokenAfterRollback = repository.withCurrentGeneration(
            key, v1Package.packageDigest, v1Package.packageGeneration,
        ) { true }
        check(staleV1TokenAfterRollback == null)
        check(gateway.withCurrentProvider(staleV1Provider) { true } == null)
        check(gateway.dispatch(staleV1Provider, navigationContext(v1Package), "ep07-consumed-v1-token") == null)

        currentIndex.set("index-v3.json")
        repository.activate(sourceId, EXTENSION_ID)
        val v3Package = requireNotNull(repository.loadInstalled(key))
        val beforeRecovery = store.snapshot()
        val generationBeforeRecovery = beforeRecovery.generations.getValue(EXTENSION_ID).packageGeneration
        val v3Receipt = requireNotNull(beforeRecovery.generations.getValue(EXTENSION_ID).active)
        check(v3Receipt.version == "3.0.0-test.4" && v3Package.releaseSequence == 4L &&
            v3Package.packageGeneration > restoredV1.packageGeneration)
        check(store.safePreviousGood(EXTENSION_ID, CLOCK.instant())?.digest == v1Package.packageDigest)
        store.beginOperation(EXTENSION_ID, ExtensionUpdateState.CHECKING, failedV2.getString("packageDigest"), CLOCK.instant())

        val interruptedRepository = FileExtensionSourceRepository(
            directory = sourceDirectory,
            bootstrap = ExtensionSourceTrustBootstrap { anchor },
            transport = transport,
            storeFactory = ExtensionSourceStoreFactory { storeDir, authenticated ->
                ExtensionInstallStore(
                    directory = storeDir,
                    pin = authenticated.pin,
                    verifier = ExtensionPackageVerifier(
                        CombinedWasmModuleProfileVerifier(WasmtimeNativeModuleProfileVerifier()),
                    ),
                    hostRoles = SourceRole.entries.toSet(), hostHosts = authenticated.allowedHosts,
                    policyVersion = 1, runtimeVersion = "wasmtime-48.0.3-cranelift-android",
                    smoke = { candidate -> runGuest(candidate) },
                ).also(stores::add)
            },
            scheduler = ExtensionSourceScheduler { }, clock = CLOCK, runtimeSupported = true,
        )
        interruptedRepository.restoreInstalled()
        val recoveredStore = stores.last()
        val recoveredSnapshot = recoveredStore.snapshot()
        val recoveredOperation = recoveredSnapshot.operations.getValue(EXTENSION_ID)
        check(recoveredOperation.completedAt != null && recoveredOperation.state == ExtensionUpdateState.UPDATE_FAILED &&
            recoveredOperation.failure == ExtensionUpdateFailure.INTERRUPTED)
        check(recoveredSnapshot.generations.getValue(EXTENSION_ID).active?.digest == v3Package.packageDigest)
        check(recoveredSnapshot.generations.getValue(EXTENSION_ID).packageGeneration == generationBeforeRecovery)
        check(failedV2.getString("packageDigest") in recoveredSnapshot.quarantinedDigests)
        check(recoveredStore.safePreviousGood(EXTENSION_ID, CLOCK.instant())?.digest == v1Package.packageDigest)

        currentIndex.set("index-v3-revoke-v1.json")
        interruptedRepository.refresh(sourceId)
        val revokedSnapshot = recoveredStore.snapshot()
        val safePreviousAfterRevocation = recoveredStore.safePreviousGood(EXTENSION_ID, CLOCK.instant())
        check(v1Package.packageDigest in revokedSnapshot.revokedDigests && safePreviousAfterRevocation == null)
        val generationBeforeDeniedRollback = revokedSnapshot.generations.getValue(EXTENSION_ID).packageGeneration
        interruptedRepository.rollback(sourceId, EXTENSION_ID, generationBeforeDeniedRollback, v1Package.packageDigest)
        val deniedRollback = recoveredStore.snapshot()
        val deniedGeneration = deniedRollback.generations.getValue(EXTENSION_ID)
        check(deniedGeneration.active?.digest == v3Package.packageDigest)
        check(deniedGeneration.packageGeneration == generationBeforeDeniedRollback)
        check(deniedRollback.operations[EXTENSION_ID]?.state == ExtensionUpdateState.UPDATE_FAILED)
        check(v1Package.packageDigest in deniedRollback.revokedDigests)
        check(interruptedRepository.loadInstalled(key)?.packageDigest == v3Package.packageDigest)

        val generationHistory = JSONArray().put(v1Snapshot.generations.getValue(EXTENSION_ID).packageGeneration)
            .put(v2Generation.packageGeneration).put(rolledBackGeneration.packageGeneration)
            .put(beforeRecovery.generations.getValue(EXTENSION_ID).packageGeneration)
        val sameIdentity = v2Package.extensionId == v1Package.extensionId && v2Package.providerId == v1Package.providerId &&
            v2Package.publisherId == v1Package.publisherId && v3Package.extensionId == v1Package.extensionId
        val signedArtifacts = JSONObject().put("v1VerifiedInstalled", true)
            .put("failedV2SignedCatalogRecognized", failedSnapshot.index?.sequence == 2L)
            .put("v2CatalogVerifiedAndInstalled", v2Snapshot.index?.sequence == 3L)
            .put("v3CatalogVerifiedAndInstalled", beforeRecovery.index?.sequence == 4L)
            .put("sameExtensionIdentity", sameIdentity).put("testPublisherIdentity", v1Package.publisherId)
        return JSONObject().put("testTrustOnly", true).put("productionPublication", false)
            .put("extensionId", EXTENSION_ID).put("providerId", PROVIDER_ID).put("sourceId", sourceId)
            .put("repository", generated).put("catalogRefresh", catalogRefreshProof).put("signedPackages", signedArtifacts)
            .put("v1Installed", JSONObject().put("packageDigest", v1Package.packageDigest)
                .put("moduleDigest", v1Package.moduleDigest).put("packageGeneration", v1Package.packageGeneration)
                .put("knownGood", v1Snapshot.generations.getValue(EXTENSION_ID).knownGood?.digest))
            .put("controlledV2Failure", JSONObject().put("quarantined", true).put("failure", ExtensionUpdateFailure.SMOKE.name)
                .put("activeV1Preserved", failedGeneration.active?.digest == v1Package.packageDigest)
                .put("knownGoodV1Preserved", failedGeneration.knownGood?.digest == v1Package.packageDigest)
                .put("generationUnchanged", failedGeneration.packageGeneration == v1Package.packageGeneration)
                .put("packageDigest", failedV2.getString("packageDigest")))
            .put("v2Update", JSONObject().put("version", v2Receipt.version)
                .put("packageDigest", v2Package.packageDigest).put("moduleDigest", v2Package.moduleDigest)
                .put("packageGeneration", v2Package.packageGeneration).put("releaseSequence", v2Package.releaseSequence)
                .put("guest", v2Guest).put("previousGoodV1Available", true)
                .put("archiveFetchCountAfterInstall", archiveFetchCountAfterV2Install))
            .put("retainedPreferences", JSONObject().put("sameIdentity", sameIdentity)
                .put("activeSourcePreserved", productPolicy.policy.value.activeReleaseSource == key)
                .put("preferencesPreserved", productPolicy.policy.value.preferencesFor(key) == preferences)
                .put("enabledTracks", JSONArray(preferences.enabledTracks.sorted()))
                .put("preferredTrackOrder", JSONArray(preferences.preferredTrackOrder))
                .put("languageOrder", JSONArray(preferences.languageOrder))
                .put("releaseGeneration", productPolicy.policy.value.releaseGeneration)
                .put("releaseGenerationUnchangedByUpdate", productPolicy.policy.value.releaseGeneration == releaseGenerationBeforeUpdate)
                .put("preferredNavigationProviderPreserved", productPolicy.policy.value.preferredNavigationProvider == key)
                .put("visibleInProviderFieldPreserved", productPolicy.policy.value.preferencesFor(key).visibleInProviderField))
            .put("v2NavigationPreferenceProof", v2NavigationPreferenceProof)
            .put("staleV1FencedAfterUpdate", JSONObject().put("generationTokenRejected", staleV1TokenAfterUpdate == null)
                .put("navigationProviderRejected", true).put("navigationDispatchRejected", true))
            .put("explicitRollback", JSONObject().put("targetDigest", safePrevious.digest)
                .put("targetVersion", safePrevious.version).put("expectedGeneration", generationBeforeRollback)
                .put("activeV1Restored", restoredV1.packageDigest == v1Package.packageDigest)
                .put("releaseHighUnchanged", rolledBack.releaseHigh[EXTENSION_ID] == v2Package.releaseSequence)
                .put("packageGeneration", restoredV1.packageGeneration)
                .put("packageGenerationAdvanced", restoredV1.packageGeneration > generationBeforeRollback)
                .put("realV1Guest", restoredV1Guest)
                .put("navigationProviderRetained", rolledBackNavigationProvider.key == key &&
                    rolledBackNavigationProvider.packageDigest == v1Package.packageDigest)
                .put("preferredNavigationProviderPreserved", productPolicy.policy.value.preferredNavigationProvider == key)
                .put("visibilityPreferencePreserved", productPolicy.policy.value.preferencesFor(key).visibleInProviderField))
            .put("staleV1FencedAfterRollback", JSONObject().put("sameDigestDifferentGenerationRejected", staleV1TokenAfterRollback == null)
                .put("consumedNavigationProviderRejected", true).put("consumedNavigationTokenRejected", true))
            .put("packageGenerationHistory", generationHistory)
            .put("operations", JSONObject().put("updateFinished", v2Snapshot.operations[EXTENSION_ID]?.state == ExtensionUpdateState.UPDATED)
                .put("failedUpdateFinished", failedSnapshot.operations[EXTENSION_ID]?.state == ExtensionUpdateState.UPDATE_FAILED)
                .put("rollbackFinished", rolledBack.operations[EXTENSION_ID]?.state == ExtensionUpdateState.ROLLED_BACK)
                .put("interruptedOperationRecovered", recoveredOperation.completedAt != null)
                .put("quarantinePersistsAcrossRestart", failedV2.getString("packageDigest") in recoveredSnapshot.quarantinedDigests)
                .put("interruptedFailure", recoveredOperation.failure?.name))
            .put("revokedPreviousTarget", JSONObject().put("indexSequence", deniedRollback.index?.sequence)
                .put("indexRevocationWasSignedByTestKey", true)
                .put("indexSignerKeyId", generated.getString("revocationIndexKeyId"))
                .put("v1DigestRecordedRevoked", v1Package.packageDigest in deniedRollback.revokedDigests)
                .put("safePreviousGoodUnavailable", safePreviousAfterRevocation == null)
                .put("rollbackAttemptDenied", deniedGeneration.active?.digest == v3Package.packageDigest)
                .put("activeV3Preserved", deniedGeneration.active?.digest == v3Package.packageDigest)
                .put("packageGenerationUnchanged", deniedGeneration.packageGeneration == generationBeforeDeniedRollback)
                .put("operationFailedClosed", deniedRollback.operations[EXTENSION_ID]?.state == ExtensionUpdateState.UPDATE_FAILED))
            .put("repositoryTransportUsed", true).put("productionTransportUsed", false)
            .put("repositoryMetadataRequests", JSONArray(metadataUrls.toList()))
            .put("archiveFetchUrls", JSONArray(archiveFetchUrls.toList()))
            .put("smokeGuestRuns", JSONArray(smokeRuns))
    }

    private suspend fun guestCall(
        runtime: AndroidIsolatedExtensionRuntime,
        extension: VerifiedExtensionPackage,
        method: String,
        input: ByteArray,
    ): ByteArray = when (val result = runtime.execute(extension.moduleDigest, extension.moduleBytes, method, input, LIMITS)) {
        is ExtensionRuntimeResult.Success -> result.outputUtf8
        is ExtensionRuntimeResult.Failure -> error("sequence ${extension.releaseSequence} $method guest call failed: ${result.code}")
    }

    private fun read(directory: File, name: String) = directory.resolve(name).readText(StandardCharsets.UTF_8)
    private fun JSONArray.strings(): Set<String> = (0 until length()).map { getString(it) }.toSet()
    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

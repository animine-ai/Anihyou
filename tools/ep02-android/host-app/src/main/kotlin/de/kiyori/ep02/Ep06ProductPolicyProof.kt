package de.kiyori.ep02

import android.content.Context
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.navigation.ActiveReleaseSnapshot
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.ProviderCoordinate
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeMapper
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationCoordinator
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationGateway
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationResult
import com.axiel7.anihyou.release.core.navigation.ReleasedInstallment
import com.axiel7.anihyou.release.core.navigation.WatchNextResolver
import com.axiel7.anihyou.release.core.navigation.WatchNextState
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.extension.AndroidIsolatedExtensionRuntime
import com.axiel7.anihyou.release.data.extension.DestinationBoundExtensionTransport
import com.axiel7.anihyou.release.data.extension.FileExtensionProductPolicyRepository
import com.axiel7.anihyou.release.data.extension.NavigationDispatchResult
import com.axiel7.anihyou.release.data.extension.ProductionNavigationDispatcher
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionPackage
import com.axiel7.anihyou.release.data.extension.VerifiedExtensionRepository
import java.io.File
import java.math.BigDecimal
import org.json.JSONObject

/**
 * Android domain proof for EP06 using the already accepted signed test package and real host
 * networking. The second release source is deterministic domain metadata only; this does not
 * claim two signed providers or real release-worker ingestion.
 */
internal object Ep06ProductPolicyProof {
    private const val MEDIA_ID = 42
    private const val FIXTURE_HOST_ADDRESS = "8.8.8.8"
    private const val FIXTURE_NAVIGATION_HASH = "96dfb9bbcd4f46a63e8b521bdef0b5b5357f2bd5ddf9cae543f098069b8478fb"

    suspend fun run(
        context: Context,
        runtime: AndroidIsolatedExtensionRuntime,
        verified: VerifiedExtensionPackage,
        transport: DestinationBoundExtensionTransport,
        httpsFixture: LocalHttpsFixtureServer,
    ): JSONObject {
        val fixtureRequestsBefore = httpsFixture.pathCount("/nav-source")
        check(verified.navigationCapabilities.containsAll(setOf(
            NavigationCapability.OVERVIEW_NAVIGATION,
            NavigationCapability.EPISODE_NAVIGATION,
        )))
        check(verified.grantedHosts.contains("example.org"))

        val sourceA = ExtensionSelectionKey(
            "ep06-test-source-a", "ep06.source.a", "ep06-test-publisher-a", "source-a",
        )
        val providerB = ExtensionSelectionKey(
            "ep06-test-source-b", verified.extensionId.value, verified.publisherId, verified.providerId.value,
        )
        val directory = File(context.cacheDir, "ep06-product-policy-domain-proof")
        check(!directory.exists() || directory.deleteRecursively())
        fun policyRepository() = FileExtensionProductPolicyRepository(directory) { it == sourceA || it == providerB }

        val policy = policyRepository()
        check(policy.policy.value.activeReleaseSource == null)
        policy.selectActiveSource(sourceA)
        check(policy.policy.value.activeReleaseSource == sourceA)
        check(policy.policy.value.releaseGeneration == 1L)
        policy.setPreferences(sourceA, ExtensionPreferences(
            enabledTracks = setOf(ObservationTrack.DE_DUB.name),
            preferredTrackOrder = listOf(ObservationTrack.DE_DUB.name),
            languageOrder = listOf("de"),
        ))
        policy.setPreferences(providerB, ExtensionPreferences(
            enabledTracks = setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
            preferredTrackOrder = listOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
            languageOrder = listOf("de"),
        ))
        policy.selectNavigationProvider(providerB)

        val sourceASnapshot = policy.policy.value
        check(sourceASnapshot.activeReleaseSource == sourceA)
        policy.selectActiveSource(providerB)
        check(policy.policy.value.activeReleaseSource == providerB)
        var staleCommitEntered = false
        val staleCommit = policy.withCurrentSelection(sourceASnapshot) {
            staleCommitEntered = true
            "committed"
        }
        check(staleCommit == null && !staleCommitEntered)

        // Reopening the durable repository models an app/process restart for product settings.
        val reopened: ExtensionProductPolicyRepository = policyRepository()
        check(reopened.policy.value.activeReleaseSource == providerB)
        check(reopened.policy.value.preferredNavigationProvider == providerB)
        check(reopened.policy.value.preferencesFor(sourceA).enabledTracks == setOf(ObservationTrack.DE_DUB.name))
        check(reopened.policy.value.preferencesFor(providerB).enabledTracks == setOf(
            ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name,
        ))
        check(reopened.policy.value.preferencesFor(providerB).preferredTrackOrder == listOf(
            ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name,
        ))

        // A remains the sole release source while B is selected only for navigation.
        reopened.selectActiveSource(sourceA)
        check(reopened.policy.value.activeReleaseSource == sourceA)
        check(reopened.policy.value.preferredNavigationProvider == providerB)
        val freshPolicy = reopened.policy.value
        val releases = ActiveReleaseSnapshot(
            key = sourceA,
            policyGeneration = freshPolicy.releaseGeneration,
            installments = listOf(ReleasedInstallment(
                MEDIA_ID, BigDecimal.ONE, setOf(ObservationTrack.DE_DUB.name), authoritative = true,
            )),
        )
        val sourceAProvider = NavigationProvider(
            key = sourceA,
            displayName = "Source A test metadata",
            packageDigest = "a".repeat(64),
            capabilities = setOf(NavigationCapability.OVERVIEW_NAVIGATION, NavigationCapability.EPISODE_NAVIGATION),
            allowedHosts = setOf("example.org"),
            supportedTracks = setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
        )
        val navigationProvider = NavigationProvider(
            key = providerB,
            displayName = verified.displayName,
            packageDigest = verified.packageDigest,
            capabilities = verified.navigationCapabilities,
            allowedHosts = verified.grantedHosts,
            supportedTracks = setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
        )
        val providerFieldProviders = listOf(sourceAProvider, navigationProvider)
        check(providerFieldProviders.map { it.key }.containsAll(listOf(sourceA, providerB)))
        check(reopened.policy.value.activeReleaseSource == sourceA)
        val segment = ProviderEpisodeSegment(
            key = providerB,
            mediaId = MEDIA_ID,
            seriesKey = "series-1",
            sourceSeason = 2,
            providerFirst = 15,
            canonicalFirst = 1,
            count = 1,
        )
        val episodeCoordinate = requireNotNull(ProviderEpisodeMapper.coordinate(
            listOf(segment), providerB, MEDIA_ID, BigDecimal.ONE,
            setOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name),
        ))
        val next = WatchNextResolver().resolve(
            MEDIA_ID,
            BigDecimal.ZERO,
            freshPolicy,
            releases,
            providerFieldProviders,
            listOf(episodeCoordinate),
        )
        check(next is WatchNextState.Candidate)
        check(next.episode.compareTo(BigDecimal.ONE) == 0)
        check(next.provider.key == providerB && next.provider.key != freshPolicy.activeReleaseSource)
        check(next.tracks == listOf(ObservationTrack.DE_SUB.name, ObservationTrack.DE_DUB.name))
        check(episodeCoordinate.providerEpisode == "15")

        val verifiedRepository = object : VerifiedExtensionRepository {
            override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? =
                verified.takeIf { it.providerId == providerId }
        }
        val dispatcher = ProductionNavigationDispatcher(verifiedRepository, runtime, transport)
        val gateway = FixtureNavigationGateway(providerFieldProviders, providerB, dispatcher)
        val coordinator = ProviderNavigationCoordinator(gateway, reopened)

        val overviewCoordinate = ProviderCoordinate(
            key = providerB,
            mediaId = MEDIA_ID,
            canonicalEpisode = null,
            seriesKey = "series-1",
            sourceSeason = 2,
            providerEpisode = null,
        )
        val overviewResult = coordinator.resolve(overviewCoordinate, NavigationTargetKind.OVERVIEW)
        check(overviewResult is ProviderNavigationResult.Ready)
        check(overviewResult.target.url == "https://example.org/series/1")
        check(overviewResult.target.provider.key == providerB)

        // Production success throttling applies across interactive navigation generations.
        kotlinx.coroutines.delay(1_100)
        val episodeResult = coordinator.resolve(episodeCoordinate, NavigationTargetKind.EPISODE)
        check(episodeResult is ProviderNavigationResult.Ready) { "episode navigation: $episodeResult" }
        check(episodeResult.target.url == "https://example.org/series/1/episode/15")
        check(episodeResult.target.provider.key == providerB)
        check(gateway.dispatches.size == 2)
        check(gateway.dispatches.map { it.target.targetKind } == listOf(
            NavigationTargetKind.OVERVIEW, NavigationTargetKind.EPISODE,
        ))
        check(gateway.dispatches.all { result ->
            result.responseProvenance.single().let { provenance ->
                provenance.destinationAddress == FIXTURE_HOST_ADDRESS &&
                    provenance.finalUrl == "https://example.org/nav-source" &&
                    provenance.sourceHash == FIXTURE_NAVIGATION_HASH
            }
        })

        var launchCalls = 0
        val invalidGateway = object : ProviderNavigationGateway {
            override suspend fun providers() = providerFieldProviders
            override suspend fun dispatch(
                provider: NavigationProvider,
                request: NavigationContextV1,
                generation: String,
            ) = dispatcher.navigate(request, generation)?.copy(url = "https://untrusted.example/episode/15")
        }
        val invalidCoordinator = ProviderNavigationCoordinator(invalidGateway, reopened)
        kotlinx.coroutines.delay(1_100)
        val invalidTarget = invalidCoordinator.resolve(episodeCoordinate, NavigationTargetKind.EPISODE)
        if (invalidTarget is ProviderNavigationResult.Ready) {
            invalidCoordinator.launch(invalidTarget.target) {
                launchCalls += 1
                true
            }
        }
        check(invalidTarget == ProviderNavigationResult.Unavailable(NavigationUnavailableReason.INVALID_TARGET))
        check(launchCalls == 0)
        check(httpsFixture.pathCount("/nav-source") == fixtureRequestsBefore + 3)

        return JSONObject()
            .put("status", "PASS")
            .put("domainFixtureOnly", true)
            .put("activeSourceAIsSyntheticTestMetadata", true)
            .put("secondSignedProviderInstalled", false)
            .put("realReleaseWorkerEvidence", false)
            .put("initialActiveSourceNone", true)
            .put("exactlyOneActiveSourceSelectedA", true)
            .put("activeSourceSwitchAtoB", true)
            .put("staleSourceCommitBlocked", true)
            .put("preferencesPersistAcrossRepositoryReopen", true)
            .put("preferencesArePerExtension", true)
            .put("sourceATrackPreferenceDubOnly", true)
            .put("providerBTrackOrderSubBeforeDub", true)
            .put("watchNextUsesReleasedCanonicalEpisodeFromA", true)
            .put("watchNextNavigatesThroughB", true)
            .put("providerFieldShowsNavigationBWhileAActive", true)
            .put("canonicalEpisodeOneMapsToProviderEpisodeFifteen", true)
            .put("activeReleaseSourceRemainsA", reopened.policy.value.activeReleaseSource == sourceA)
            .put("overviewUsesRealSignedFixtureGuestAndProductionTls", true)
            .put("episodeUsesRealSignedFixtureGuestAndProductionTls", true)
            .put("invalidTargetRejectedBeforeLaunch", true)
            .put("launchCallsForInvalidTarget", launchCalls)
            .put("navigationDispatches", gateway.dispatches.size)
            .put("hostTlsFixtureRequestCountDelta", httpsFixture.pathCount("/nav-source") - fixtureRequestsBefore)
            .put("navigationDestinationAddress", FIXTURE_HOST_ADDRESS)
            .put("navigationSourceHash", FIXTURE_NAVIGATION_HASH)
    }

    private class FixtureNavigationGateway(
        private val providersValue: List<NavigationProvider>,
        private val dispatchKey: ExtensionSelectionKey,
        private val dispatcher: ProductionNavigationDispatcher,
    ) : ProviderNavigationGateway {
        val dispatches = mutableListOf<NavigationDispatchResult>()

        override suspend fun providers(): List<NavigationProvider> = providersValue

        override suspend fun dispatch(
            provider: NavigationProvider,
            request: NavigationContextV1,
            generation: String,
        ) = dispatcher.navigateWithProvenance(request, generation)?.also { result ->
            if (provider.key == dispatchKey) dispatches += result
        }?.target
    }
}

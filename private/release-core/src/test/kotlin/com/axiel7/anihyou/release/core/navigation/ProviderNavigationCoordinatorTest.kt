package com.axiel7.anihyou.release.core.navigation

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderNavigationCoordinatorTest {
    @Test
    fun `episode dispatch preserves explicit season episode series and language track`() = runBlocking {
        val fixture = fixture()

        val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

        val target = (result as ProviderNavigationResult.Ready).target
        val request = fixture.gateway.requests.single()
        assertEquals(NAVIGATION_KEY, target.provider.key)
        assertEquals("fixture-series", request.providerSeriesKey)
        assertEquals(2, request.sourceSeason)
        assertEquals("15", request.providerEpisode)
        assertEquals(ObservationTrack.DE_SUB, request.track)
        assertEquals("https://watch.example.org/series/fixture/season/2/episode/15", target.url)
        assertEquals(ACTIVE_KEY, fixture.repository.policy.value.activeReleaseSource)
    }

    @Test
    fun `overview dispatch needs no episode coordinate or language track`() = runBlocking {
        val fixture = fixture()
        val overviewCoordinate = coordinate().copy(
            canonicalEpisode = null,
            sourceSeason = null,
            providerEpisode = null,
            availableTracks = emptySet(),
        )

        val result = fixture.coordinator.resolve(overviewCoordinate, NavigationTargetKind.OVERVIEW)

        assertTrue(result is ProviderNavigationResult.Ready)
        val request = fixture.gateway.requests.single()
        assertEquals(NavigationTargetKind.OVERVIEW, request.targetKind)
        assertEquals("fixture-series", request.providerSeriesKey)
        assertEquals(null, request.sourceSeason)
        assertEquals(null, request.providerEpisode)
        assertEquals(null, request.track)
    }

    @Test
    fun `disabled preferred track is skipped in favor of the enabled supported track`() = runBlocking {
        val preferences = ExtensionPreferences(
            enabledTracks = setOf("DE_DUB"),
            preferredTrackOrder = listOf("DE_SUB", "DE_DUB"),
        )
        val fixture = fixture(
            initialPolicy = productPolicy(preferences = preferences),
            providerInfo = provider(supportedTracks = setOf("DE_SUB", "DE_DUB")),
        )

        val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

        assertTrue(result is ProviderNavigationResult.Ready)
        assertEquals(listOf(ObservationTrack.DE_DUB), fixture.gateway.requests.map { it.track })
    }

    @Test
    fun `missing preferred target falls back through configured track priority`() = runBlocking {
        val fixture = fixture(onDispatch = { _, request, _ ->
            if (request.track == ObservationTrack.DE_SUB) null else target(request)
        })

        val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

        assertTrue(result is ProviderNavigationResult.Ready)
        assertEquals(listOf(ObservationTrack.DE_SUB, ObservationTrack.DE_DUB),
            fixture.gateway.requests.map { it.track })
    }

    @Test
    fun `unsupported or unknown tracks are rejected before extension dispatch`() = runBlocking {
        val cases = listOf(
            provider(supportedTracks = setOf("UNKNOWN")) to coordinate(availableTracks = setOf("UNKNOWN")),
            provider(supportedTracks = setOf("DE_DUB")) to coordinate(availableTracks = setOf("DE_SUB")),
        )

        cases.forEach { (provider, coordinate) ->
            val fixture = fixture(providerInfo = provider)

            val result = fixture.coordinator.resolve(coordinate, NavigationTargetKind.EPISODE)

            assertEquals(NavigationUnavailableReason.TRACK_UNAVAILABLE,
                (result as ProviderNavigationResult.Unavailable).reason)
            assertTrue(fixture.gateway.requests.isEmpty())
        }
    }

    @Test
    fun `episode dispatch rejects missing and malformed exact coordinates`() = runBlocking {
        val cases = listOf(
            coordinate().copy(canonicalEpisode = null),
            coordinate().copy(sourceSeason = 0),
            coordinate().copy(providerEpisode = null),
            coordinate().copy(providerEpisode = "0"),
            coordinate().copy(providerEpisode = "15.123"),
            coordinate().copy(mediaId = 0),
        )

        cases.forEach { coordinate ->
            val fixture = fixture()

            val result = fixture.coordinator.resolve(coordinate, NavigationTargetKind.EPISODE)

            assertEquals(NavigationUnavailableReason.MISSING_MAPPING,
                (result as ProviderNavigationResult.Unavailable).reason)
            assertTrue(fixture.gateway.requests.isEmpty())
        }
    }

    @Test
    fun `extension output must match exact provider identity coordinates and track`() = runBlocking {
        val mutations: List<Pair<String, (ProviderNavigationTargetV1) -> ProviderNavigationTargetV1>> = listOf(
            "extension identity" to { it.copy(extensionId = ExtensionId.parse("de.other")) },
            "provider identity" to { it.copy(providerId = ProviderId.parse("other.provider")) },
            "target kind" to { it.copy(targetKind = NavigationTargetKind.OVERVIEW) },
            "provider series" to { it.copy(providerSeriesKey = "other-series") },
            "source season" to { it.copy(sourceSeason = 3) },
            "provider episode" to { it.copy(providerEpisode = "16") },
            "language track" to { it.copy(track = ObservationTrack.DE_DUB) },
        )

        mutations.forEach { (label, mutate) ->
            val fixture = fixture(onDispatch = { _, request, _ -> mutate(target(request)) })

            val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

            assertEquals(label, NavigationUnavailableReason.INVALID_TARGET,
                (result as ProviderNavigationResult.Unavailable).reason)
        }
    }

    @Test
    fun `target must use https and an exact allowlisted host`() = runBlocking {
        val urls = listOf(
            "http://watch.example.org/series/fixture/season/2/episode/15",
            "https://evil.example.org/series/fixture/season/2/episode/15",
            "https://watch.example.org.evil.test/series/fixture/season/2/episode/15",
        )

        urls.forEach { url ->
            val fixture = fixture(onDispatch = { _, request, _ -> target(request, url) })

            val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

            assertEquals(url, NavigationUnavailableReason.INVALID_TARGET,
                (result as ProviderNavigationResult.Unavailable).reason)
        }
    }

    @Test
    fun `package change while extension dispatches makes the result stale`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = fixture(onDispatch = { _, request, _ ->
            fixture.gateway.availableProviders = listOf(provider(packageDigest = "new-package-digest"))
            target(request)
        })

        val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

        assertEquals(NavigationUnavailableReason.STALE_RESULT,
            (result as ProviderNavigationResult.Unavailable).reason)
    }

    @Test
    fun `preference change while extension dispatches makes the result stale`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = fixture(onDispatch = { _, request, _ ->
            val previous = fixture.repository.policy.value
            fixture.repository.current.value = previous.copy(
                generation = previous.generation + 1,
                preferredNavigationProvider = OTHER_KEY,
            )
            target(request)
        })

        val result = fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)

        assertEquals(NavigationUnavailableReason.STALE_RESULT,
            (result as ProviderNavigationResult.Unavailable).reason)
    }

    @Test
    fun `cancellation from navigation dispatch propagates to caller`() {
        val fixture = fixture(onDispatch = { _, _, _ -> throw CancellationException("caller cancelled") })

        val failure = org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking { fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE) }
        }

        assertEquals("caller cancelled", failure.message)
    }

    @Test
    fun `missing external handler is surfaced without claiming launch success`() = runBlocking {
        val fixture = fixture()
        val target = (fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)
            as ProviderNavigationResult.Ready).target
        var launchCalls = 0

        val result = fixture.coordinator.launch(target) {
            launchCalls++
            false
        }

        assertEquals(1, launchCalls)
        assertEquals(NavigationUnavailableReason.LAUNCH_FAILED,
            (result as ProviderNavigationResult.Unavailable).reason)
    }

    @Test
    fun `changed package makes an already resolved target stale before launch`() = runBlocking {
        val fixture = fixture()
        val target = (fixture.coordinator.resolve(coordinate(), NavigationTargetKind.EPISODE)
            as ProviderNavigationResult.Ready).target
        fixture.gateway.availableProviders = listOf(provider(packageDigest = "replacement-digest"))
        var launchCalls = 0

        val result = fixture.coordinator.launch(target) {
            launchCalls++
            true
        }

        assertEquals(0, launchCalls)
        assertEquals(NavigationUnavailableReason.STALE_RESULT,
            (result as ProviderNavigationResult.Unavailable).reason)
    }

    private fun fixture(
        initialPolicy: ExtensionProductPolicy = productPolicy(),
        providerInfo: NavigationProvider = provider(),
        onDispatch: (suspend (NavigationProvider, NavigationContextV1, String) -> ProviderNavigationTargetV1?)? = null,
    ): Fixture {
        val repository = FakePolicyRepository(initialPolicy)
        val dispatcher: suspend (NavigationProvider, NavigationContextV1, String) -> ProviderNavigationTargetV1? =
            onDispatch ?: { _, request, _ -> target(request) }
        val gateway = FakeGateway(listOf(providerInfo), dispatcher)
        return Fixture(repository, gateway, ProviderNavigationCoordinator(gateway, repository))
    }

    private fun productPolicy(
        generation: Long = 3,
        preferences: ExtensionPreferences = ExtensionPreferences(),
    ) = ExtensionProductPolicy(
        generation = generation,
        activeReleaseSource = ACTIVE_KEY,
        preferredNavigationProvider = NAVIGATION_KEY,
        preferences = mapOf(NAVIGATION_KEY to preferences),
    )

    private fun provider(
        key: ExtensionSelectionKey = NAVIGATION_KEY,
        packageDigest: String = "fixture-package-digest",
        supportedTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
        capabilities: Set<NavigationCapability> = setOf(
            NavigationCapability.OVERVIEW_NAVIGATION,
            NavigationCapability.EPISODE_NAVIGATION,
        ),
        allowedHosts: Set<String> = setOf("watch.example.org"),
    ) = NavigationProvider(key, "Fixture provider", packageDigest, capabilities, allowedHosts, supportedTracks)

    private fun coordinate(
        key: ExtensionSelectionKey = NAVIGATION_KEY,
        mediaId: Int = 42,
        canonicalEpisode: BigDecimal? = BigDecimal("3"),
        seriesKey: String = "fixture-series",
        sourceSeason: Int? = 2,
        providerEpisode: String? = "15",
        availableTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
        routeHint: String? = "season/2/episode/15",
    ) = ProviderCoordinate(key, mediaId, canonicalEpisode, seriesKey,
        sourceSeason, providerEpisode, availableTracks, routeHint)

    private fun target(
        request: NavigationContextV1,
        url: String = "https://watch.example.org/series/fixture/season/2/episode/15",
    ) = ProviderNavigationTargetV1(
        schemaVersion = 1,
        extensionId = request.extensionId,
        providerId = request.providerId,
        targetKind = request.targetKind,
        providerSeriesKey = request.providerSeriesKey,
        sourceSeason = request.sourceSeason,
        providerEpisode = request.providerEpisode,
        track = request.track,
        url = url,
        requestId = "fixture-request",
        sourceHash = "a".repeat(64),
        diagnostics = emptyList(),
    )

    private data class Fixture(
        val repository: FakePolicyRepository,
        val gateway: FakeGateway,
        val coordinator: ProviderNavigationCoordinator,
    )

    private class FakeGateway(
        var availableProviders: List<NavigationProvider>,
        private val dispatcher: suspend (NavigationProvider, NavigationContextV1, String) -> ProviderNavigationTargetV1?,
    ) : ProviderNavigationGateway {
        val requests = mutableListOf<NavigationContextV1>()

        override suspend fun providers(): List<NavigationProvider> = availableProviders

        override suspend fun dispatch(
            provider: NavigationProvider,
            request: NavigationContextV1,
            generation: String,
        ): ProviderNavigationTargetV1? {
            requests += request
            return dispatcher(provider, request, generation)
        }
    }

    private class FakePolicyRepository(initial: ExtensionProductPolicy) : ExtensionProductPolicyRepository {
        val current = MutableStateFlow(initial)
        override val policy: StateFlow<ExtensionProductPolicy> = current.asStateFlow()

        override suspend fun selectActiveSource(key: ExtensionSelectionKey?) {
            current.value = current.value.copy(generation = current.value.generation + 1, activeReleaseSource = key)
        }

        override suspend fun selectNavigationProvider(key: ExtensionSelectionKey?) {
            current.value = current.value.copy(generation = current.value.generation + 1, preferredNavigationProvider = key)
        }

        override suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) {
            current.value = current.value.copy(generation = current.value.generation + 1,
                preferences = current.value.preferences + (key to preferences))
        }

        override suspend fun invalidateSource(sourceId: String) {
            val value = current.value
            current.value = value.copy(generation = value.generation + 1,
                activeReleaseSource = value.activeReleaseSource?.takeUnless { it.sourceId == sourceId },
                preferredNavigationProvider = value.preferredNavigationProvider?.takeUnless { it.sourceId == sourceId })
        }

        override suspend fun <T> withCurrentSelection(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
            if (current.value.activeReleaseSource == snapshot.activeReleaseSource &&
                current.value.releaseGeneration == snapshot.releaseGeneration) block() else null
    }

    companion object {
        private val ACTIVE_KEY = ExtensionSelectionKey("active-source", "de.release", "release-publisher", "release-provider")
        private val NAVIGATION_KEY = ExtensionSelectionKey("navigation-source", "de.watch", "watch-publisher", "watch-provider")
        private val OTHER_KEY = ExtensionSelectionKey("other-source", "de.other", "other-publisher", "other-provider")
    }
}

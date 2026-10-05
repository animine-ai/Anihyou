package com.axiel7.anihyou.release.core.navigation

import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextResolverTest {
    private val resolver = WatchNextResolver()

    @Test fun `dub-only release never adds behind but a confirmed sub can be opened with dub navigation`() {
        val snapshot = ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(
            release("3", tracks = setOf("DE_SUB")),
            release("3.0", tracks = setOf("DE_DUB")),
            release("4", tracks = setOf("DE_DUB")),
        ))
        val state = resolver.resolve(MEDIA_ID, BigDecimal("2"), policy(), snapshot, listOf(provider()),
            listOf(coordinate("3"), coordinate("4"))) as WatchNextState.Candidate
        assertEquals(1, state.behindCount)
        assertEquals(BigDecimal("3"), state.episode)
        val dubOnly = snapshot.copy(installments = listOf(release("4", tracks = setOf("DE_DUB"))))
        assertEquals(NavigationUnavailableReason.NO_RELEASED_UNWATCHED,
            (resolver.resolve(MEDIA_ID, BigDecimal("2"), policy(), dubOnly, listOf(provider()),
                listOf(coordinate("4"))) as WatchNextState.Unavailable).reason)
    }

    @Test
    fun `no active release source is reported explicitly`() {
        val state = resolver.resolve(
            MEDIA_ID,
            BigDecimal("2"),
            ExtensionProductPolicy(preferredNavigationProvider = NAVIGATION_KEY),
            null,
            listOf(provider()),
            emptyList(),
        )

        assertEquals(NavigationUnavailableReason.NO_ACTIVE_SOURCE, (state as WatchNextState.Unavailable).reason)
    }

    @Test
    fun `release snapshot must match active source and current release generation`() {
        val wrongSource = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(OTHER_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider()), listOf(coordinate("3")),
        )
        val staleGeneration = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION - 1, listOf(release("3"))),
            listOf(provider()), listOf(coordinate("3")),
        )

        assertEquals(NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE,
            (wrongSource as WatchNextState.Unavailable).reason)
        assertEquals(NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE,
            (staleGeneration as WatchNextState.Unavailable).reason)
    }

    @Test
    fun `watch next uses the chosen provider exact split cour coordinate independently of release source`() {
        val policy = policy(preferences = mapOf(
            ACTIVE_KEY to ExtensionPreferences(),
            NAVIGATION_KEY to ExtensionPreferences(),
        ))
        val coordinate = coordinate(
            canonicalEpisode = "3",
            key = NAVIGATION_KEY,
            sourceSeason = 2,
            providerEpisode = "15",
            routeHint = "series/example/season-2/episode-15",
        )

        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy,
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider(NAVIGATION_KEY)), listOf(coordinate),
        )

        val candidate = state as WatchNextState.Candidate
        assertEquals(ACTIVE_KEY, policy.activeReleaseSource)
        assertEquals(NAVIGATION_KEY, candidate.provider.key)
        assertEquals(BigDecimal("3"), candidate.episode)
        assertEquals(2, candidate.coordinate.sourceSeason)
        assertEquals("15", candidate.coordinate.providerEpisode)
        assertEquals("series/example/season-2/episode-15", candidate.coordinate.routeHint)
        assertEquals(listOf("DE_SUB", "DE_DUB"), candidate.tracks)
    }

    @Test
    fun `exact mapping makes the single actionable provider the automatic choice`() {
        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(preferred = null),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider(NAVIGATION_KEY), provider(OTHER_KEY)),
            listOf(coordinate("3", key = OTHER_KEY, sourceSeason = 2, providerEpisode = "15")),
        )

        val candidate = state as WatchNextState.Candidate
        assertEquals(OTHER_KEY, candidate.provider.key)
        assertEquals(2, candidate.coordinate.sourceSeason)
        assertEquals("15", candidate.coordinate.providerEpisode)
    }

    @Test
    fun `exact mappings for multiple providers return a chooser`() {
        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(preferred = null),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider(NAVIGATION_KEY), provider(OTHER_KEY)),
            listOf(
                coordinate("3", key = NAVIGATION_KEY, sourceSeason = 1, providerEpisode = "3"),
                coordinate("3", key = OTHER_KEY, sourceSeason = 2, providerEpisode = "15"),
            ),
        )

        val chooser = state as WatchNextState.ChooseProvider
        assertEquals(listOf(NAVIGATION_KEY, OTHER_KEY), chooser.providers.map { it.key })
    }

    @Test
    fun `explicit unavailable navigation preference does not fall back to mapped provider`() {
        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(preferred = UNAVAILABLE_KEY),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider(NAVIGATION_KEY), provider(OTHER_KEY)),
            listOf(
                coordinate("3", key = NAVIGATION_KEY),
                coordinate("3", key = OTHER_KEY, sourceSeason = 2, providerEpisode = "15"),
            ),
        )

        assertEquals(NavigationUnavailableReason.PROVIDER_UNAVAILABLE,
            (state as WatchNextState.Unavailable).reason)
    }

    @Test
    fun `fractional canonical episode keeps its explicit fractional provider mapping`() {
        val fractional = coordinate(
            canonicalEpisode = "3.5",
            sourceSeason = 2,
            providerEpisode = "15.5",
        )

        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("3"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3.50"))),
            listOf(provider()), listOf(fractional),
        )

        val candidate = state as WatchNextState.Candidate
        assertEquals(BigDecimal("3.5"), candidate.episode)
        assertEquals(BigDecimal("3.5"), candidate.coordinate.canonicalEpisode)
        assertEquals("15.5", candidate.coordinate.providerEpisode)
    }

    @Test
    fun `disabled preferred track does not block enabled fallback track`() {
        val preferences = ExtensionPreferences(
            enabledTracks = setOf("DE_DUB"),
            preferredTrackOrder = listOf("DE_SUB", "DE_DUB"),
        )

        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(preferences = mapOf(NAVIGATION_KEY to preferences)),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3", tracks = setOf("DE_SUB", "DE_DUB")))),
            listOf(provider(supportedTracks = setOf("DE_SUB", "DE_DUB"))),
            listOf(coordinate("3", availableTracks = setOf("DE_SUB", "DE_DUB"))),
        )

        val candidate = state as WatchNextState.Candidate
        assertEquals(listOf("DE_DUB"), candidate.tracks)
    }

    @Test
    fun `unknown release tracks and unsupported mapped tracks cannot produce watch next`() {
        val unknownRelease = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3", tracks = setOf("UNKNOWN")))),
            listOf(provider()), listOf(coordinate("3", availableTracks = setOf("DE_SUB"))),
        )
        val trackMissingFromMapping = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider(supportedTracks = setOf("DE_DUB"))),
            listOf(coordinate("3", availableTracks = setOf("DE_SUB"))),
        )
        val unknownProviderTrack = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider(supportedTracks = setOf("UNKNOWN"))),
            listOf(coordinate("3", availableTracks = setOf("UNKNOWN"))),
        )

        assertEquals(NavigationUnavailableReason.NO_RELEASED_UNWATCHED,
            (unknownRelease as WatchNextState.Unavailable).reason)
        assertEquals(NavigationUnavailableReason.TRACK_UNAVAILABLE,
            (trackMissingFromMapping as WatchNextState.Unavailable).reason)
        assertEquals(NavigationUnavailableReason.TRACK_UNAVAILABLE,
            (unknownProviderTrack as WatchNextState.Unavailable).reason)
    }

    @Test
    fun `only authoritative positive unwatched installments for this media are counted once`() {
        val releases = listOf(
            release("3", authoritative = true),
            release("3.0", authoritative = true),
            release("4", authoritative = false),
            release("5", mediaId = MEDIA_ID + 1),
            release("0", authoritative = true),
            release("2", authoritative = true),
        )
        val mappings = listOf(coordinate("3"), coordinate("4"), coordinate("5"), coordinate("0"))

        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, releases),
            listOf(provider()), mappings,
        )

        val candidate = state as WatchNextState.Candidate
        assertEquals(1, candidate.behindCount)
        assertEquals(BigDecimal("3"), candidate.episode)
    }

    @Test
    fun `missing or malformed exact provider mapping never falls back to canonical episode as provider episode`() {
        val missing = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider()), emptyList(),
        )
        val malformed = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), policy(),
            ActiveReleaseSnapshot(ACTIVE_KEY, RELEASE_GENERATION, listOf(release("3"))),
            listOf(provider()),
            listOf(coordinate("3", sourceSeason = 0, providerEpisode = "0")),
        )

        assertEquals(NavigationUnavailableReason.MISSING_MAPPING, (missing as WatchNextState.Unavailable).reason)
        assertEquals(NavigationUnavailableReason.MISSING_MAPPING, (malformed as WatchNextState.Unavailable).reason)
    }

    @Test
    fun `navigation preference generation does not invalidate active release snapshot`() {
        val before = policy()
        val navigationPreferenceChanged = before.copy(
            generation = before.generation + 1,
            preferredNavigationProvider = OTHER_KEY,
        )

        val state = resolver.resolve(
            MEDIA_ID, BigDecimal("2"), navigationPreferenceChanged,
            ActiveReleaseSnapshot(ACTIVE_KEY, before.releaseGeneration, listOf(release("3"))),
            listOf(provider(OTHER_KEY)), listOf(coordinate("3", key = OTHER_KEY)),
        )

        assertTrue(state is WatchNextState.Candidate)
        assertEquals(ACTIVE_KEY, navigationPreferenceChanged.activeReleaseSource)
        assertEquals(OTHER_KEY, (state as WatchNextState.Candidate).provider.key)
    }

    private fun policy(
        generation: Long = GENERATION,
        releaseGeneration: Long = RELEASE_GENERATION,
        active: ExtensionSelectionKey? = ACTIVE_KEY,
        preferred: ExtensionSelectionKey? = NAVIGATION_KEY,
        preferences: Map<ExtensionSelectionKey, ExtensionPreferences> = emptyMap(),
    ) = ExtensionProductPolicy(
        generation = generation,
        activeReleaseSource = active,
        preferredNavigationProvider = preferred,
        preferences = preferences,
        releaseGeneration = releaseGeneration,
    )

    private fun provider(
        key: ExtensionSelectionKey = NAVIGATION_KEY,
        supportedTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
        capabilities: Set<NavigationCapability> = setOf(NavigationCapability.EPISODE_NAVIGATION),
    ) = NavigationProvider(key, "Fixture provider", "digest-$key", capabilities,
        setOf("watch.example.org"), supportedTracks)

    private fun release(
        episode: String,
        mediaId: Int = MEDIA_ID,
        tracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
        authoritative: Boolean = true,
    ) = ReleasedInstallment(mediaId, BigDecimal(episode), tracks, authoritative)

    private fun coordinate(
        canonicalEpisode: String,
        key: ExtensionSelectionKey = NAVIGATION_KEY,
        sourceSeason: Int? = 1,
        providerEpisode: String? = canonicalEpisode,
        availableTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
        routeHint: String? = null,
        mediaId: Int = MEDIA_ID,
    ) = ProviderCoordinate(key, mediaId, BigDecimal(canonicalEpisode), "fixture-series",
        sourceSeason, providerEpisode, availableTracks, routeHint)

    companion object {
        private const val MEDIA_ID = 42
        private const val GENERATION = 7L
        private const val RELEASE_GENERATION = 4L
        private val ACTIVE_KEY = ExtensionSelectionKey("release-source", "de.release", "release-publisher", "release-provider")
        private val NAVIGATION_KEY = ExtensionSelectionKey("navigation-source", "de.watch", "watch-publisher", "watch-provider")
        private val OTHER_KEY = ExtensionSelectionKey("other-source", "de.other", "other-publisher", "other-provider")
        private val UNAVAILABLE_KEY = ExtensionSelectionKey("missing-source", "de.missing", "missing-publisher", "missing-provider")
    }
}

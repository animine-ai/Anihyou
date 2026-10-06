package com.axiel7.anihyou.release.core.navigation

import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.source.*
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class NavigationProvider(
    val key: ExtensionSelectionKey,
    val displayName: String,
    val packageDigest: String,
    val capabilities: Set<NavigationCapability>,
    val allowedHosts: Set<String>,
    val supportedTracks: Set<String>,
    val packageGeneration: Long = 0,
)

/** Exact mappings, including split/cour offsets, are supplied by the host matching plane. */
data class ProviderCoordinate(
    val key: ExtensionSelectionKey,
    val mediaId: Int,
    val canonicalEpisode: BigDecimal?,
    val seriesKey: String,
    val sourceSeason: Int?,
    val providerEpisode: String?,
    val availableTracks: Set<String> = emptySet(),
    val routeHint: String? = null,
)

data class ReleasedInstallment(
    val mediaId: Int,
    val episode: BigDecimal,
    val tracks: Set<String>,
    val authoritative: Boolean,
)

data class ActiveReleaseSnapshot(
    val key: ExtensionSelectionKey,
    val policyGeneration: Long,
    val installments: List<ReleasedInstallment>,
)

enum class NavigationUnavailableReason {
    NO_ACTIVE_SOURCE, RELEASE_SOURCE_UNAVAILABLE, NO_PROVIDERS, PROVIDER_UNAVAILABLE,
    CHOOSE_PROVIDER, NO_RELEASED_UNWATCHED, MISSING_MAPPING, TRACK_UNAVAILABLE, INVALID_TARGET,
    STALE_RESULT, LAUNCH_FAILED, UNKNOWN_PROGRESS,
}

sealed interface WatchNextState {
    data class Unavailable(val reason: NavigationUnavailableReason) : WatchNextState
    data class ChooseProvider(val providers: List<NavigationProvider>) : WatchNextState
    data class Candidate(val behindCount: Int, val episode: BigDecimal,
        val provider: NavigationProvider, val coordinate: ProviderCoordinate, val tracks: List<String>) : WatchNextState
}

/** Backlog eligibility and the exact next coordinate are independent of navigation receipts. */
class WatchNextResolver {
    fun resolve(mediaId: Int, watchedProgress: BigDecimal, policy: ExtensionProductPolicy,
        releases: ActiveReleaseSnapshot?, providers: List<NavigationProvider>,
        mappings: List<ProviderCoordinate>): WatchNextState {
        val progress = runCatching { watchedProgress.intValueExact() }.getOrNull()
            ?: return WatchNextState.Unavailable(NavigationUnavailableReason.UNKNOWN_PROGRESS)
        if (progress < 0) return WatchNextState.Unavailable(NavigationUnavailableReason.UNKNOWN_PROGRESS)
        val active = policy.activeReleaseSource ?: return WatchNextState.Unavailable(NavigationUnavailableReason.NO_ACTIVE_SOURCE)
        if (releases?.key != active || releases.policyGeneration != policy.releaseGeneration)
            return WatchNextState.Unavailable(NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE)
        val facts = releases.installments.filter {
            it.mediaId == mediaId && it.authoritative && "DE_SUB" in it.tracks
        }.mapNotNull { row -> runCatching { row.episode.intValueExact() }.getOrNull()
            ?.let { BacklogEpisodeEvidence(it, released = true) } }
        return resolveNext(mediaId, progress, WatchBacklog.resolve(progress, null, facts).count,
            policy, providers, mappings)
    }

    fun resolveNext(mediaId: Int, progress: Int, backlogCount: Int?, policy: ExtensionProductPolicy,
        providers: List<NavigationProvider>, mappings: List<ProviderCoordinate>): WatchNextState {
        fun unavailable(reason: NavigationUnavailableReason) = WatchNextState.Unavailable(reason)
        if (progress < 0) return unavailable(NavigationUnavailableReason.UNKNOWN_PROGRESS)
        if (backlogCount == null || backlogCount <= 0) return unavailable(NavigationUnavailableReason.NO_RELEASED_UNWATCHED)
        val number = BigDecimal(progress.toLong() + 1)
        val eligible = providers.filter {
            NavigationCapability.EPISODE_NAVIGATION in it.capabilities && policy.preferencesFor(it.key).visibleInProviderField
        }.distinctBy { it.key }
        if (eligible.isEmpty()) return unavailable(NavigationUnavailableReason.NO_PROVIDERS)
        val preferred = policy.preferredNavigationProvider
        if (preferred != null && eligible.none { it.key == preferred })
            return unavailable(NavigationUnavailableReason.PROVIDER_UNAVAILABLE)
        fun coordinate(provider: NavigationProvider) = mappings.filter {
            it.key == provider.key && it.mediaId == mediaId && it.canonicalEpisode?.compareTo(number) == 0
        }.singleOrNull()?.takeIf { it.isExactEpisode() }
        fun tracks(provider: NavigationProvider): List<String> =
            policy.preferencesFor(provider.key, provider.supportedTracks).orderedTracks(provider.supportedTracks)
                .filter { it in coordinate(provider)?.availableTracks.orEmpty() }
        val actionable = eligible.filter { coordinate(it) != null && tracks(it).isNotEmpty() }
        val provider = if (preferred != null) eligible.single { it.key == preferred } else when (actionable.size) {
            0 -> return unavailable(if (eligible.any { coordinate(it) != null })
                NavigationUnavailableReason.TRACK_UNAVAILABLE else NavigationUnavailableReason.MISSING_MAPPING)
            1 -> actionable.single()
            else -> return WatchNextState.ChooseProvider(actionable)
        }
        val exact = coordinate(provider) ?: return unavailable(NavigationUnavailableReason.MISSING_MAPPING)
        val ordered = tracks(provider)
        if (ordered.isEmpty()) return unavailable(NavigationUnavailableReason.TRACK_UNAVAILABLE)
        // Never skip a missing/unavailable next episode to a later available episode.
        return WatchNextState.Candidate(backlogCount, number, provider, exact, ordered)
    }
}

private fun ProviderCoordinate.isExactEpisode(): Boolean = canonicalEpisode != null &&
    canonicalEpisode.signum() > 0 && mediaId > 0 && seriesKey.length in 1..128 &&
    (sourceSeason == null || sourceSeason in 1..9999) && providerEpisode?.matches(Regex("[1-9][0-9]{0,5}(?:\\.[0-9]{1,2})?")) == true

interface ProviderNavigationGateway {
    suspend fun providers(): List<NavigationProvider>
    suspend fun dispatch(provider: NavigationProvider, request: NavigationContextV1, generation: String): ProviderNavigationTargetV1?
    suspend fun <T> withCurrentProvider(provider: NavigationProvider, block: suspend () -> T): T? =
        if (providers().singleOrNull { it.key == provider.key } == provider) block() else null
}

/** Only the coordinator can mint a launchable target after identity/coordinate/host validation. */
class ValidatedNavigationTarget internal constructor(
    val url: String,
    val provider: NavigationProvider,
    internal val policy: ExtensionProductPolicy,
    val track: ObservationTrack? = null,
)

sealed interface ProviderNavigationResult {
    data class Ready(val target: ValidatedNavigationTarget) : ProviderNavigationResult
    data class Unavailable(val reason: NavigationUnavailableReason) : ProviderNavigationResult
}

fun interface ExternalNavigationLauncher {
    /** Android implementation handles a missing handler as false. */
    suspend fun launch(target: ValidatedNavigationTarget): Boolean
}

class ProviderNavigationCoordinator(
    private val gateway: ProviderNavigationGateway,
    private val preferences: ExtensionProductPolicyRepository,
) {
    suspend fun resolve(coordinate: ProviderCoordinate, kind: NavigationTargetKind): ProviderNavigationResult {
        val policy = preferences.policy.value
        val provider = gateway.providers().singleOrNull { it.key == coordinate.key }
            ?: return unavailable(NavigationUnavailableReason.PROVIDER_UNAVAILABLE)
        val capability = if (kind == NavigationTargetKind.OVERVIEW) NavigationCapability.OVERVIEW_NAVIGATION else NavigationCapability.EPISODE_NAVIGATION
        if (capability !in provider.capabilities || !policy.preferencesFor(provider.key).visibleInProviderField)
            return unavailable(NavigationUnavailableReason.PROVIDER_UNAVAILABLE)
        val tracks: List<ObservationTrack?> = if (kind == NavigationTargetKind.OVERVIEW) listOf(null) else {
            if (!coordinate.isExactEpisode()) return unavailable(NavigationUnavailableReason.MISSING_MAPPING)
            policy.preferencesFor(provider.key, provider.supportedTracks).orderedTracks(provider.supportedTracks)
                .filter { it in coordinate.availableTracks }
                .mapNotNull { value -> ObservationTrack.entries.singleOrNull { it.name == value && it != ObservationTrack.UNKNOWN } }
        }
        if (tracks.isEmpty()) return unavailable(NavigationUnavailableReason.TRACK_UNAVAILABLE)
        for (track in tracks) {
            coroutineContext.ensureActive()
            val request = NavigationContextV1(1, ExtensionId.parse(provider.key.extensionId), ProviderId.parse(provider.key.providerId),
                Instant.now().toString(), kind, "navigation-${UUID.randomUUID()}", coordinate.seriesKey,
                coordinate.routeHint, coordinate.sourceSeason,
                if (kind == NavigationTargetKind.EPISODE) coordinate.providerEpisode else null, track)
            val result = try { gateway.dispatch(provider, request, "product-${policy.generation}-${UUID.randomUUID()}") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return unavailable(NavigationUnavailableReason.INVALID_TARGET) }
            coroutineContext.ensureActive()
            if (preferences.policy.value != policy || gateway.providers().singleOrNull { it.key == provider.key } != provider)
                return unavailable(NavigationUnavailableReason.STALE_RESULT)
            if (result == null) continue
            if (!valid(result, request, provider)) return unavailable(NavigationUnavailableReason.INVALID_TARGET)
            return ProviderNavigationResult.Ready(ValidatedNavigationTarget(result.url, provider, policy, track))
        }
        return unavailable(NavigationUnavailableReason.TRACK_UNAVAILABLE)
    }

    suspend fun launch(target: ValidatedNavigationTarget, launcher: ExternalNavigationLauncher): ProviderNavigationResult {
        return preferences.withCurrentPolicy(target.policy) {
            gateway.withCurrentProvider(target.provider) {
                coroutineContext.ensureActive()
                try {
                    if (launcher.launch(target)) ProviderNavigationResult.Ready(target) else unavailable(NavigationUnavailableReason.LAUNCH_FAILED)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable(NavigationUnavailableReason.LAUNCH_FAILED) }
            } ?: unavailable(NavigationUnavailableReason.STALE_RESULT)
        } ?: unavailable(NavigationUnavailableReason.STALE_RESULT)
    }

    private fun valid(target: ProviderNavigationTargetV1, request: NavigationContextV1, provider: NavigationProvider): Boolean {
        if (target.schemaVersion != 1 || target.extensionId != request.extensionId || target.providerId != request.providerId ||
            target.targetKind != request.targetKind || target.providerSeriesKey != request.providerSeriesKey ||
            target.sourceSeason != request.sourceSeason || target.providerEpisode != request.providerEpisode || target.track != request.track ||
            target.url.length !in 1..2048 || target.url.any { it.code !in 0x21..0x7e } || '\\' in target.url) return false
        val uri = runCatching { URI(target.url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1, 443) &&
            uri.host?.lowercase(Locale.ROOT) in provider.allowedHosts && uri.rawPath.orEmpty().split('/').none { it == "." || it == ".." }
    }

    private fun unavailable(reason: NavigationUnavailableReason) = ProviderNavigationResult.Unavailable(reason)
}

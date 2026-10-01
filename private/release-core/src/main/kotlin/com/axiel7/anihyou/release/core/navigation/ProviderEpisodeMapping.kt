package com.axiel7.anihyou.release.core.navigation

import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Host-validated segment; no implicit season/cour offset or website URL. */
data class ProviderEpisodeSegment(
    val key: ExtensionSelectionKey,
    val mediaId: Int,
    val seriesKey: String,
    val sourceSeason: Int,
    val providerFirst: Int,
    val canonicalFirst: Int,
    val count: Int,
) {
    init {
        require(mediaId > 0 && seriesKey.length in 1..128 && sourceSeason in 1..9999)
        require(providerFirst in 1..9999 && canonicalFirst in 1..9999 && count in 1..9999)
        require(providerFirst + count - 1 <= 9999 && canonicalFirst + count - 1 <= 9999)
    }
    fun providerEpisode(canonical: BigDecimal): BigDecimal? = map(canonical, canonicalFirst, providerFirst)
    fun canonicalEpisode(provider: BigDecimal): BigDecimal? = map(provider, providerFirst, canonicalFirst)
    private fun map(number: BigDecimal, first: Int, destination: Int): BigDecimal? {
        if (number < BigDecimal(first) || number >= BigDecimal(first + count)) return null
        return number - BigDecimal(first) + BigDecimal(destination)
    }
}

object ProviderEpisodeMapper {
    fun coordinate(segments: List<ProviderEpisodeSegment>, key: ExtensionSelectionKey, mediaId: Int,
        number: BigDecimal, availableTracks: Set<String>): ProviderCoordinate? {
        val segment = segments.filter { it.key == key && it.mediaId == mediaId && it.providerEpisode(number) != null }
            .singleOrNull() ?: return null
        return ProviderCoordinate(key, mediaId, number, segment.seriesKey, segment.sourceSeason,
            segment.providerEpisode(number)!!.stripTrailingZeros().toPlainString(), availableTracks)
    }
}

data class ProviderNavigationProductState(
    val providers: List<NavigationProvider> = emptyList(),
    val watchNext: WatchNextState = WatchNextState.Unavailable(NavigationUnavailableReason.NO_ACTIVE_SOURCE),
    val watchTarget: ValidatedNavigationTarget? = null,
    val loading: Boolean = false,
    val failure: NavigationUnavailableReason? = null,
    val mappingProviders: List<NavigationProvider> = emptyList(),
    val activeReleaseSource: ExtensionSelectionKey? = null,
)

interface ProviderNavigationProductRepository {
    fun observe(mediaId: Int, watchedProgress: Int): Flow<ProviderNavigationProductState>
    suspend fun overview(mediaId: Int, key: ExtensionSelectionKey): ProviderNavigationResult
    suspend fun watchNext(mediaId: Int, watchedProgress: Int): ProviderNavigationResult
    suspend fun preferProvider(key: ExtensionSelectionKey)
    suspend fun launch(target: ValidatedNavigationTarget): ProviderNavigationResult
    suspend fun setEpisodeMapping(segment: ProviderEpisodeSegment)
}

object EmptyProviderNavigationProductRepository : ProviderNavigationProductRepository {
    override fun observe(mediaId: Int, watchedProgress: Int) = flowOf(ProviderNavigationProductState())
    override suspend fun overview(mediaId: Int, key: ExtensionSelectionKey) = unavailable()
    override suspend fun watchNext(mediaId: Int, watchedProgress: Int) = unavailable()
    override suspend fun preferProvider(key: ExtensionSelectionKey) = Unit
    override suspend fun launch(target: ValidatedNavigationTarget) = unavailable()
    override suspend fun setEpisodeMapping(segment: ProviderEpisodeSegment) = Unit
    private fun unavailable() = ProviderNavigationResult.Unavailable(NavigationUnavailableReason.NO_PROVIDERS)
}

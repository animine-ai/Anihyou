package com.axiel7.anihyou.feature.mediadetails

import androidx.compose.runtime.Immutable
import com.axiel7.anihyou.core.base.event.UiEvent
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.MediaCharacter
import com.axiel7.anihyou.core.network.fragment.MediaRecommended
import com.axiel7.anihyou.core.network.type.RecommendationRating
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey

@Immutable
interface MediaDetailsEvent : UiEvent {
    fun onUpdateListEntry(newListEntry: BasicMediaListEntry?)
    fun toggleFavorite()
    fun fetchCharactersAndStaff()
    fun fetchRelationsAndRecommendations()
    fun fetchStats()
    fun fetchThreads()
    fun fetchReviews()
    fun fetchActivity()
    fun showVoiceActorsSheet(character: MediaCharacter)
    fun hideVoiceActorSheet()
    fun onVoteClick(recommendedMediaId: Int, recommendationId: Int, rating: RecommendationRating)
    fun addRecommendation(media: MediaRecommended)
    fun changeNotificationAllowance(type: AiringNotificationType, value: Boolean)
    fun writeNotificationAllowanceToDatabase()
    fun openProviderOverview(key: ExtensionSelectionKey)
    fun openWatchNext()
    fun chooseNavigationProvider(key: ExtensionSelectionKey)
    fun saveProviderEpisodeMapping(
        key: ExtensionSelectionKey,
        seriesKey: String,
        providerSeason: Int,
        providerFirstEpisode: Int,
        anilistFirstEpisode: Int,
        episodeCount: Int,
    )
    fun clearEpisodeMappingFeedback()
}

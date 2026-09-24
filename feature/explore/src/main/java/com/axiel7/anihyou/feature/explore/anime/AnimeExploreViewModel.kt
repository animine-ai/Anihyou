package com.axiel7.anihyou.feature.explore.anime

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.base.extensions.indexOfFirstOrNull
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.media.currentAnimeSeason
import com.axiel7.anihyou.core.model.media.nextAnimeSeason
import com.axiel7.anihyou.core.network.fragment.BasicMediaDetails
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.type.MediaSort
import com.axiel7.anihyou.core.network.type.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

class AnimeExploreViewModel(
    private val mediaRepository: MediaRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : UiStateViewModel<AnimeExploreUiState>(), AnimeExploreEvent {

    private val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
    private val myUserId = defaultPreferencesRepository.userId.filterNotNull()

    override val initialState =
        AnimeExploreUiState(
            infos = mutableStateListOf(
                AnimeDiscoverInfo.AIRING,
                AnimeDiscoverInfo.THIS_SEASON,
                AnimeDiscoverInfo.TRENDING_ANIME
            ),
            nowAnimeSeason = now.currentAnimeSeason(),
            nextAnimeSeason = now.nextAnimeSeason(),
        )

    override fun addNextInfo() {
        mutableUiState.value.run {
            if (infos.size < AnimeDiscoverInfo.entries.size) {
                infos.add(AnimeDiscoverInfo.entries[infos.size])
            }
        }
    }

    override fun fetchAiringAnime() {
        if (mutableUiState.value.airingAnime.isEmpty()) {
            mediaRepository.getAiringAnimesPage(
                airingAtGreater = clock.instant().epochSecond,
                isAdult = uiState.value.displayAdult,
                page = 1
            ).onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        it.airingAnime.addAll(result.list)
                    }
                    it.copy(
                        isLoadingAiring = result is PagedResult.Loading,
                        error = (result as? PagedResult.Error)?.message
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    override fun fetchAiringAnimeOnMyList() {
        if (mutableUiState.value.airingAnimeOnMyList.isEmpty()) {
            mediaRepository.getAiringAnimeOnMyListPage(page = 1)
                .onEach { result ->
                    mutableUiState.update {
                        if (result is PagedResult.Success) {
                            it.airingAnimeOnMyList.addAll(result.list)
                        }
                        it.copy(
                            isLoadingAiring = result is PagedResult.Loading,
                            error = (result as? PagedResult.Error)?.message
                        )
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    override fun fetchThisSeasonAnime() {
        if (mutableUiState.value.thisSeasonAnime.isEmpty()) {
            mediaRepository.getSeasonalAnimePage(
                animeSeason = uiState.value.nowAnimeSeason,
                isAdult = uiState.value.isAdult,
                page = 1
            ).onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        it.thisSeasonAnime.addAll(result.list)
                    }
                    it.copy(
                        isLoadingThisSeason = result is PagedResult.Loading,
                        error = (result as? PagedResult.Error)?.message
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    override fun fetchTrendingAnime() {
        if (mutableUiState.value.trendingAnime.isEmpty()) {
            mediaRepository.getMediaSortedPage(
                mediaType = MediaType.ANIME,
                sort = listOf(MediaSort.TRENDING_DESC),
                isAdult = uiState.value.isAdult,
                page = 1
            ).onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        it.trendingAnime.addAll(result.list)
                    }
                    it.copy(
                        isLoadingTrendingAnime = result is PagedResult.Loading,
                        error = (result as? PagedResult.Error)?.message
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    override fun fetchNextSeasonAnime() {
        if (mutableUiState.value.nextSeasonAnime.isEmpty()) {
            mediaRepository.getSeasonalAnimePage(
                animeSeason = uiState.value.nextAnimeSeason,
                isAdult = uiState.value.isAdult,
                page = 1
            ).onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        it.nextSeasonAnime.addAll(result.list)
                    }
                    it.copy(
                        isLoadingNextSeason = result is PagedResult.Loading,
                        error = (result as? PagedResult.Error)?.message
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    override fun fetchPopularAnime() {
        if (mutableUiState.value.popularAnime.isEmpty()) {
            mediaRepository.getMediaSortedPage(
                mediaType = MediaType.ANIME,
                sort = listOf(MediaSort.POPULARITY_DESC),
                isAdult = uiState.value.isAdult,
                page = 1
            ).onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        it.popularAnime.addAll(result.list)
                    }
                    it.copy(
                        isLoadingPopularAnime = result is PagedResult.Loading,
                        error = (result as? PagedResult.Error)?.message
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    override fun fetchNewlyAnime() {
        if (mutableUiState.value.newlyAnime.isEmpty()) {
            mediaRepository.getMediaSortedPage(
                mediaType = MediaType.ANIME,
                sort = listOf(MediaSort.ID_DESC),
                isAdult = uiState.value.isAdult,
                page = 1
            ).onEach { result ->
                mutableUiState.update {
                    if (result is PagedResult.Success) {
                        it.newlyAnime.addAll(result.list)
                    }
                    it.copy(
                        isLoadingNewlyAnime = result is PagedResult.Loading,
                        error = (result as? PagedResult.Error)?.message
                    )
                }
            }.launchIn(viewModelScope)
        }
    }

    override fun refresh() {
        mutableUiState.update { it.copy(isLoading = true) }
        mutableUiState.value.run {
            airingAnime.clear()
            airingAnimeOnMyList.clear()
            thisSeasonAnime.clear()
            trendingAnime.clear()
            nextSeasonAnime.clear()
            newlyAnime.clear()
            if (airingOnMyList == true) fetchAiringAnimeOnMyList()
            else fetchAiringAnime()
            fetchThisSeasonAnime()
            fetchTrendingAnime()
        }
        viewModelScope.launch {
            delay(1000.milliseconds)
            mutableUiState.update { it.copy(isLoading = false) }
        }
    }

    override fun selectItem(
        details: BasicMediaDetails?,
        listEntry: BasicMediaListEntry?,
    ) {
        mutableUiState.update {
            it.copy(
                selectedMediaDetails = details,
                selectedMediaListEntry = listEntry,
            )
        }
    }

    override fun onUpdateListEntry(
        newListEntry: BasicMediaListEntry?
    ) {
        val selectedMediaId = uiState.value.selectedMediaDetails?.id ?: return
        mutableUiState.update { it.copy(selectedMediaListEntry = newListEntry) }

        uiState.value.allLists.forEach { list ->
            list.indexOfFirstOrNull { it.id == selectedMediaId }?.let { index ->
                list[index] = list[index].copy(
                    mediaListEntry = newListEntry?.let {
                        ExploreMedia.MediaListEntry(
                            __typename = "ExploreMedia.MediaListEntry",
                            id = it.id,
                            mediaId = it.mediaId,
                            basicMediaListEntry = it,
                        )
                    }
                )
            }
        }
    }

    init {
        mutableUiState
            .map { state ->
                state.allLists.flatten().mapTo(mutableSetOf()) { it.id }
            }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                myUserId.flatMapLatest { accountId ->
                    releasePresentationRepository.observeForMedia(accountId.toLong(), ids)
                }
            }
            .onEach { rows ->
                mutableUiState.update { it.copy(releaseByMediaId = rows) }
            }
            .launchIn(viewModelScope)

        myUserId
            .flatMapLatest { accountId ->
                val start = LocalDate.ofInstant(
                    clock.instant(),
                    ReleaseSourceTimePolicy.ANI_WORLD_ZONE,
                )
                releasePresentationRepository.observeCalendar(
                    accountId = accountId.toLong(),
                    range = start..start.plusDays(14),
                )
            }
            .onEach { rows ->
                mutableUiState.update {
                    it.copy(
                        providerAiringRows = rows.filter(ReleaseUiCalendarItem::isAuthoritative),
                    )
                }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.airingOnMyList
            .onEach { value ->
                mutableUiState.update { it.copy(airingOnMyList = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.displayAdult
            .onEach { value ->
                mutableUiState.update { it.copy(displayAdult = value ?: false) }
            }
            .launchIn(viewModelScope)
    }
}

package com.axiel7.anihyou.feature.home.current

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.base.extensions.indexOfFirstOrNull
import com.axiel7.anihyou.core.common.utils.NumberUtils.isNullOrZero
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.CurrentListType
import com.axiel7.anihyou.core.model.media.currentAnimeSeason
import com.axiel7.anihyou.core.model.media.duration
import com.axiel7.anihyou.core.model.media.episodesBehind
import com.axiel7.anihyou.core.model.media.isBehind
import com.axiel7.anihyou.core.model.media.nextAnimeSeason
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaListSort
import com.axiel7.anihyou.core.network.type.MediaListStatus
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentViewModel(
    private val mediaListRepository: MediaListRepository,
    defaultPreferencesRepository: DefaultPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : UiStateViewModel<CurrentUiState>(), CurrentEvent {

    override val initialState = CurrentUiState()

    private val myUserId = defaultPreferencesRepository.userId.filterNotNull()
    private val releaseMediaIds = MutableStateFlow<Set<Int>>(emptySet())

    private fun Map<Int, List<ReleaseUiPresentation>>.authoritativeFor(mediaId: Int): ReleaseUiPresentation? =
        this[mediaId].orEmpty().firstOrNull { it.isAuthoritative }

    private fun isBehindForCurrent(
        entry: CommonMediaListEntry,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): Boolean {
        val release = presentations.authoritativeFor(entry.mediaId)
        return if (release?.isAuthoritative == true) {
            release.pendingCount > 0
        } else {
            entry.isBehind()
        }
    }

    private fun providerAwareEpisodesBehind(
        entry: CommonMediaListEntry,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): Int {
        val release = presentations.authoritativeFor(entry.mediaId)
        return if (release?.isAuthoritative == true) {
            release.pendingCount
        } else {
            entry.episodesBehind()
        }
    }

    private fun airingSortValue(
        entry: CommonMediaListEntry,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): Long? {
        val release = presentations.authoritativeFor(entry.mediaId)
        return if (release?.isAuthoritative == true) {
            release.nextForecastAt?.toEpochMilli()
        } else {
            entry.media?.nextAiringEpisode?.timeUntilAiring?.toLong()
        }
    }

    private fun reclassifyCurrentLists(
        state: CurrentUiState,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): CurrentUiState {
        val currentEntries = (state.airingList + state.behindList).distinctBy { it.mediaId }
        val airing = currentEntries
            .filterNot { isBehindForCurrent(it, presentations) }
            .sortedWith(compareBy(nullsLast()) { airingSortValue(it, presentations) })
        val behind = currentEntries
            .filter { isBehindForCurrent(it, presentations) }
            .sortedWith(
                compareByDescending<CommonMediaListEntry> { it.basicMediaListEntry.priority }
                    .thenByDescending { providerAwareEpisodesBehind(it, presentations) }
            )
        state.airingList.clear()
        state.airingList.addAll(airing)
        state.behindList.clear()
        state.behindList.addAll(behind)
        return state.copy(releaseByMediaId = presentations)
    }

    override fun refresh() {
        mutableUiState.update { it.copy(fetchFromNetwork = true) }
    }

    override fun onClickPlusOne(
        increment: Int,
        item: CommonMediaListEntry,
        type: CurrentListType
    ) {
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(
                    selectedItem = item,
                    selectedType = type,
                    isLoadingPlusOne = true
                )
            }
            mediaListRepository.incrementProgress(
                entry = item.basicMediaListEntry,
                increment = increment,
                total = item.duration()
            ).collectLatest { result ->
                mutableUiState.update {
                    if (result is DataResult.Success && result.data != null) {
                        onUpdateListEntry(result.data!!.basicMediaListEntry, type)
                    }
                    result.toUiState().copy(isLoadingPlusOne = result is DataResult.Loading)
                }
            }
        }
    }

    override fun blockPlusOne() {
        mutableUiState.update { it.copy(isLoadingPlusOne = true) }
    }

    override fun onUpdateListEntry(
        newListEntry: BasicMediaListEntry?,
        type: CurrentListType
    ) {
        mutableUiState.value.run {
            selectedItem?.let { selectedItem ->
                if (selectedItem.basicMediaListEntry != newListEntry) {
                    val list = getListFromType(type)
                    if (newListEntry != null) {
                        list.indexOfFirstOrNull { it.mediaId == selectedItem.mediaId }
                            ?.let { index ->
                                val oldValue = list[index]
                                val updatedValue = oldValue.copy(basicMediaListEntry = newListEntry)
                                val statusChanged =
                                    newListEntry.status != oldValue.basicMediaListEntry.status
                                if (statusChanged) {
                                    list.removeAt(index)
                                    if (newListEntry.status == MediaListStatus.COMPLETED
                                        && newListEntry.score.isNullOrZero()
                                    ) {
                                        toggleSetScoreDialog(true)
                                    }
                                    if (
                                        type == CurrentListType.AIRING ||
                                        type == CurrentListType.BEHIND
                                    ) {
                                        val target = if (
                                            isBehindForCurrent(
                                                updatedValue,
                                                mutableUiState.value.releaseByMediaId,
                                            )
                                        ) {
                                            behindList
                                        } else {
                                            airingList
                                        }
                                        target.removeAll { it.mediaId == updatedValue.mediaId }
                                        if (
                                            newListEntry.status == MediaListStatus.CURRENT ||
                                            newListEntry.status == MediaListStatus.REPEATING
                                        ) {
                                            target.add(updatedValue)
                                        }
                                    }
                                } else {
                                    list[index] = updatedValue
                                    if (
                                        type == CurrentListType.BEHIND &&
                                        !isBehindForCurrent(
                                            updatedValue,
                                            mutableUiState.value.releaseByMediaId,
                                        )
                                    ) {
                                        airingList.add(updatedValue)
                                        list.removeAt(index)
                                    }
                                }
                            }
                    } else {
                        list.remove(selectedItem)
                    }
                }
            }
        }
    }

    override fun selectItem(item: CommonMediaListEntry, type: CurrentListType) {
        mutableUiState.update { it.copy(selectedItem = item, selectedType = type) }
    }

    override fun toggleSetScoreDialog(open: Boolean) {
        mutableUiState.update { it.copy(openSetScoreDialog = open) }
    }

    override fun setScore(score: Double?) {
        viewModelScope.launch {
            mutableUiState.value.selectedItem?.let { item ->
                mediaListRepository.updateEntry(
                    oldEntry = item.basicMediaListEntry,
                    mediaId = item.mediaId,
                    score = score,
                ).collectLatest {
                    if (it is DataResult.Success) toggleSetScoreDialog(false)
                }
            }
        }
    }

    private fun findEntryAndListType(entry: BasicMediaListEntry): Pair<CommonMediaListEntry, CurrentListType>? {
        val predicate: (CommonMediaListEntry) -> Boolean = { it.mediaId == entry.mediaId }
        mutableUiState.value.run {
            return airingList.find(predicate)?.let { it to CurrentListType.AIRING }
                ?: behindList.find(predicate)?.let { it to CurrentListType.BEHIND }
                ?: animeList.find(predicate)?.let { it to CurrentListType.ANIME }
                ?: mangaList.find(predicate)?.let { it to CurrentListType.MANGA }
                ?: nextSeasonAnimeList.find(predicate)?.let { it to CurrentListType.NEXT_SEASON }
        }
    }

    init {
        releaseMediaIds
            .combine(myUserId) { ids, accountId -> accountId.toLong() to ids }
            .flatMapLatest { (accountId, ids) ->
                releasePresentationRepository.observeForMedia(accountId, ids)
            }
            .onEach { presentations ->
                mutableUiState.update { state ->
                    reclassifyCurrentLists(state, presentations)
                }
            }
            .launchIn(viewModelScope)

        mutableUiState
            .map { state ->
                (state.airingList + state.behindList + state.animeList + state.mangaList + state.nextSeasonAnimeList)
                    .mapTo(mutableSetOf()) { it.mediaId }
            }
            .distinctUntilChanged()
            .onEach { ids -> releaseMediaIds.value = ids }
            .launchIn(viewModelScope)

        // anime
        mutableUiState
            .distinctUntilChanged { _, new ->
                !new.fetchFromNetwork
            }
            .flatMapLatest { uiState ->
                mediaListRepository.getUserMediaList(
                    userId = myUserId.first(),
                    mediaType = MediaType.ANIME,
                    statusIn = listOf(MediaListStatus.CURRENT, MediaListStatus.REPEATING),
                    sort = listOf(MediaListSort.UPDATED_TIME_DESC),
                    scoreFormat = defaultPreferencesRepository.scoreFormat.first() ?: ScoreFormat.POINT_10_DECIMAL,
                    fetchFromNetwork = uiState.fetchFromNetwork,
                    page = null,
                    perPage = null,
                )
            }
            .onEach { result ->
                mutableUiState.update { uiState ->
                    when (result) {
                        is PagedResult.Success -> {
                            val currentEntries = result.list.filter {
                                it.media?.status == MediaStatus.RELEASING
                            }
                            val airingList = currentEntries
                                .filterNot {
                                    isBehindForCurrent(it, uiState.releaseByMediaId)
                                }
                                .sortedWith(
                                    compareBy(nullsLast()) {
                                        airingSortValue(it, uiState.releaseByMediaId)
                                    }
                                )
                            val behindList = currentEntries
                                .filter {
                                    isBehindForCurrent(it, uiState.releaseByMediaId)
                                }
                                .sortedWith(
                                    compareByDescending<CommonMediaListEntry> {
                                        it.basicMediaListEntry.priority
                                    }.thenByDescending {
                                        providerAwareEpisodesBehind(it, uiState.releaseByMediaId)
                                    }
                                )
                            val animeList = result.list
                                .filter { it.media?.status != MediaStatus.RELEASING }
                            uiState.airingList.clear()
                            uiState.airingList.addAll(airingList)
                            uiState.behindList.clear()
                            uiState.behindList.addAll(behindList)
                            uiState.animeList.clear()
                            uiState.animeList.addAll(animeList)
                            uiState.copy(
                                isLoading = false
                            )
                        }

                        is PagedResult.Loading -> {
                            uiState.copy(isLoading = true)
                        }

                        is PagedResult.Error -> {
                            uiState.copy(
                                error = result.message,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
            .launchIn(viewModelScope)

        // manga
        mutableUiState
            .distinctUntilChanged { _, new ->
                !new.fetchFromNetwork
            }
            .flatMapLatest { uiState ->
                mediaListRepository.getUserMediaList(
                    userId = myUserId.first(),
                    mediaType = MediaType.MANGA,
                    statusIn = listOf(MediaListStatus.CURRENT, MediaListStatus.REPEATING),
                    sort = listOf(MediaListSort.UPDATED_TIME_DESC),
                    scoreFormat = defaultPreferencesRepository.scoreFormat.first() ?: ScoreFormat.POINT_10_DECIMAL,
                    fetchFromNetwork = uiState.fetchFromNetwork,
                    page = 1
                )
            }
            .onEach { result ->
                mutableUiState.update { uiState ->
                    when (result) {
                        is PagedResult.Success -> {
                            uiState.mangaList.clear()
                            uiState.mangaList.addAll(result.list)
                            uiState.copy(
                                fetchFromNetwork = false,
                                isLoading = false
                            )
                        }

                        is PagedResult.Loading -> {
                            uiState.copy(isLoading = true)
                        }

                        is PagedResult.Error -> {
                            uiState.copy(
                                error = result.message,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
            .launchIn(viewModelScope)


        defaultPreferencesRepository.showLowPriority
            .distinctUntilChanged()
            .onEach { value ->
                mutableUiState.update { it.copy(showLowPriority = value) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorLowPriority
            .onEach { color ->
                mutableUiState.update { it.copy(lowPriorityColor = Color(color)) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorMediumPriority
            .onEach { color ->
                mutableUiState.update { it.copy(mediumPriorityColor = Color(color)) }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.colorHighPriority
            .onEach { color ->
                mutableUiState.update { it.copy(highPriorityColor = Color(color)) }
            }
            .launchIn(viewModelScope)


        defaultPreferencesRepository.scoreSteps
            .onEach { value ->
                mutableUiState.update { it.copy(scoreStep = value) }
            }
            .launchIn(viewModelScope)

        // next season on list
        mutableUiState
            .distinctUntilChanged { _, new ->
                !new.fetchFromNetwork
            }
            .flatMapLatest { uiState ->
                val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())
                mediaListRepository.getMySeasonalAnime(
                    season = now.currentAnimeSeason(),
                    fetchFromNetwork = uiState.fetchFromNetwork,
                    page = 1,
                ).combine(
                    mediaListRepository.getMySeasonalAnime(
                        season = now.nextAnimeSeason(),
                        fetchFromNetwork = uiState.fetchFromNetwork,
                        page = 1,
                    )
                ) { first, second ->
                    when (first) {
                        is PagedResult.Success if second is PagedResult.Success -> {
                            PagedResult.Success(
                                list = first.list + second.list,
                                currentPage = first.currentPage,
                                hasNextPage = first.hasNextPage,
                            )
                        }

                        !is PagedResult.Success -> first
                        else -> second
                    }
                }
            }
            .onEach { result ->
                mutableUiState.update { uiState ->
                    when (result) {
                        is PagedResult.Success -> {
                            uiState.nextSeasonAnimeList.clear()
                            uiState.nextSeasonAnimeList.addAll(result.list)
                            uiState.copy(
                                fetchFromNetwork = false,
                                isLoading = false
                            )
                        }

                        is PagedResult.Loading -> {
                            uiState.copy(isLoading = true)
                        }

                        is PagedResult.Error -> {
                            uiState.copy(
                                error = result.message,
                                isLoading = false,
                            )
                        }
                    }
                }
            }
            .launchIn(viewModelScope)

        mediaListRepository
            .lastUpdatedEntry
            .filterNotNull()
            .onEach { entry ->
                findEntryAndListType(entry)?.let {
                    val mediaEntry = it.first
                    val listType = it.second
                    selectItem(mediaEntry, listType)
                    onUpdateListEntry(entry, listType)
                }
            }
            .launchIn(viewModelScope)
    }
}
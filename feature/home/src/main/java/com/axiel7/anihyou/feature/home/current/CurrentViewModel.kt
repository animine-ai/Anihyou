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
import com.axiel7.anihyou.release.core.api.ReleaseUiSelection
import com.axiel7.anihyou.release.core.api.pendingFor
import com.axiel7.anihyou.release.core.log.AppLog
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentViewModel(
    private val mediaListRepository: MediaListRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : UiStateViewModel<CurrentUiState>(), CurrentEvent {

    override val initialState = CurrentUiState()

    private val myUserId = defaultPreferencesRepository.userId.filterNotNull()
    private val releaseMediaIds = MutableStateFlow<Set<Int>>(emptySet())

    /**
     * The whole CURRENT/REPEATING list of one media type. AniList answers a list query with one page only (25 entries
     * unless a size is given), and the list is sorted by last update, so the entries a user is behind on (the ones not
     * touched for a while) were exactly the ones cut off. Pages are read until AniList reports no further page.
     */
    private fun allCurrentPages(
        mediaType: MediaType,
        fetchFromNetwork: Boolean,
    ): Flow<PagedResult<CommonMediaListEntry>> = flow {
        val userId = myUserId.first()
        val scoreFormat = defaultPreferencesRepository.scoreFormat.first() ?: ScoreFormat.POINT_10_DECIMAL
        fun page(number: Int) = mediaListRepository.getUserMediaList(
            userId = userId,
            mediaType = mediaType,
            statusIn = listOf(MediaListStatus.CURRENT, MediaListStatus.REPEATING),
            sort = listOf(MediaListSort.UPDATED_TIME_DESC),
            scoreFormat = scoreFormat,
            fetchFromNetwork = fetchFromNetwork,
            page = number,
            perPage = CURRENT_PAGE_SIZE,
        )
        emit(PagedResult.Loading)
        // The first page stays a live source (every answer of it is followed, as before); the pages behind it are read once.
        page(1).collect { first ->
            when (first) {
                PagedResult.Loading -> Unit
                is PagedResult.Error -> {
                    AppLog.w("current") { "list $mediaType page=1 failed: ${first.message}" }
                    emit(PagedResult.Error(first.message))
                }
                is PagedResult.Success -> {
                    val all = first.list.toMutableList()
                    var number = 1
                    var hasNext = first.hasNextPage
                    var failure: String? = null
                    AppLog.d("current") {
                        "list $mediaType page=1 got=${first.list.size} hasNext=$hasNext network=$fetchFromNetwork"
                    }
                    while (hasNext && number < MAX_CURRENT_PAGES) {
                        number++
                        when (val next = page(number).first { it !is PagedResult.Loading }) {
                            is PagedResult.Success -> {
                                all += next.list
                                hasNext = next.hasNextPage
                                AppLog.d("current") { "list $mediaType page=$number got=${next.list.size} total=${all.size} hasNext=$hasNext" }
                            }
                            is PagedResult.Error -> {
                                failure = next.message
                                hasNext = false
                            }
                            PagedResult.Loading -> hasNext = false
                        }
                    }
                    if (failure != null) {
                        AppLog.w("current") { "list $mediaType page=$number failed: $failure; loaded so far=${all.size}" }
                        emit(PagedResult.Error(failure))
                    } else {
                        AppLog.i("current") { "list $mediaType complete entries=${all.size} pages=$number" }
                        emit(PagedResult.Success(all.distinctBy { it.mediaId }, currentPage = number, hasNextPage = false))
                    }
                }
            }
        }
    }

    private fun Map<Int, List<ReleaseUiPresentation>>.authoritativeFor(mediaId: Int): ReleaseUiPresentation? =
        ReleaseUiSelection.effective(this[mediaId].orEmpty())

    /**
     * Behind always means confirmed source installments the user has not watched, counted from the progress the
     * entry has right now. A stored count would stay wrong after a progress change until the next source refresh.
     */
    private fun isBehindForCurrent(
        entry: CommonMediaListEntry,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): Boolean = providerAwareEpisodesBehind(entry, presentations) > 0

    private fun providerAwareEpisodesBehind(
        entry: CommonMediaListEntry,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): Int {
        val release = presentations.authoritativeFor(entry.mediaId)
        return if (release != null) {
            release.pendingFor(entry.basicMediaListEntry.progress)
        } else {
            entry.episodesBehind()
        }
    }

    /** One summary line per classification and one line per entry the source decided differently from AniList. */
    private fun logClassification(
        reason: String,
        entries: List<CommonMediaListEntry>,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ) {
        if (!AppLog.enabled) return
        val behind = entries.filter { isBehindForCurrent(it, presentations) }
        AppLog.i("current") {
            "classify $reason releasing=${entries.size} behind=${behind.size} airing=${entries.size - behind.size} " +
                "withSource=${entries.count { presentations.authoritativeFor(it.mediaId) != null }}"
        }
        entries.forEach { entry ->
            val release = presentations.authoritativeFor(entry.mediaId)
            val progress = entry.basicMediaListEntry.progress
            val anilist = entry.episodesBehind()
            if (release != null) {
                val source = release.pendingFor(progress)
                if ((anilist > 0) != (source > 0)) {
                    AppLog.i("current") {
                        "source decides media=${entry.mediaId} progress=$progress anilistBehind=$anilist sourcePending=$source " +
                            "confirmedThrough=${release.confirmedThroughEpisode} -> ${if (source > 0) "behind" else "not behind"}"
                    }
                }
            } else if (anilist > 0) {
                AppLog.d("current") { "anilist decides media=${entry.mediaId} progress=$progress behind=$anilist next=${entry.media?.nextAiringEpisode?.episode}" }
            }
        }
    }

    private fun airingSortValue(
        entry: CommonMediaListEntry,
        presentations: Map<Int, List<ReleaseUiPresentation>>,
    ): Long? {
        val release = presentations.authoritativeFor(entry.mediaId)
        return if (release != null) {
            release.nextForecastAt?.let { Duration.between(clock.instant(), it).toMillis() }
        } else {
            entry.media?.nextAiringEpisode?.timeUntilAiring?.toLong()?.times(1_000L)
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
        logClassification("release update", currentEntries, presentations)
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
                                        airingList.removeAll { it.mediaId == updatedValue.mediaId }
                                        airingList.add(updatedValue)
                                        list.removeAt(index)
                                    } else if (
                                        type == CurrentListType.AIRING &&
                                        isBehindForCurrent(
                                            updatedValue,
                                            mutableUiState.value.releaseByMediaId,
                                        )
                                    ) {
                                        // A lowered progress (undo, correction in the details) puts the entry behind again.
                                        behindList.removeAll { it.mediaId == updatedValue.mediaId }
                                        behindList.add(updatedValue)
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
        refreshReleaseMediaIds()
    }

    /** SnapshotStateList mutations can leave CurrentUiState equal and suppress StateFlow emissions. */
    private fun refreshReleaseMediaIds() {
        val state = mutableUiState.value
        releaseMediaIds.value =
            (state.airingList + state.behindList + state.animeList + state.mangaList + state.nextSeasonAnimeList)
                .mapTo(mutableSetOf()) { it.mediaId }
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

        // anime
        mutableUiState
            .distinctUntilChanged { _, new ->
                !new.fetchFromNetwork
            }
            .flatMapLatest { uiState ->
                allCurrentPages(MediaType.ANIME, uiState.fetchFromNetwork)
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
                            AppLog.i("current") {
                                "anime list loaded total=${result.list.size} releasing=${currentEntries.size} other=${animeList.size}"
                            }
                            if (AppLog.enabled) {
                                result.list.take(60).forEach { entry ->
                                    AppLog.d("current") {
                                        "entry media=${entry.mediaId} listStatus=${entry.basicMediaListEntry.status} mediaStatus=${entry.media?.status} " +
                                            "progress=${entry.basicMediaListEntry.progress} nextEp=${entry.media?.nextAiringEpisode?.episode} " +
                                            "in=${entry.media?.nextAiringEpisode?.timeUntilAiring}s episodes=${entry.media?.basicMediaDetails?.episodes}"
                                    }
                                }
                            }
                            logClassification("list load", currentEntries, uiState.releaseByMediaId)
                            uiState.airingList.clear()
                            uiState.airingList.addAll(airingList)
                            uiState.behindList.clear()
                            uiState.behindList.addAll(behindList)
                            uiState.animeList.clear()
                            uiState.animeList.addAll(animeList)
                            // A finished refresh ends the network request. Every list watches this flag and restarts on every
                            // state change while it is on, so a list that never switched it off (and a failing sibling that
                            // never could) kept all of them restarting each other: 48 requests in 30 seconds, then HTTP 429.
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
                                fetchFromNetwork = false,
                                isLoading = false,
                            )
                        }
                    }
                }
                refreshReleaseMediaIds()
            }
            .launchIn(viewModelScope)

        // manga
        mutableUiState
            .distinctUntilChanged { _, new ->
                !new.fetchFromNetwork
            }
            .flatMapLatest { uiState ->
                allCurrentPages(MediaType.MANGA, uiState.fetchFromNetwork)
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
                                fetchFromNetwork = false,
                                isLoading = false,
                            )
                        }
                    }
                }
                refreshReleaseMediaIds()
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
            .filter { !it.isLoading }
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
                                fetchFromNetwork = false,
                                isLoading = false,
                            )
                        }
                    }
                }
                refreshReleaseMediaIds()
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

    private companion object {
        /** AniList's maximum page size. */
        const val CURRENT_PAGE_SIZE = 50
        /** 50 pages of 50 entries; a bound against a runaway "next page" flag. */
        const val MAX_CURRENT_PAGES = 50
    }
}

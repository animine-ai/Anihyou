package com.axiel7.anihyou.feature.calendar

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.utils.DateUtils.toTimestamp
import com.axiel7.anihyou.core.common.viewmodel.PagedUiStateViewModel
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val mediaRepository: MediaRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : PagedUiStateViewModel<CalendarUiState>(), CalendarEvent {

    override val initialState = CalendarUiState(day = nowLocalDateTime())

    private fun nowLocalDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())

    val onMyList = defaultPreferencesRepository.calendarOnMyList
    private val myUserId = defaultPreferencesRepository.userId.filterNotNull()
    private val displayAdult = defaultPreferencesRepository.displayAdult

    fun onMyListChanged(value: Boolean?) = viewModelScope.launch {
        defaultPreferencesRepository.setCalendarOnMyList(value)
    }

    override fun onUpdateListEntry(viewListEntry: BasicMediaListEntry?) {
        mutableUiState.value.run {
            selectedItem?.let { selectedItem ->
                weeklyAnime.forEach { (date, list) ->
                    val index = list.indexOf(selectedItem)
                    if (index != -1) {
                        val updatedList = list.toMutableList()
                        updatedList[index] = selectedItem.copy(
                            mediaListEntry = viewListEntry?.let {
                                ExploreMedia.MediaListEntry(
                                    __typename = "ExploreMedia.MediaListEntry",
                                    id = viewListEntry.id,
                                    mediaId = viewListEntry.mediaId,
                                    basicMediaListEntry = viewListEntry
                                )
                            }
                        )
                        val updatedMap = weeklyAnime.toMutableMap()
                        updatedMap[date] = updatedList
                        mutableUiState.update { it.copy(weeklyAnime = updatedMap) }
                    }
                }
            }
        }
    }

    override fun selectItem(value: ExploreMedia?) {
        mutableUiState.update {
            it.copy(selectedItem = value)
        }
    }

    override fun onLoadMore() {
        if (uiState.value.isLoading) return
        if (uiState.value.hasNextPage) {
            mutableUiState.update { it.copy(page = it.page + 1, isLoading = true) }
        } else {
            nextDay()
        }
    }

    override fun nextDay() {
        mutableUiState.update {
            it.copy(
                day = uiState.value.day.plusDays(1),
                page = 1,
                hasNextPage = true,
                isLoading = true,
            )
        }
    }

    override fun refresh() {
        mutableUiState.update {
            it.copy(
                fetchFromNetwork = true,
                day = nowLocalDateTime(),
                weeklyAnime = mutableMapOf(),
                page = 1,
                hasNextPage = true,
                isLoading = true,
            )
        }
    }

    override fun refreshDay(date: LocalDate) {
        val start = date.atStartOfDay().toTimestamp(isEndOfDay = false)
        val end = date.atStartOfDay().toTimestamp(isEndOfDay = true)
        viewModelScope.launch {
            val animes = mutableListOf<ExploreMedia>()
            var currentPage = 1
            var hasNextPage = true
            var fetchFailed = false

            mutableUiState.update {
                it.copy(isLoading = true)
            }

            while (hasNextPage) {
                mediaRepository.getAiringAnimesPage(
                    airingAtGreater = start,
                    airingAtLesser = end,
                    onMyList = mutableUiState.value.onMyList,
                    isAdult = displayAdult.first() == true,
                    page = currentPage,
                    perPage = 50,
                    fetchFromNetwork = true,
                ).collect { result ->
                    if (result is PagedResult.Success) {
                        animes.addAll(result.list)
                        hasNextPage = result.hasNextPage
                        currentPage++
                    } else if (result is PagedResult.Error) {
                        fetchFailed = true
                        hasNextPage = false
                        mutableUiState.update {
                            result.toUiState(loadingWhen = it.page == 1)
                        }
                    }
                }
                if (fetchFailed) return@launch
            }

            mutableUiState.update { state ->
                val updatedMap = state.weeklyAnime.toMutableMap()
                if (animes.isNotEmpty()) {
                    updatedMap[date] = animes
                } else {
                    updatedMap.remove(date)
                }
                state.copy(
                    weeklyAnime = updatedMap,
                    providerOnlyByDate = state.releaseCalendarRows.providerOnlyByDate(
                        knownMediaIds = updatedMap.values
                            .asSequence()
                            .flatten()
                            .map { it.id }
                            .toSet(),
                        fallbackDate = state.day.toLocalDate(),
                    ),
                    isLoading = false,
                )
            }
        }
    }

    init {
        mutableUiState
            .map { it.day.toLocalDate() }
            .distinctUntilChanged()
            .flatMapLatest { date ->
                myUserId.flatMapLatest { accountId ->
                    releasePresentationRepository.observeCalendar(
                        accountId = accountId.toLong(),
                        range = date..date.plusDays(14),
                    )
                }
            }
            .onEach { rows ->
                mutableUiState.update { state ->
                    val authoritativeRows = rows.filter { it.isAuthoritative }
                    val byMedia = authoritativeRows
                        .mapNotNull { it.mediaId }
                        .distinct()
                        .associateWith { mediaId ->
                            authoritativeRows
                                .filter { it.mediaId == mediaId }
                                .sortedForPresentation()
                        }
                    state.copy(
                        releaseCalendarRows = rows,
                        providerRowsByDate = authoritativeRows.providerRowsByDate(
                            fallbackDate = state.day.toLocalDate(),
                        ),
                        releaseByMediaId = byMedia,
                        providerOnlyByDate = rows.providerOnlyByDate(
                            knownMediaIds = state.weeklyAnime.values
                                .asSequence()
                                .flatten()
                                .map { it.id }
                                .toSet(),
                            fallbackDate = state.day.toLocalDate(),
                        ),
                    )
                }
            }
            .launchIn(viewModelScope)

        onMyList.onEach { onMyListVal ->
            if (mutableUiState.value.onMyList != onMyListVal) {
                mutableUiState.update {
                    it.copy(
                        onMyList = onMyListVal,
                        weeklyAnime = mutableMapOf(),
                        day = nowLocalDateTime(),
                        page = 1,
                        hasNextPage = true,
                        isLoading = true,
                    )
                }
            }
        }.launchIn(viewModelScope)

        mutableUiState
            .filter { it.hasNextPage }
            .combine(displayAdult, ::Pair)
            .distinctUntilChanged { (oldState, oldAdult), (newState, newAdult) ->
                oldState.page == newState.page &&
                        oldState.day == newState.day &&
                        oldState.onMyList == newState.onMyList &&
                        oldAdult == newAdult
            }
            .flatMapLatest { (uiState, displayAdult) ->
                val start = uiState.day.toTimestamp(isEndOfDay = false)
                val end = uiState.day.toTimestamp(isEndOfDay = true)
                mediaRepository.getAiringAnimesPage(
                    airingAtGreater = start,
                    airingAtLesser = end,
                    onMyList = onMyList.first(),
                    isAdult = displayAdult == true,
                    page = uiState.page,
                    perPage = 50,
                    fetchFromNetwork = uiState.fetchFromNetwork,
                )
            }
            .onEach { result ->
                if (result is PagedResult.Success) {
                    mutableUiState.update { state ->
                        val localeDate = state.day.toLocalDate()
                        val currentList = state.weeklyAnime[localeDate]
                            .takeIf { state.page > 1 }
                            .orEmpty()
                        val updatedList = currentList + result.list
                        val updatedMap = state.weeklyAnime.toMutableMap()
                        updatedMap[localeDate] = updatedList

                        state.copy(
                            weeklyAnime = updatedMap,
                            providerOnlyByDate = state.releaseCalendarRows.providerOnlyByDate(
                                knownMediaIds = updatedMap.values
                                    .asSequence()
                                    .flatten()
                                    .map { it.id }
                                    .toSet(),
                                fallbackDate = state.day.toLocalDate(),
                            ),
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        )
                    }
                } else if (result is PagedResult.Loading) {
                    if (mutableUiState.value.page == 1) {
                        mutableUiState.update { it.copy(isLoading = true) }
                    }
                } else if (result is PagedResult.Error) {
                    mutableUiState.update {
                        result.toUiState(loadingWhen = it.page == 1)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

private fun List<ReleaseUiCalendarItem>.providerRowsByDate(
    fallbackDate: LocalDate,
): Map<LocalDate, List<ReleaseUiCalendarItem>> =
    asSequence()
        .filter { it.isAuthoritative }
        .groupBy { it.sourceDate ?: fallbackDate }
        .mapValues { (_, rows) -> rows.sortedForPresentation() }

private fun List<ReleaseUiCalendarItem>.providerOnlyByDate(
    knownMediaIds: Set<Int>,
    fallbackDate: LocalDate,
): Map<LocalDate, List<ReleaseUiCalendarItem>> =
    asSequence()
        .filter { it.isAuthoritative && (it.mediaId == null || it.mediaId !in knownMediaIds) }
        .groupBy { it.sourceDate ?: fallbackDate }
        .mapValues { (_, rows) -> rows.sortedForPresentation() }

private fun List<ReleaseUiCalendarItem>.sortedForPresentation(): List<ReleaseUiCalendarItem> =
    sortedWith(
        compareBy<ReleaseUiCalendarItem> { it.forecastAt ?: java.time.Instant.MAX }
            .thenBy { it.stream.stableKey }
            .thenBy { it.installment.stableKey }
            .thenByDescending { it.revision },
    )

}

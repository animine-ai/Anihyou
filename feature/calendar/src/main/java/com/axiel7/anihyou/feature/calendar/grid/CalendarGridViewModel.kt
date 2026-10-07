package com.axiel7.anihyou.feature.calendar.grid

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.utils.DateUtils.toTimestamp
import com.axiel7.anihyou.core.common.viewmodel.PagedUiStateViewModel
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.media.uniqueAiringEvents
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.feature.calendar.MEDIA_BY_IDS_PAGE
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.log.AppLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * One tab of the weekday calendar. Besides the AniList airing events of its day it reads the rows of the active release
 * source, as the list calendar does, so a tab shows the same entries, times and SUB/DUB tracks as the same day in the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarGridViewModel(
    private val mediaRepository: MediaRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val extensionRefreshScheduler: ExtensionReleaseRefreshScheduler? = null,
) : PagedUiStateViewModel<CalendarGridUiState>(), CalendarGridEvent {

    private fun localToday(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    override val initialState = CalendarGridUiState(today = localToday())

    private val displayAdult = defaultPreferencesRepository.displayAdult
    private val myUserId = defaultPreferencesRepository.userId

    private val requestedMedia = HashSet<Int>()

    fun setOnMyList(value: Boolean?) = mutableUiState.update {
        it.copy(onMyList = value, page = 1, hasNextPage = true, isLoading = true, loadId = it.loadId + 1)
    }

    /** Monday is 1. The tab shows that day of the current week. */
    fun setWeekday(value: Int) = mutableUiState.update {
        val today = localToday()
        it.copy(weekday = value, today = today, date = today.with(DayOfWeek.of(value)))
    }

    override fun refresh() {
        AppLog.i("calendar") { "tab refresh: day=${uiState.value.date} asked again, extension refresh scheduled=${extensionRefreshScheduler != null}" }
        extensionRefreshScheduler?.scheduleNow()
        mutableUiState.update {
            it.copy(fetchFromNetwork = true, page = 1, hasNextPage = true, isLoading = true, loadId = it.loadId + 1)
        }
    }

    override fun onUpdateListEntry(newListEntry: BasicMediaListEntry?) {
        val selected = mutableUiState.value.selectedItem ?: return
        val updated = selected.copy(
            mediaListEntry = newListEntry?.let {
                ExploreMedia.MediaListEntry(
                    __typename = "ExploreMedia.MediaListEntry",
                    id = newListEntry.id,
                    mediaId = newListEntry.mediaId,
                    basicMediaListEntry = newListEntry,
                )
            },
        )
        mutableUiState.update { state ->
            state.copy(
                extraMedia = state.extraMedia + (selected.id to updated),
                airingEvents = state.airingEvents.map { event ->
                    if (event.media.id == selected.id) event.copy(media = updated) else event
                },
            )
        }
    }

    override fun selectItem(value: ExploreMedia?) {
        mutableUiState.update { it.copy(selectedItem = value) }
    }

    /**
     * A source can name an entry that no loaded AniList day holds, as a dub does that runs weeks behind the original.
     * Cover and title of such an entry come from AniList by id; nothing is guessed when AniList does not answer.
     */
    private fun loadMissingMedia(ids: Collection<Int>) {
        val state = mutableUiState.value
        val known = state.airingEvents.mapTo(HashSet()) { it.media.id }
        val missing = ids.filter { it > 0 && it !in known && it !in state.extraMedia && it !in requestedMedia }.distinct()
        if (missing.isEmpty()) return
        requestedMedia += missing
        viewModelScope.launch {
            missing.chunked(MEDIA_BY_IDS_PAGE).forEach { chunk ->
                val result = runCatching {
                    mediaRepository.getMediaByIdsPage(chunk, page = 1, perPage = MEDIA_BY_IDS_PAGE)
                        .first { it !is PagedResult.Loading }
                }.getOrNull()
                if (result is PagedResult.Success) {
                    val loaded = result.list.associateBy { it.id }
                    AppLog.i("calendar") { "tab cover and title loaded by id: ${loaded.size} of ${chunk.size} entries" }
                    mutableUiState.update { it.copy(extraMedia = it.extraMedia + loaded) }
                } else {
                    AppLog.w("calendar") { "tab cover and title by id unavailable for ${chunk.size} entries, asked again with the next rows" }
                    requestedMedia -= chunk.toSet()
                }
            }
        }
    }

    init {
        // The rows of the active source around today. One window for every tab, so "a source is the main calendar" means
        // the same in every tab and in the list.
        myUserId
            .flatMapLatest { accountId ->
                val today = localToday()
                releasePresentationRepository.observeCalendar(
                    accountId = accountId?.toLong(),
                    range = today.minusDays(14)..today.plusDays(14),
                )
            }
            .onEach { rows ->
                mutableUiState.update { it.copy(releaseRows = rows) }
                val state = mutableUiState.value
                if (state.weekday != 0) {
                    loadMissingMedia(rows.filter { (it.sourceDate ?: state.today) == state.date }.mapNotNull { it.mediaId })
                }
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.calendarShowAniListExtras
            .onEach { value -> mutableUiState.update { it.copy(showAniListExtras = value == true) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.calendarCombineTracks
            .onEach { value -> mutableUiState.update { it.copy(combineSimultaneousTracks = value == true) } }
            .launchIn(viewModelScope)

        mutableUiState
            .filter { it.hasNextPage && it.weekday != 0 }
            .distinctUntilChanged { old, new ->
                old.page == new.page
                        && old.weekday == new.weekday
                        && old.loadId == new.loadId
                        && old.onMyList == new.onMyList
            }
            .combine(displayAdult, ::Pair)
            .flatMapLatest { (uiState, displayAdult) ->
                val start = uiState.date.atStartOfDay().toTimestamp(isEndOfDay = false)
                val end = uiState.date.atStartOfDay().toTimestamp(isEndOfDay = true)
                mediaRepository.getCalendarAiringEventsPage(
                    airingAtGreater = start,
                    airingAtLesser = end,
                    onMyList = uiState.onMyList,
                    isAdult = displayAdult == true,
                    page = uiState.page,
                    perPage = 50,
                    fetchFromNetwork = uiState.fetchFromNetwork,
                ).map { result -> uiState.page to result }
            }
            .onEach { (requestedPage, result) ->
                if (result is PagedResult.Success) {
                    mutableUiState.update { state ->
                        // Page 1 replaces the day, later pages add to it; one schedule entry is never listed twice.
                        val events = (if (requestedPage == 1) result.list else state.airingEvents + result.list)
                            .uniqueAiringEvents()
                        state.copy(
                            airingEvents = events,
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        )
                    }
                    val state = mutableUiState.value
                    loadMissingMedia(
                        state.releaseRows.filter { (it.sourceDate ?: state.today) == state.date }.mapNotNull { it.mediaId },
                    )
                } else {
                    mutableUiState.update {
                        result.toUiState(loadingWhen = it.page == 1)
                    }
                }
            }
            .launchIn(viewModelScope)
    }
}

package com.axiel7.anihyou.feature.calendar.week

import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.utils.DateUtils.toTimestamp
import com.axiel7.anihyou.core.common.viewmodel.UiStateViewModel
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.model.media.uniqueAiringEvents
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.feature.calendar.MEDIA_BY_IDS_PAGE
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.log.AppLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** More pages than this for one day are not expected (50 per page); the guard keeps a misbehaving API from looping. */
private const val MAX_PAGES_PER_DAY = 12

/**
 * The week calendar. Besides the AniList airing events of the days it reads the rows of the active release source, as the
 * list calendar does, so a day shows the same entries, times and SUB/DUB tracks as the same day in the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarWeekViewModel(
    private val mediaRepository: MediaRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val extensionRefreshScheduler: ExtensionReleaseRefreshScheduler? = null,
) : UiStateViewModel<CalendarWeekUiState>(), CalendarWeekEvent {

    private fun localToday(): LocalDate = clock.instant().atZone(ZoneId.systemDefault()).toLocalDate()

    override val initialState = localToday().let { today ->
        CalendarWeekUiState(weekStart = weekStartOf(today), selectedDate = today, today = today)
    }

    private val displayAdult = defaultPreferencesRepository.displayAdult
    private val myUserId = defaultPreferencesRepository.userId

    private val requestedMedia = HashSet<Int>()
    private var loadJob: Job? = null

    override fun selectDay(date: LocalDate) {
        mutableUiState.update { it.copy(selectedDate = date, today = localToday()) }
    }

    override fun previousWeek() = showWeekOf(mutableUiState.value.weekStart.minusWeeks(1))

    override fun nextWeek() = showWeekOf(mutableUiState.value.weekStart.plusWeeks(1))

    /** The same weekday in another week stays selected, as the strip would show it. */
    private fun showWeekOf(weekStart: LocalDate) {
        mutableUiState.update {
            it.copy(
                weekStart = weekStart,
                selectedDate = weekStart.plusDays(it.selectedDate.dayOfWeek.value - 1L),
                today = localToday(),
                loadedDays = emptySet(),
                fetchFromNetwork = false,
            )
        }
        loadWeek()
    }

    override fun goToToday() {
        val today = localToday()
        val reload = weekStartOf(today) != mutableUiState.value.weekStart
        mutableUiState.update { it.copy(weekStart = weekStartOf(today), selectedDate = today, today = today) }
        if (reload) {
            mutableUiState.update { it.copy(loadedDays = emptySet(), fetchFromNetwork = false) }
            loadWeek()
        }
    }

    override fun refresh() {
        AppLog.i("calendar") { "week refresh: ${uiState.value.weekStart} asked again, extension refresh scheduled=${extensionRefreshScheduler != null}" }
        extensionRefreshScheduler?.scheduleNow()
        mutableUiState.update { it.copy(fetchFromNetwork = true, loadedDays = emptySet(), today = localToday()) }
        loadWeek()
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
                airingEvents = state.airingEvents.mapValues { (_, events) ->
                    events.map { event -> if (event.media.id == selected.id) event.copy(media = updated) else event }
                },
            )
        }
    }

    override fun selectItem(value: ExploreMedia?) {
        mutableUiState.update { it.copy(selectedItem = value) }
    }

    /** The shown week, the selected day first so the list the user looks at fills before the counts of the other days. */
    private fun loadWeek() {
        loadJob?.cancel()
        val state = mutableUiState.value
        val order = (listOf(state.selectedDate) + state.days).distinct().filter { it in state.days }
        mutableUiState.update { it.copy(isLoading = true) }
        loadJob = viewModelScope.launch {
            val adult = displayAdult.first() == true
            for (date in order) {
                if (!loadDay(date, adult)) break
            }
            mutableUiState.update { it.copy(isLoading = false, fetchFromNetwork = false) }
            loadMissingMediaOfWeek()
        }
    }

    /** All pages of one day. False when the day could not be loaded, which ends the week's load with the error shown. */
    private suspend fun loadDay(date: LocalDate, adult: Boolean): Boolean {
        val start = date.atStartOfDay().toTimestamp(isEndOfDay = false)
        val end = date.atStartOfDay().toTimestamp(isEndOfDay = true)
        var collected = emptyList<CalendarAiringEvent>()
        var page = 1
        while (page <= MAX_PAGES_PER_DAY) {
            val query = mutableUiState.value
            val result = mediaRepository.getCalendarAiringEventsPage(
                airingAtGreater = start,
                airingAtLesser = end,
                onMyList = query.onMyList,
                isAdult = adult,
                page = page,
                perPage = 50,
                fetchFromNetwork = query.fetchFromNetwork,
            ).first { it !is PagedResult.Loading }
            when (result) {
                is PagedResult.Success -> {
                    // Page 1 replaces the day, later pages add to it; one schedule entry is never listed twice.
                    collected = (collected + result.list).uniqueAiringEvents()
                    mutableUiState.update { it.copy(airingEvents = it.airingEvents + (date to collected)) }
                    if (!result.hasNextPage) break
                    page += 1
                }
                is PagedResult.Error -> {
                    AppLog.w("calendar") { "week day $date page $page failed: ${result.message}" }
                    mutableUiState.update { it.copy(error = result.message, isLoading = false) }
                    return false
                }
                PagedResult.Loading -> return false
            }
        }
        mutableUiState.update { it.copy(loadedDays = it.loadedDays + date) }
        loadMissingMediaOfWeek()
        return true
    }

    private fun loadMissingMediaOfWeek() {
        val state = mutableUiState.value
        val week = state.days.toSet()
        loadMissingMedia(
            state.releaseRows.filter { (it.sourceDate ?: state.today) in week }.mapNotNull { it.mediaId },
        )
    }

    /**
     * A source can name an entry that no loaded AniList day holds, as a dub does that runs weeks behind the original.
     * Cover and title of such an entry come from AniList by id; nothing is guessed when AniList does not answer.
     */
    private fun loadMissingMedia(ids: Collection<Int>) {
        val state = mutableUiState.value
        val known = state.airingEvents.values.asSequence().flatten().mapTo(HashSet()) { it.media.id }
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
                    AppLog.i("calendar") { "week cover and title loaded by id: ${loaded.size} of ${chunk.size} entries" }
                    mutableUiState.update { it.copy(extraMedia = it.extraMedia + loaded) }
                } else {
                    AppLog.w("calendar") { "week cover and title by id unavailable for ${chunk.size} entries, asked again with the next rows" }
                    requestedMedia -= chunk.toSet()
                }
            }
        }
    }

    init {
        // The rows of the active source around today and around the shown week: whether a source is the main calendar
        // means the same here as in the list and in the tabs.
        myUserId
            .flatMapLatest { accountId ->
                mutableUiState
                    .map { it.weekStart }
                    .distinctUntilChanged()
                    .flatMapLatest { weekStart ->
                        val today = localToday()
                        releasePresentationRepository.observeCalendar(
                            accountId = accountId?.toLong(),
                            range = minOf(today.minusDays(14), weekStart)..maxOf(today.plusDays(14), weekStart.plusDays(6)),
                        )
                    }
            }
            .onEach { rows ->
                mutableUiState.update { it.copy(releaseRows = rows) }
                loadMissingMediaOfWeek()
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.calendarShowAniListExtras
            .onEach { value -> mutableUiState.update { it.copy(showAniListExtras = value == true) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.calendarCombineTracks
            .onEach { value -> mutableUiState.update { it.copy(combineSimultaneousTracks = value == true) } }
            .launchIn(viewModelScope)

        // The list filter changes what AniList is asked for, so the week is loaded again; the first value is the one the
        // week is loaded with anyway.
        defaultPreferencesRepository.calendarOnMyList
            .distinctUntilChanged()
            .onEach { value ->
                if (value != mutableUiState.value.onMyList) {
                    mutableUiState.update { it.copy(onMyList = value, loadedDays = emptySet()) }
                    loadWeek()
                }
            }
            .launchIn(viewModelScope)

        loadWeek()
    }
}

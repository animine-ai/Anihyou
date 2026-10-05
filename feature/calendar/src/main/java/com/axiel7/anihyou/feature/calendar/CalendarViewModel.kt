package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.release.core.log.AppLog
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.common.utils.DateUtils.toTimestamp
import com.axiel7.anihyou.core.common.viewmodel.PagedUiStateViewModel
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.model.ListStyle
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.model.media.uniqueAiringEvents
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val mediaRepository: MediaRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val listPreferencesRepository: ListPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val extensionRefreshScheduler: com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler? = null,
) : PagedUiStateViewModel<CalendarUiState>(), CalendarEvent {

    override val initialState = CalendarUiState(
        day = nowLocalDateTime().minusDays(1),
        today = nowLocalDateTime().toLocalDate(),
    )

    private fun nowLocalDateTime(): LocalDateTime =
        LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())

    private val onMyList = defaultPreferencesRepository.calendarOnMyList
    private val myUserId = defaultPreferencesRepository.userId
    private val displayAdult = defaultPreferencesRepository.displayAdult

    /** The device-local date. It follows the clock: a screen that stays open across midnight must not keep yesterday. */
    private var today = nowLocalDateTime().toLocalDate()

    private val requestedMedia = HashSet<Int>()

    /**
     * A release source can name an entry that no loaded AniList day holds, as a dub does that runs weeks behind the
     * original. Cover and title of such an entry come from AniList by id, a few requests for all of them.
     */
    private fun loadMissingMedia(ids: Collection<Int>) {
        val state = mutableUiState.value
        val known = state.weeklyAnime.values.asSequence().flatten().map { it.media.id }.toSet()
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
                    AppLog.i("calendar") { "cover and title loaded by id: ${loaded.size} of ${chunk.size} entries" }
                    mutableUiState.update { it.copy(extraMedia = it.extraMedia + loaded) }
                } else {
                    AppLog.w("calendar") { "cover and title by id unavailable for ${chunk.size} entries, asked again with the next rows" }
                    requestedMedia -= chunk.toSet()
                }
            }
        }
    }

    override fun onMyListChanged(value: Boolean?) {
        viewModelScope.launch {
            defaultPreferencesRepository.setCalendarOnMyList(value)
        }
    }

    override fun onShowAniListExtrasChanged(value: Boolean) {
        AppLog.i("calendar") { "AniList entries without a source match: $value" }
        viewModelScope.launch {
            defaultPreferencesRepository.setCalendarShowAniListExtras(value)
        }
    }

    override fun onChangeListStyle(value: ListStyle) {
        viewModelScope.launch {
            listPreferencesRepository.setCalendarListStyle(value)
        }
    }

    override fun onUpdateListEntry(viewListEntry: BasicMediaListEntry?) {
        mutableUiState.value.run {
            selectedItem?.let { selectedItem ->
                val updatedMedia = selectedItem.copy(
                    mediaListEntry = viewListEntry?.let {
                        ExploreMedia.MediaListEntry(
                            __typename = "ExploreMedia.MediaListEntry", id = viewListEntry.id,
                            mediaId = viewListEntry.mediaId, basicMediaListEntry = viewListEntry,
                        )
                    },
                )
                mutableUiState.update { state ->
                    state.copy(weeklyAnime = state.weeklyAnime.mapValues { (_, events) ->
                        events.map { event ->
                            if (event.media.id == selectedItem.id) event.copy(media = updatedMedia) else event
                        }
                    }.toMutableMap())
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
        AppLog.d("calendar") { "load next day after ${uiState.value.day.toLocalDate()}" }
        mutableUiState.update {
            it.copy(
                day = it.day.plusDays(1),
                page = 1,
                hasNextPage = true,
                isLoading = true,
            )
        }
    }

    override fun refresh() {
        AppLog.i("calendar") { "pull to refresh: AniList days reset, extension refresh scheduled=${extensionRefreshScheduler != null}" }
        extensionRefreshScheduler?.scheduleNow()
        mutableUiState.update {
            it.copy(
                fetchFromNetwork = true,
                day = nowLocalDateTime().minusDays(1),
                weeklyAnime = mutableMapOf(),
                page = 1,
                hasNextPage = true,
                isLoading = true,
                autoScrollToToday = false,
            )
        }
    }

    override fun refreshDay(date: LocalDate) {
        val start = date.atStartOfDay().toTimestamp(isEndOfDay = false)
        val end = date.atStartOfDay().toTimestamp(isEndOfDay = true)
        viewModelScope.launch {
            val animes = mutableListOf<CalendarAiringEvent>()
            var currentPage = 1
            var hasNextPage = true
            var fetchFailed = false

            mutableUiState.update {
                it.copy(isLoading = true)
            }

            while (hasNextPage) {
                var pageEvents = emptyList<CalendarAiringEvent>()
                mediaRepository.getCalendarAiringEventsPage(
                    airingAtGreater = start,
                    airingAtLesser = end,
                    onMyList = mutableUiState.value.onMyList,
                    isAdult = displayAdult.first() == true,
                    page = currentPage,
                    perPage = 50,
                    fetchFromNetwork = true,
                ).collect { result ->
                    if (result is PagedResult.Success) {
                        pageEvents = result.list
                        hasNextPage = result.hasNextPage
                    } else if (result is PagedResult.Error) {
                        fetchFailed = true
                        hasNextPage = false
                        mutableUiState.update {
                            result.toUiState(loadingWhen = it.page == 1)
                        }
                    }
                }
                if (fetchFailed) return@launch
                animes.addAll(pageEvents)
                currentPage++
            }

            mutableUiState.update { state ->
                val updatedMap = state.weeklyAnime.withAiringPage(date, replaceDay = true, animes)
                state.copy(
                    weeklyAnime = updatedMap,
                    providerOnlyByDate = state.releaseCalendarRows.providerOnlyByDate(
                        knownMediaIds = updatedMap.values
                            .asSequence()
                            .flatten()
                            .map { it.media.id }
                            .toSet(),
                        fallbackDate = state.day.toLocalDate(),
                    ),
                    isLoading = false,
                ).withTodayFirstItemIndex()
            }
        }
    }

    override fun onScreenEntered() {
        AppLog.i("calendar") { "screen entered: focus today again (anchor ready=${uiState.value.todayAnchorReady}, index=${uiState.value.todayFirstItemIndex})" }
        mutableUiState.update { if (it.autoScrollToToday) it else it.copy(autoScrollToToday = true) }
    }

    override fun onAutoScrolled() {
        // Initial focus is one-shot; the Today index remains available for the FAB.
        mutableUiState.update { it.copy(autoScrollToToday = false) }
    }

    init {
        // Midnight: re-anchor Today and the request window, without moving what the user is looking at.
        viewModelScope.launch {
            while (true) {
                delay(millisUntilNextLocalDay(ZonedDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())))
                val date = nowLocalDateTime().toLocalDate()
                if (date != today) {
                    today = date
                    mutableUiState.update { it.copy(today = date, autoScrollToToday = false).withTodayFirstItemIndex() }
                }
            }
        }
        mutableUiState
            .map { state ->
                maxOf(today.plusDays(14), state.day.toLocalDate().plusDays(14))
            }
            .distinctUntilChanged()
            .flatMapLatest { endDate ->
                myUserId.flatMapLatest { accountId ->
                    releasePresentationRepository.observeCalendar(
                        accountId = accountId?.toLong(),
                        range = today.minusDays(14)..endDate,
                    )
                }
            }
            .onEach { rows ->
                mutableUiState.update { state ->
                    val authoritativeRows = rows.filter { it.isAuthoritative }
                    AppLog.i("calendar") {
                        "extension rows received=${rows.size} authoritative=${authoritativeRows.size} " +
                            "days=${authoritativeRows.mapNotNull { it.sourceDate }.distinct().size} -> source is main: ${authoritativeRows.isNotEmpty()}"
                    }
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
                                .map { it.media.id }
                                .toSet(),
                            fallbackDate = state.day.toLocalDate(),
                        ),
                    ).withTodayFirstItemIndex()
                }
                loadMissingMedia(rows.mapNotNull { it.mediaId })
            }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.calendarShowAniListExtras
            .onEach { value -> mutableUiState.update { it.copy(showAniListExtras = value == true) } }
            .launchIn(viewModelScope)

        defaultPreferencesRepository.calendarCombineTracks
            .onEach { value -> mutableUiState.update { it.copy(combineSimultaneousTracks = value == true).withTodayFirstItemIndex() } }
            .launchIn(viewModelScope)

        listPreferencesRepository.calendarListStyle
            .onEach { value ->
                mutableUiState.update { it.copy(listStyle = value) }
            }
            .launchIn(viewModelScope)

        onMyList.onEach { onMyListVal ->
            if (mutableUiState.value.onMyList != onMyListVal) {
                mutableUiState.update {
                    it.copy(
                        onMyList = onMyListVal,
                        weeklyAnime = mutableMapOf(),
                        day = nowLocalDateTime().minusDays(1),
                        todayFirstItemIndex = 0,
                        todayAnchorReady = false,
                        autoScrollToToday = true,
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
                mediaRepository.getCalendarAiringEventsPage(
                    airingAtGreater = start,
                    airingAtLesser = end,
                    onMyList = onMyList.first(),
                    isAdult = displayAdult == true,
                    page = uiState.page,
                    perPage = 50,
                    fetchFromNetwork = uiState.fetchFromNetwork,
                ).map { result -> Triple(uiState.day.toLocalDate(), uiState.page, result) }
            }
            .onEach { (requestedDate, requestedPage, result) ->
                if (result is PagedResult.Success) {
                    mutableUiState.updateAndGet { state ->
                        val updatedMap = state.weeklyAnime.withAiringPage(
                            requestedDate, replaceDay = requestedPage == 1, events = result.list,
                        )

                        AppLog.i("calendar") {
                            "AniList page day=$requestedDate page=$requestedPage got=${result.list.size} hasNext=${result.hasNextPage} " +
                                "replaceDay=${requestedPage == 1} days=${updatedMap.size} events=${updatedMap.values.sumOf { it.size }}"
                        }
                        state.copy(
                            weeklyAnime = updatedMap,
                            providerOnlyByDate = state.releaseCalendarRows.providerOnlyByDate(
                                knownMediaIds = updatedMap.values
                                    .asSequence()
                                    .flatten()
                                    .map { it.media.id }
                                    .toSet(),
                                fallbackDate = state.day.toLocalDate(),
                            ),
                            hasNextPage = result.hasNextPage,
                            isLoading = false,
                        ).withTodayFirstItemIndex()
                    }.also {
                        if (it.day.toLocalDate() < today) onLoadMore()
                    }
                } else if (result is PagedResult.Loading) {
                    if (mutableUiState.value.page == 1) {
                        mutableUiState.update { it.copy(isLoading = true) }
                    }
                } else if (result is PagedResult.Error) {
                    AppLog.w("calendar") { "AniList page day=$requestedDate page=$requestedPage failed: ${result.message}" }
                    mutableUiState.update {
                        it.copy(
                            error = result.message,
                            isLoading = false,
                            hasNextPage = !result.message.contains("Too many requests"),
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun CalendarUiState.withTodayFirstItemIndex(): CalendarUiState {
        val index = presentationDays().filter { it.date < today }
            .sumOf { it.rows.size + 1 } // exactly the displayed rows plus their date header
        // The list starts at yesterday. Today is only a stable anchor once the days before it have arrived, otherwise the
        // one-shot initial focus is spent on an index that still moves and the screen stays at the top.
        val ready = isTodayAnchorStable(day.toLocalDate(), today, hasNextPage, error)
        AppLog.d("calendar") {
            "today anchor index=$index ready=$ready loadedDay=${day.toLocalDate()} today=$today hasNext=$hasNextPage " +
                "autoFocus=$autoScrollToToday days=${presentationDays().size}"
        }
        return copy(
            todayFirstItemIndex = index,
            todayAnchorReady = ready,
        )
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

/** The wait until the device-local date changes, DST days included (a local day is 23 to 25 hours long). */
internal fun millisUntilNextLocalDay(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis()
        .coerceAtLeast(1_000L) + 500L

/** The loaded request window has reached today, or nothing more will load (end of data, error). */
internal fun isTodayAnchorStable(loadedDay: LocalDate, today: LocalDate, hasNextPage: Boolean, error: String?): Boolean =
    loadedDay >= today || !hasNextPage || error != null

/** How many entries one request for cover and title by id asks for (AniList allows 50 per page). */
private const val MEDIA_BY_IDS_PAGE = 50

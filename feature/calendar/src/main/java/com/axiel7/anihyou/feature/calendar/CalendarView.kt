package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.release.core.log.AppLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.base.UNKNOWN_CHAR
import com.axiel7.anihyou.core.common.utils.DateUtils.timestampToTimeString
import com.axiel7.anihyou.core.model.CalendarStyle
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.model.media.uniqueAiringEvents
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.resources.ColorUtils.colorFromHex
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.LocalBlurAdult
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.rememberSnackbarManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.core.ui.composables.common.ErrorDialogHandler
import com.axiel7.anihyou.core.ui.composables.common.IconButtonWithMenu
import com.axiel7.anihyou.core.ui.composables.list.OnBottomReached
import com.axiel7.anihyou.core.ui.composables.media.MEDIA_POSTER_SMALL_WIDTH
import com.axiel7.anihyou.core.ui.composables.media.MediaItemVertical
import com.axiel7.anihyou.core.ui.composables.media.ReleaseCalendarGroupScheduleText
import com.axiel7.anihyou.release.core.api.ReleaseCalendarUiSelection
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.core.ui.composables.media.MediaItemVerticalPlaceholder
import com.axiel7.anihyou.feature.calendar.composables.CalendarAiringHorizontalItem
import com.axiel7.anihyou.feature.calendar.composables.CalendarAiringHorizontalItemPlaceholder
import com.axiel7.anihyou.feature.calendar.composables.CalendarBanner
import com.axiel7.anihyou.feature.calendar.composables.CalendarBannerPlaceholder
import com.axiel7.anihyou.feature.calendar.grid.CalendarGridView
import com.axiel7.anihyou.feature.calendar.week.CalendarWeekView
import com.axiel7.anihyou.feature.editmedia.EditMediaSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

/**
 * The calendar in the style the user picked: the list of days, the weekday tabs, or the week strip with compact or large
 * rows. Every style takes its rows from the active release source first, as the main tab and the pushed screen do.
 */
@Composable
fun CalendarHostView(
    isLoggedIn: Boolean,
    isMain: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarHostViewModel = koinViewModel()
    val style by viewModel.style.collectAsStateWithLifecycle(null)

    AnimatedContent(
        targetState = style
    ) { style ->
        when (style) {
            CalendarStyle.LIST -> CalendarView(isLoggedIn = isLoggedIn, isMain = isMain, modifier = modifier)
            CalendarStyle.TABS -> CalendarGridView(isLoggedIn, viewModel, isMain = isMain, modifier = modifier)
            CalendarStyle.WEEK, CalendarStyle.WEEK_LARGE -> CalendarWeekView(
                isLoggedIn = isLoggedIn,
                hostViewModel = viewModel,
                largeItems = style == CalendarStyle.WEEK_LARGE,
                isMain = isMain,
                modifier = modifier,
            )
            null -> {}
        }
    }
}

@Composable
private fun CalendarView(
    isLoggedIn: Boolean,
    isMain: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CalendarViewContent(
        isLoggedIn = isLoggedIn,
        uiState = uiState,
        event = viewModel,
        isMain = isMain,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CalendarViewContent(
    isLoggedIn: Boolean,
    uiState: CalendarUiState,
    event: CalendarEvent?,
    isMain: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val navActionManager = LocalNavActionManager.current
    val topAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        rememberTopAppBarState()
    )
    val scope = rememberCoroutineScope()
    val snackbarManager = rememberSnackbarManager()
    val pullToRefreshState = rememberPullToRefreshState()
    var showEditSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Every visit of the tab starts on today, not only the first one after the app opened.
    LaunchedEffect(Unit) { event?.onScreenEntered() }

    fun showEditSheetAction() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (isLoggedIn) {
            showEditSheet = true
        } else {
            snackbarManager.showNotLoggedInSnackbar()
        }
    }

    ErrorDialogHandler(uiState, onDismiss = { event?.onErrorDisplayed() })

    if (showEditSheet && uiState.selectedItem != null) {
        EditMediaSheet(
            mediaDetails = uiState.selectedItem.basicMediaDetails,
            listEntry = uiState.selectedItem.mediaListEntry?.basicMediaListEntry,
            onEntryUpdated = {
                event?.onUpdateListEntry(it)
            },
            onDismissed = {
                showEditSheet = false
            }
        )
    }

    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.calendar),
        modifier = modifier,
        navigationIcon = { if (!isMain) BackIconButton(onClick = navActionManager::goBack) },
        actions = {
            AppBarActions(
                style = CalendarStyle.LIST,
                onMyList = uiState.onMyList,
                showAniListExtras = uiState.showAniListExtras,
                sourceIsMain = uiState.sourceIsMain(),
                onChangeStyle = { event?.onChangeStyle(it) },
                onChangeOnMyList = { event?.onMyListChanged(it) },
                onChangeShowAniListExtras = { event?.onShowAniListExtrasChanged(it) },
            )
        },
        snackbarHost = snackbarManager::SnackbarHost,
        scrollBehavior = topAppBarScrollBehavior,
        floatingActionButton = {
            val isAwayFromToday = uiState.todayAnchorReady &&
                listState.firstVisibleItemIndex != uiState.todayFirstItemIndex
            FloatingActionButton(
                onClick = {
                    AppLog.i("calendar") { "jump to today tapped: index=${uiState.todayFirstItemIndex}" }
                    scope.launch {
                        listState.animateScrollToItem(uiState.todayFirstItemIndex)
                    }
                },
                modifier = Modifier.animateFloatingActionButton(
                    visible = isAwayFromToday,
                    alignment = Alignment.BottomEnd
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.calendar_today_24),
                    contentDescription = stringResource(R.string.jump_to_today)
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.fetchFromNetwork && uiState.isLoading,
            onRefresh = { event?.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isLoading,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        ) {
            val contentPadding = PaddingValues(
                start = padding.calculateStartPadding(LocalLayoutDirection.current),
                end = padding.calculateStartPadding(LocalLayoutDirection.current),
                bottom = padding.calculateBottomPadding(),
            )
            ListView(
                uiState = uiState,
                event = event,
                listState = listState,
                contentPadding = contentPadding,
                showEditSheetAction = ::showEditSheetAction,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            )
        }
    }
}

@Composable
internal fun AppBarActions(
    style: CalendarStyle?,
    onMyList: Boolean?,
    /** Only matters while a release source supplies rows ([sourceIsMain]): AniList entries without a source match stay hidden unless on. */
    showAniListExtras: Boolean,
    sourceIsMain: Boolean,
    onChangeStyle: (CalendarStyle) -> Unit,
    onChangeOnMyList: (Boolean?) -> Unit,
    onChangeShowAniListExtras: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    IconButtonWithMenu(
        icon = if (style == CalendarStyle.LIST) R.drawable.format_list_bulleted_24 else R.drawable.grid_view_24,
        contentDescription = stringResource(R.string.list_style),
    ) { onDismiss ->
        CalendarStyle.entries.forEachIndexed { index, entry ->
            SelectableDropdownMenuItem(
                selected = entry == style,
                onClick = {
                    onChangeStyle(entry)
                    onDismiss()
                },
                text = { Text(text = stringResource(entry.stringRes)) },
                shapes = MenuDefaults.itemShape(index, CalendarStyle.entries.size),
                modifier = Modifier.testTag("calendar-style-${entry.name}"),
                selectedLeadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.check_20),
                        contentDescription = null,
                        modifier = Modifier.size(MenuDefaults.LeadingIconSize)
                    )
                },
            )
        }
    }

    IconButtonWithMenu(
        icon = R.drawable.more_vert_24,
        contentDescription = stringResource(R.string.show_more),
        modifier = modifier,
    ) { onDismiss ->
        SelectableDropdownMenuItem(
            selected = onMyList != null,
            onClick = {
                onChangeOnMyList(if (onMyList == true) null else true)
                onDismiss()
            },
            text = { Text(text = stringResource(R.string.on_my_list)) },
            shapes = MenuDefaults.itemShape(0, if (sourceIsMain) 2 else 1),
            selectedLeadingIcon = {
                if (onMyList != null) {
                    Icon(
                        painter = painterResource(
                            id = if (onMyList) R.drawable.check_20 else R.drawable.close_20
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(MenuDefaults.LeadingIconSize)
                    )
                }
            },
        )
        if (sourceIsMain) {
            SelectableDropdownMenuItem(
                selected = showAniListExtras,
                onClick = {
                    onChangeShowAniListExtras(!showAniListExtras)
                    onDismiss()
                },
                text = { Text(text = stringResource(R.string.calendar_show_anilist_extras)) },
                shapes = MenuDefaults.itemShape(1, 2),
                selectedLeadingIcon = {
                    if (showAniListExtras) {
                        Icon(
                            painter = painterResource(id = R.drawable.check_20),
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize)
                        )
                    }
                },
            )
        }
    }
}

internal fun CalendarUiState.sourceIsMain(): Boolean =
    providerRowsByDate.values.any { rows -> rows.any { it.isAuthoritative } }

@Composable
private fun StickyHeader(
    mediaList: ImmutableList<ExploreMedia>,
    date: LocalDate,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val titleId = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> R.string.monday
        DayOfWeek.TUESDAY -> R.string.tuesday
        DayOfWeek.WEDNESDAY -> R.string.wednesday
        DayOfWeek.THURSDAY -> R.string.thursday
        DayOfWeek.FRIDAY -> R.string.friday
        DayOfWeek.SATURDAY -> R.string.saturday
        DayOfWeek.SUNDAY -> R.string.sunday
    }
    val title = stringResource(id = titleId)
    val media = mediaList.maxWithOrNull(
        compareBy<ExploreMedia> { it.popularity ?: Int.MIN_VALUE }
            .thenBy {
                it.averageScore ?: Int.MIN_VALUE
            } // if popularity is the same, fallback to score
    )
    val banner =
        media?.bannerImage ?: mediaList.firstNotNullOfOrNull { it.bannerImage }
    val imageColor = colorFromHex(media?.coverImage?.color)

    CalendarBanner(
        title = title,
        date = date.atStartOfDay(),
        imageUrl = banner,
        height = 100.dp,
        color = imageColor,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().testTag("calendar-day-$date")
    )
}

internal data class CalendarRow(
    val key: String,
    val media: ExploreMedia?,
    val releasePresentations: List<ReleaseUiCalendarItem>,
    val airingEvent: CalendarAiringEvent? = null,
)

internal fun CalendarRow.providerFallbackTitle(): String? =
    releasePresentations.firstOrNull()?.stream?.stableSeriesKey?.value
        ?.removePrefix("/anime/stream/")
        ?.takeIf { it.isNotBlank() }
        ?.replace('-', ' ')
        ?.replace('_', ' ')
        ?.split(' ')
        ?.joinToString(" ") { part ->
            part.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }

internal data class CalendarDay(
    val date: LocalDate,
    val rows: List<CalendarRow>,
)

/**
 * A lazy list crashes on a repeated key. AniList may list one media twice for a day (two episodes the same day, pages that
 * overlap) and the upstream list simply showed both rows, so the rows stay and only a repeated key gets an occurrence suffix.
 * The first row keeps its plain key, which keeps keys stable across a refresh.
 */
internal fun List<CalendarRow>.withUniqueKeys(): List<CalendarRow> {
    val used = HashSet<String>(size)
    return map { row ->
        var key = row.key
        var occurrence = 1
        while (!used.add(key)) {
            occurrence += 1
            key = "${row.key}#$occurrence"
        }
        if (key == row.key) row else row.copy(key = key)
    }
}

internal fun CalendarUiState.presentationDays(): List<CalendarDay> {
    val metadataByMediaId = extraMedia + weeklyAnime.values.asSequence().flatten().map { it.media }.associateBy { it.id }
    val sourceCoveredMediaIds = providerRowsByDate.values.asSequence().flatten()
        .filter { it.isAuthoritative }.mapNotNull { it.mediaId }.toSet()
    // A release source is the main calendar as soon as it supplies rows: AniList entries without its match stay out unless asked for.
    val sourceIsMain = sourceIsMain()
    return (weeklyAnime.keys + providerRowsByDate.keys + providerOnlyByDate.keys + today)
        .toSortedSet()
        .mapNotNull { date ->
            val providerRows = providerRowsByDate[date].orEmpty()
            val separateReleaseRows = if (providerRows.isNotEmpty()) {
                // Provider events own the date. AniList supplies metadata only.
                val (knownRows, providerOnlyRows) = providerRows.partition { row ->
                    row.mediaId?.let(metadataByMediaId::containsKey) == true
                }
                (knownRows + providerOnlyRows).map { release ->
                    CalendarRow(
                        key = "$date-provider-${release.eventKey}",
                        media = release.mediaId?.let(metadataByMediaId::get),
                        releasePresentations = listOf(release),
                    )
                }
            } else {
                providerOnlyByDate[date].orEmpty().map { release ->
                    CalendarRow(
                        key = "$date-provider-${release.eventKey}",
                        media = null,
                        releasePresentations = listOf(release),
                    )
                }
            }
            val releaseRows = if (combineSimultaneousTracks) {
                val byRelease = separateReleaseRows.associateBy { it.releasePresentations.single() }
                ReleaseCalendarUiSelection
                    .simultaneousTracks(separateReleaseRows.map { it.releasePresentations.single() })
                    .map { group ->
                        val representative = requireNotNull(ReleaseCalendarUiSelection.representative(group))
                        requireNotNull(byRelease[representative]).copy(releasePresentations = group)
                    }
            } else separateReleaseRows
            val originalRows = weeklyAnime[date].orEmpty().uniqueAiringEvents()
                .filter { sourceIsMain.not() || showAniListExtras }
                .filter { it.media.id !in sourceCoveredMediaIds }.map { airing ->
                    CalendarRow(
                        key = "$date-anilist-${airing.scheduleId}",
                        media = airing.media,
                        releasePresentations = emptyList(),
                        airingEvent = airing,
                    )
                }
            // A source event has no list membership of its own. Only loaded AniList metadata proves it.
            // Unknown/provider-only entries stay visible with no filter, never in "On my list".
            val rows = (releaseRows + originalRows).filter { row ->
                when (onMyList) {
                    true -> row.media?.mediaListEntry != null
                    false -> row.media != null && row.media.mediaListEntry == null
                    null -> true
                }
            }
            if (rows.isNotEmpty() || date == today) CalendarDay(date, rows.withUniqueKeys()) else null
        }
}

@Composable
internal fun calendarSubtitle(row: CalendarRow): String =
    if (row.releasePresentations.any { it.isAuthoritative }) {
        ""
    } else {
        row.airingEvent?.let { airingEvent ->
            stringResource(
                R.string.episode_airing_at,
                airingEvent.episode,
                airingEvent.airingAt.toLong().timestampToTimeString() ?: UNKNOWN_CHAR,
            )
        } ?: stringResource(R.string.unknown)
    }

@Composable
private fun ListView(
    uiState: CalendarUiState,
    event: CalendarEvent?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    showEditSheetAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navActionManager = LocalNavActionManager.current
    val blurAdult = LocalBlurAdult.current

    listState.OnBottomReached(buffer = 1, debounceDuration = 500.milliseconds) {
        event?.onLoadMore()
    }
    // The first screen is today. The index of today moves while the days before it are still arriving (more AniList
    // pages, extension rows), so the focus is renewed on every change until the user takes over by dragging. A single
    // animated scroll that then marked itself done left the list on whatever day was at that index at that moment.
    // A scroll to today is clamped while there are not yet enough items behind it to fill the screen (cold start: today's
    // page arrives after yesterday's), so the size of the content is part of the key and the focus is applied again.
    val totalItems = uiState.presentationDays().sumOf { it.rows.size + 1 }
    LaunchedEffect(uiState.todayAnchorReady, uiState.autoScrollToToday, uiState.todayFirstItemIndex, totalItems) {
        if (uiState.todayAnchorReady && uiState.autoScrollToToday) {
            AppLog.d("calendar") { "focus today: scroll list to item ${uiState.todayFirstItemIndex} (items=$totalItems)" }
            listState.scrollToItem(uiState.todayFirstItemIndex)
            AppLog.d("calendar") { "focus today: list now at item ${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}" }
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                AppLog.d("calendar") { "user took over the scroll position" }
                event?.onAutoScrolled()
            }
        }
    }

    LazyColumn(
        modifier = modifier.testTag("calendar-list"),
        contentPadding = contentPadding,
        state = listState,
    ) {
        uiState.presentationDays().forEach { day ->
            stickyHeader(key = "date-${day.date}") {
                StickyHeader(
                    mediaList = day.rows.mapNotNull { it.media }.distinctBy { it.id }.toImmutableList(),
                    date = day.date,
                    onLongClick = { event?.refreshDay(day.date) },
                )
            }
            itemsIndexed(
                items = day.rows,
                key = { _, row -> row.key },
                contentType = { _, row -> if (row.releasePresentations.isEmpty()) "anilist-media" else "provider-event" },
            ) { index, row ->
                val item = row.media
                CalendarAiringHorizontalItem(
                    title = item?.basicMediaDetails?.title?.userPreferred.orEmpty()
                        .ifBlank { row.providerFallbackTitle().orEmpty() }
                        .ifBlank { stringResource(R.string.release_provider_only) },
                    subtitle = calendarSubtitle(row),
                    releasePresentations = row.releasePresentations,
                    blurImage = blurAdult && item?.basicMediaDetails?.isAdult == true,
                    imageUrl = item?.coverImage?.large,
                    score = item?.averageScore,
                    status = item?.mediaListEntry?.basicMediaListEntry?.status,
                    onClick = {
                        (item?.id ?: row.releasePresentations.firstOrNull()?.mediaId)
                            ?.let(navActionManager::toMediaDetails)
                    },
                    onLongClick = {
                        item?.let {
                            event?.selectItem(it)
                            showEditSheetAction()
                        }
                    },
                    // The first entry of a day keeps the same gap to the date banner as the entries keep among themselves.
                    modifier = Modifier.padding(top = if (index == 0) 8.dp else 0.dp, bottom = 8.dp),
                )
            }
        }
        if (uiState.isLoading && uiState.presentationDays().all { it.rows.isEmpty() }) {
            item {
                CalendarBannerPlaceholder(modifier = Modifier.padding(bottom = 8.dp))
            }
            items(count = 20, contentType = { "placeholder" }) {
                CalendarAiringHorizontalItemPlaceholder(modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

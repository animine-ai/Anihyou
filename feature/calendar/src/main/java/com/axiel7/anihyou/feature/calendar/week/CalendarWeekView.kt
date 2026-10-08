package com.axiel7.anihyou.feature.calendar.week

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.model.CalendarStyle
import com.axiel7.anihyou.core.model.genre.SelectableGenre.Companion.genreTagLocalized
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.common.LocalBlurAdult
import com.axiel7.anihyou.core.ui.common.LocalNavActionManager
import com.axiel7.anihyou.core.ui.common.SnackbarManager
import com.axiel7.anihyou.core.ui.common.rememberSnackbarManager
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.BackIconButton
import com.axiel7.anihyou.core.ui.composables.common.ErrorDialogHandler
import com.axiel7.anihyou.core.ui.composables.media.MediaItemHorizontal
import com.axiel7.anihyou.core.ui.composables.media.MediaItemHorizontalPlaceholder
import com.axiel7.anihyou.core.ui.composables.media.ReleaseCalendarGroupScheduleText
import com.axiel7.anihyou.core.ui.composables.scores.SmallScoreIndicator
import com.axiel7.anihyou.feature.calendar.AppBarActions
import com.axiel7.anihyou.feature.calendar.CalendarHostViewModel
import com.axiel7.anihyou.feature.calendar.CalendarRow
import com.axiel7.anihyou.feature.calendar.calendarSubtitle
import com.axiel7.anihyou.feature.calendar.composables.CalendarAiringHorizontalItem
import com.axiel7.anihyou.feature.calendar.composables.CalendarAiringHorizontalItemPlaceholder
import com.axiel7.anihyou.feature.calendar.providerFallbackTitle
import com.axiel7.anihyou.feature.editmedia.EditMediaSheet
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.time.format.TextStyle as DateTextStyle

/**
 * The calendar as a week: arrows to move between weeks, a strip of the seven days with the number of releases of each,
 * and the releases of the selected day below. The rows are the ones of the list and the tabs (the active release source
 * first, AniList where no source row stands for it); [largeItems] picks the size of the rows, the compact ones of the list
 * calendar or the larger ones of the Discover lists.
 */
@Composable
fun CalendarWeekView(
    isLoggedIn: Boolean,
    hostViewModel: CalendarHostViewModel,
    largeItems: Boolean,
    isMain: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarWeekViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onMyList by hostViewModel.onMyList.collectAsStateWithLifecycle(initialValue = null)
    val showAniListExtras by hostViewModel.showAniListExtras.collectAsStateWithLifecycle(initialValue = false)
    val sourceIsMain by hostViewModel.sourceIsMain.collectAsStateWithLifecycle(initialValue = false)

    CalendarWeekContent(
        isLoggedIn = isLoggedIn,
        isMain = isMain,
        largeItems = largeItems,
        uiState = uiState,
        event = viewModel,
        onMyList = onMyList,
        showAniListExtras = showAniListExtras,
        sourceIsMain = sourceIsMain,
        onChangeStyle = hostViewModel::onChangeStyle,
        onChangeOnMyList = hostViewModel::onMyListChanged,
        onChangeShowAniListExtras = hostViewModel::onShowAniListExtrasChanged,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CalendarWeekContent(
    isLoggedIn: Boolean,
    isMain: Boolean,
    largeItems: Boolean,
    uiState: CalendarWeekUiState,
    event: CalendarWeekEvent?,
    onMyList: Boolean?,
    showAniListExtras: Boolean,
    sourceIsMain: Boolean,
    onChangeStyle: (CalendarStyle) -> Unit,
    onChangeOnMyList: (Boolean?) -> Unit,
    onChangeShowAniListExtras: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navActionManager = LocalNavActionManager.current
    val topAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val snackbarManager = rememberSnackbarManager()
    val showEditSheet = remember { mutableStateOf(false) }

    ErrorDialogHandler(uiState, onDismiss = { event?.onErrorDisplayed() })

    DefaultScaffoldWithSmallTopAppBar(
        title = stringResource(R.string.calendar),
        modifier = modifier,
        navigationIcon = { if (!isMain) BackIconButton(onClick = navActionManager::goBack) },
        actions = {
            AppBarActions(
                style = if (largeItems) CalendarStyle.WEEK_LARGE else CalendarStyle.WEEK,
                onMyList = onMyList,
                showAniListExtras = showAniListExtras,
                sourceIsMain = sourceIsMain,
                onChangeStyle = onChangeStyle,
                onChangeOnMyList = onChangeOnMyList,
                onChangeShowAniListExtras = onChangeShowAniListExtras,
            )
        },
        snackbarHost = snackbarManager::SnackbarHost,
        scrollBehavior = topAppBarScrollBehavior,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { event?.goToToday() },
                modifier = Modifier
                    .testTag("calendar-week-today")
                    .animateFloatingActionButton(
                        visible = uiState.selectedDate != uiState.today,
                        alignment = Alignment.BottomEnd,
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.calendar_today_24),
                    contentDescription = stringResource(R.string.jump_to_today),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            CalendarWeekHeader(
                weekStart = uiState.weekStart,
                onPrevious = { event?.previousWeek() },
                onNext = { event?.nextWeek() },
            )
            val rowsByDay = remember(uiState) { uiState.rowsByDay() }
            CalendarWeekStrip(
                uiState = uiState,
                rowsByDay = rowsByDay,
                onSelect = { event?.selectDay(it) },
            )
            CalendarWeekDayView(
                isLoggedIn = isLoggedIn,
                snackbarManager = snackbarManager,
                uiState = uiState,
                rows = rowsByDay[uiState.selectedDate].orEmpty(),
                events = event,
                largeItems = largeItems,
                showEditSheet = showEditSheet,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                    bottom = padding.calculateBottomPadding(),
                ),
                modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            )
        }
    }
}

/** The week with the arrows to the one before and the one after. */
@Composable
private fun CalendarWeekHeader(
    weekStart: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val label = remember(weekStart) {
        val zone = ZoneId.systemDefault()
        DateUtils.formatDateRange(
            context,
            weekStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            weekStart.plusDays(6).atStartOfDay(zone).toInstant().toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("calendar-week-previous")) {
            Icon(
                painter = painterResource(R.drawable.arrow_back_24),
                contentDescription = stringResource(R.string.calendar_week_previous),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .testTag("calendar-week-range"),
        )
        IconButton(onClick = onNext, modifier = Modifier.testTag("calendar-week-next")) {
            Icon(
                painter = painterResource(R.drawable.arrow_forward_24),
                contentDescription = stringResource(R.string.calendar_week_next),
            )
        }
    }
}

/** The seven days of the week: weekday, date and the number of releases; the selected day is a pill, today's number is colored. */
@Composable
private fun CalendarWeekStrip(
    uiState: CalendarWeekUiState,
    rowsByDay: Map<LocalDate, List<CalendarRow>>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        uiState.days.forEach { date ->
            val selected = date == uiState.selectedDate
            val isToday = date == uiState.today
            val count = rowsByDay[date].orEmpty().size
            val countKnown = date in uiState.loadedDays
            val weekday = date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.getDefault())
            val description = "$weekday ${date.dayOfMonth}" + if (countKnown) ", $count" else ""
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(date) }
                    .semantics(mergeDescendants = true) {
                        this.selected = selected
                        contentDescription = description
                    }
                    .testTag("calendar-week-day-$date")
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = weekday,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.primary
                        selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    // Blank while the day is still arriving, so a count is never shown that is only half of the day.
                    text = if (countKnown) count.toString() else " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("calendar-week-count-$date"),
                )
            }
        }
    }
}

/** The releases of the selected day, as the compact rows of the list calendar or as the larger Discover rows. */
@Composable
fun CalendarWeekDayView(
    isLoggedIn: Boolean,
    snackbarManager: SnackbarManager,
    uiState: CalendarWeekUiState,
    rows: List<CalendarRow>,
    events: CalendarWeekEvent?,
    largeItems: Boolean,
    showEditSheet: MutableState<Boolean>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val navActionManager = LocalNavActionManager.current
    val blurAdult = LocalBlurAdult.current
    val haptic = LocalHapticFeedback.current

    if (showEditSheet.value && uiState.selectedItem != null) {
        EditMediaSheet(
            mediaDetails = uiState.selectedItem.basicMediaDetails,
            listEntry = uiState.selectedItem.mediaListEntry?.basicMediaListEntry,
            onEntryUpdated = { events?.onUpdateListEntry(it) },
            onDismissed = { showEditSheet.value = false },
        )
    }

    PullToRefreshBox(
        isRefreshing = uiState.fetchFromNetwork && uiState.isLoading,
        onRefresh = { events?.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("calendar-week-list"),
            contentPadding = contentPadding,
        ) {
            items(
                items = rows,
                key = { it.key },
                contentType = { if (it.releasePresentations.isEmpty()) "anilist-media" else "provider-event" },
            ) { row ->
                val item = row.media
                val title = item?.basicMediaDetails?.title?.userPreferred.orEmpty()
                    .ifBlank { row.providerFallbackTitle().orEmpty() }
                    .ifBlank { stringResource(R.string.release_provider_only) }
                val blur = blurAdult && item?.basicMediaDetails?.isAdult == true
                val onClick = {
                    (item?.id ?: row.releasePresentations.firstOrNull()?.mediaId)
                        ?.let(navActionManager::toMediaDetails)
                    Unit
                }
                val onLongClick = {
                    item?.let {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isLoggedIn) {
                            events?.selectItem(it)
                            showEditSheet.value = true
                        } else {
                            snackbarManager.showNotLoggedInSnackbar()
                        }
                    }
                    Unit
                }
                if (largeItems) {
                    MediaItemHorizontal(
                        title = title,
                        imageUrl = item?.coverImage?.large,
                        blurImage = blur,
                        subtitle1 = {
                            val authoritativeRows = row.releasePresentations.filter { it.isAuthoritative }
                            if (authoritativeRows.isNotEmpty()) {
                                ReleaseCalendarGroupScheduleText(presentations = authoritativeRows, fallback = {})
                            } else {
                                Text(
                                    text = calendarSubtitle(row),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        subtitle2 = {
                            val genres = item?.genres.orEmpty().filterNotNull().take(3)
                            val score = item?.averageScore
                            if (score != null || genres.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    score?.let { SmallScoreIndicator(score = it) }
                                    if (genres.isNotEmpty()) {
                                        Text(
                                            text = genres.joinToString(" · ") { it.genreTagLocalized() },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        },
                        status = item?.mediaListEntry?.basicMediaListEntry?.status,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    CalendarAiringHorizontalItem(
                        title = title,
                        subtitle = calendarSubtitle(row),
                        releasePresentations = row.releasePresentations,
                        blurImage = blur,
                        imageUrl = item?.coverImage?.large,
                        score = item?.averageScore,
                        status = item?.mediaListEntry?.basicMediaListEntry?.status,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            if (rows.isEmpty()) {
                if (uiState.selectedDayLoaded) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.calendar_week_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("calendar-week-empty"),
                            )
                        }
                    }
                } else {
                    items(count = 8, contentType = { "placeholder" }) {
                        if (largeItems) MediaItemHorizontalPlaceholder()
                        else CalendarAiringHorizontalItemPlaceholder(modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
}

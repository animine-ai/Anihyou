package com.axiel7.anihyou.feature.calendar.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.feature.calendar.CalendarDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * A touch strip at the right edge of the calendar list, between a gap at the top and one at the bottom. Holding or moving a
 * finger on it shows a dot for every day and a bubble with the date of the day under the finger; moving up and down jumps the
 * list to that day. There is no slider bar: while the list scrolls, only a short handle shows where it is, and it fades.
 */
@Composable
internal fun BoxScope.CalendarFastScroller(
    days: List<CalendarDay>,
    today: LocalDate,
    listState: LazyListState,
    topGap: Dp,
    bottomGap: Dp,
    onScrub: () -> Unit,
) {
    if (days.size < 2) return
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val starts = remember(days.map { it.rows.size }) { dayStartItemIndices(days.map { it.rows.size }) }
    var active by remember { mutableStateOf(false) }
    var touchedDay by remember { mutableIntStateOf(0) }
    var trackHeight by remember { mutableFloatStateOf(0f) }
    val currentDay by remember(starts) { derivedStateOf { dayOfItemIndex(starts, listState.firstVisibleItemIndex) } }
    val shownDay = (if (active) touchedDay else currentDay).coerceIn(days.indices)

    // The handle stays a moment after the list stopped, so it can be found and grabbed.
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) recentlyScrolled = true else {
                delay(HANDLE_LINGER_MILLIS)
                recentlyScrolled = false
            }
        }
    }
    val visible by animateFloatAsState(if (active || recentlyScrolled) 1f else 0f, tween(180), label = "scroller")
    val dots by animateFloatAsState(if (active) 1f else 0f, tween(120), label = "dots")

    val primary = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(top = topGap, bottom = bottomGap),
    ) {
        // Drawing only: it takes no touch, the strip below does.
        Canvas(Modifier.fillMaxSize().alpha(visible)) {
            val x = size.width - with(density) { 14.dp.toPx() }
            if (dots > 0f) days.forEachIndexed { index, day ->
                val y = (index + 0.5f) * size.height / days.size
                val isShown = index == shownDay
                drawCircle(
                    color = (if (isShown || day.date == today) primary else quiet).copy(alpha = if (isShown) 1f else 0.55f * dots),
                    radius = with(density) { (if (isShown) 5.dp else 2.5.dp).toPx() },
                    center = Offset(x, y),
                )
            } else {
                val handleHeight = with(density) { 44.dp.toPx() }
                val fraction = if (days.size > 1) shownDay.toFloat() / (days.size - 1) else 0f
                drawRoundRect(
                    color = quiet.copy(alpha = 0.7f),
                    topLeft = Offset(size.width - with(density) { 10.dp.toPx() }, fraction * (size.height - handleHeight)),
                    size = Size(with(density) { 4.dp.toPx() }, handleHeight),
                    cornerRadius = CornerRadius(with(density) { 2.dp.toPx() }),
                )
            }
        }
        if (active) {
            val centerY = with(density) { ((shownDay + 0.5f) * trackHeight / days.size).toDp() }
            Surface(
                shape = RoundedCornerShape(50),
                color = container,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-36).dp, y = centerY - 18.dp),
            ) {
                Text(
                    text = days[shownDay].date.bubbleLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (days[shownDay].date == today) primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(STRIP_WIDTH)
                .onSizeChanged { trackHeight = it.height.toFloat() }
                .pointerInput(days.size, starts) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        active = true
                        onScrub()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        fun select(y: Float) {
                            val index = dayIndexAt(y, trackHeight, days.size)
                            if (touchedDay >= 0 && index != touchedDay) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            touchedDay = index
                            listState.requestScrollToItem(starts[index])
                        }
                        touchedDay = -1
                        select(down.position.y)
                        drag(down.id) { change ->
                            change.consume()
                            select(change.position.y)
                        }
                        active = false
                    }
                },
        )
    }
}

private val STRIP_WIDTH = 44.dp
private const val HANDLE_LINGER_MILLIS = 1_200L

/** "Sa, 17.10.2026": the weekday and the date in the user's format. */
private fun LocalDate.bubbleLabel(): String =
    "${dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"

/** The index of the day under a finger [y] pixels down a track of [height] pixels that holds [days] equal parts. */
internal fun dayIndexAt(y: Float, height: Float, days: Int): Int {
    if (days <= 0 || height <= 0f) return 0
    return (y / height * days).toInt().coerceIn(0, days - 1)
}

/** The first list item of each day: the date header and the rows of the days before it come first. */
internal fun dayStartItemIndices(rowCounts: List<Int>): List<Int> {
    var next = 0
    return rowCounts.map { rows -> next.also { next += rows + 1 } }
}

/** The day that the list item [item] belongs to: the last day that starts at or before it. */
internal fun dayOfItemIndex(starts: List<Int>, item: Int): Int {
    if (starts.isEmpty()) return 0
    val found = starts.binarySearch(item)
    return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
}

package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.core.common.utils.DateUtils.timestampToTimeString
import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.ui.composables.media.releaseTrackLabel
import com.axiel7.anihyou.feature.calendar.composables.ReleaseMark
import com.axiel7.anihyou.feature.calendar.composables.releaseMark
import com.axiel7.anihyou.release.core.api.ReleaseCalendarUiSelection
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.Installment
import java.time.Instant
import java.time.LocalDate

/**
 * One entry of the calendar as the list tab shows it, for the surfaces that cannot use its screen (the widget). Which entries
 * there are, in which order and how they are marked is decided by the same code as in the tab, so a setting of the calendar
 * (list filter, AniList entries without a source match, simultaneous tracks) holds for every surface and none keeps its own
 * rules.
 */
data class CalendarEntry(
    val date: LocalDate,
    val key: String,
    val media: ExploreMedia?,
    val releases: List<ReleaseUiCalendarItem>,
    val airingEvent: CalendarAiringEvent?,
    /** The AniList title, else the name the source lists the series under; null if there is neither. */
    val title: String?,
    /** The extension's planned time has passed (the entry is dimmed). An AniList time alone never dims. */
    val dimmed: Boolean,
    /** The source lists the episode as released (the entry carries a check). */
    val checked: Boolean,
    val episode: Int?,
    /** "HH:mm" in the device's zone, of the source's time (else of AniList's). */
    val timeText: String?,
    /** "SUB", "DUB" or "SUB/DUB"; null for an AniList entry. */
    val trackLabel: String?,
)

/**
 * The entries of [fromDate] on, in the order of the calendar tab. [fromDate] is where the day ends that a surface does not
 * show any more: the widget starts at today, so yesterday is gone at midnight, while every entry of today stays, released or
 * not.
 */
fun calendarEntries(
    today: LocalDate,
    now: Instant,
    onMyList: Boolean?,
    showAniListExtras: Boolean,
    combineSimultaneousTracks: Boolean,
    airingByDate: Map<LocalDate, List<CalendarAiringEvent>>,
    media: Map<Int, ExploreMedia>,
    releaseRows: List<ReleaseUiCalendarItem>,
    fromDate: LocalDate = today,
): List<CalendarEntry> {
    val knownMedia = airingByDate.values.asSequence().flatten().map { it.media.id }.toSet()
    val state = CalendarUiState(
        onMyList = onMyList,
        showAniListExtras = showAniListExtras,
        combineSimultaneousTracks = combineSimultaneousTracks,
        today = today,
        weeklyAnime = airingByDate.toMutableMap(),
        extraMedia = media,
        releaseCalendarRows = releaseRows,
        providerRowsByDate = releaseRows.providerRowsByDate(fallbackDate = today),
        providerOnlyByDate = releaseRows.providerOnlyByDate(knownMediaIds = knownMedia, fallbackDate = today),
    )
    return state.presentationDays().filter { it.date >= fromDate }.flatMap { day ->
        day.rows.map { row ->
            val representative = ReleaseCalendarUiSelection.representative(row.releasePresentations)
                ?.takeIf { it.isAuthoritative }
            val mark = row.releaseMark(now)
            val airing = row.airingEvent
            CalendarEntry(
                date = day.date,
                key = row.key,
                media = row.media,
                releases = row.releasePresentations,
                airingEvent = airing,
                title = row.media?.basicMediaDetails?.title?.userPreferred?.takeIf { it.isNotBlank() }
                    ?: row.providerFallbackTitle(),
                dimmed = mark == ReleaseMark.THEORETICAL || mark == ReleaseMark.CONFIRMED_PAST,
                checked = mark == ReleaseMark.CONFIRMED || mark == ReleaseMark.CONFIRMED_PAST,
                episode = representative?.let { (it.installment as? Installment.Episode)?.number } ?: airing?.episode,
                timeText = representative?.forecastAt?.epochSecond?.timestampToTimeString()
                    ?: airing?.airingAt?.toLong()?.timestampToTimeString(),
                trackLabel = row.releasePresentations.takeIf { it.isNotEmpty() }
                    ?.mapNotNull { releaseTrackLabel(it.track) }?.distinct()?.sortedDescending()
                    ?.joinToString("/")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

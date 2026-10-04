package com.axiel7.anihyou.core.model.media

import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One AniList airing schedule, independent of the media's next future airing. */
data class CalendarAiringEvent(
    val scheduleId: Int,
    val episode: Int,
    val airingAt: Int,
    val media: ExploreMedia,
) {
    fun localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochSecond(airingAt.toLong()).atZone(zone).toLocalDate()
}

/** Repeated emissions/pages update the same schedule, while separate episodes retain their identities. */
fun Iterable<CalendarAiringEvent>.uniqueAiringEvents(): List<CalendarAiringEvent> =
    associateBy { it.scheduleId }.values.sortedWith(
        compareBy<CalendarAiringEvent> { it.airingAt }.thenBy { it.scheduleId },
    )

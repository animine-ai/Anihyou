package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.core.model.media.CalendarAiringEvent
import com.axiel7.anihyou.core.model.media.uniqueAiringEvents
import java.time.LocalDate
import java.time.ZoneId

/** Replace a refreshed day; merge overlapping pages by the actual upstream schedule ID. */
internal fun Map<LocalDate, List<CalendarAiringEvent>>.withAiringPage(
    requestedDate: LocalDate,
    replaceDay: Boolean,
    events: List<CalendarAiringEvent>,
    zone: ZoneId = ZoneId.systemDefault(),
): MutableMap<LocalDate, List<CalendarAiringEvent>> =
    (filterKeys { !replaceDay || it != requestedDate }.values.flatten() + events)
        .uniqueAiringEvents().groupBy { it.localDate(zone) }.toMutableMap()

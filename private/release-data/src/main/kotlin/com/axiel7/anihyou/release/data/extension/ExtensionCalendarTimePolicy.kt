package com.axiel7.anihyou.release.data.extension

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Converts already separated guest calendar fields; never parses a response body or route. */
internal object ExtensionCalendarTimePolicy {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun normalize(dateText: String?, timeText: String?, zone: ZoneId): Instant? {
        if (dateText == null || timeText == null || !dateText.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")) ||
            !timeText.matches(Regex("[0-9]{2}:[0-9]{2}"))) return null
        return runCatching {
            val local = LocalDateTime.of(LocalDate.parse(dateText, dateFormat), LocalTime.parse(timeText))
            // An impossible or ambiguous local time cannot silently pick a DST offset.
            val offset = zone.rules.getValidOffsets(local).singleOrNull() ?: return null
            local.toInstant(offset)
        }.getOrNull()
    }
}

package com.axiel7.anihyou.feature.calendar

import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import java.time.Instant
import java.time.LocalDate

// The release rows of the active source as the list calendar and the weekday tabs both read them.

internal fun List<ReleaseUiCalendarItem>.providerRowsByDate(
    fallbackDate: LocalDate,
): Map<LocalDate, List<ReleaseUiCalendarItem>> =
    asSequence()
        .filter { it.isAuthoritative }
        .groupBy { it.sourceDate ?: fallbackDate }
        .mapValues { (_, rows) -> rows.sortedForPresentation() }

internal fun List<ReleaseUiCalendarItem>.providerOnlyByDate(
    knownMediaIds: Set<Int>,
    fallbackDate: LocalDate,
): Map<LocalDate, List<ReleaseUiCalendarItem>> =
    asSequence()
        .filter { it.isAuthoritative && (it.mediaId == null || it.mediaId !in knownMediaIds) }
        .groupBy { it.sourceDate ?: fallbackDate }
        .mapValues { (_, rows) -> rows.sortedForPresentation() }

internal fun List<ReleaseUiCalendarItem>.sortedForPresentation(): List<ReleaseUiCalendarItem> =
    sortedWith(
        compareBy<ReleaseUiCalendarItem> { it.forecastAt ?: Instant.MAX }
            .thenBy { it.stream.stableKey }
            .thenBy { it.installment.stableKey }
            .thenByDescending { it.revision },
    )

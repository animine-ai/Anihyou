package com.axiel7.anihyou.core.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.axiel7.anihyou.core.model.base.Localizable
import com.axiel7.anihyou.core.resources.R

/** How the calendar looks. The data behind every style is the same. */
enum class CalendarStyle : Localizable {
    /** The days one below the other. */
    LIST,
    /** One tab per weekday, the posters in a grid. */
    TABS,
    /** A week strip with the number of releases per day, the day below it as list rows of the size of the list style. */
    WEEK,
    /** The same week strip, the day as the larger list rows of the Discover lists. */
    WEEK_LARGE;

    @get:StringRes
    val stringRes: Int
        get() = when (this) {
            LIST -> R.string.calendar_style_list
            TABS -> R.string.calendar_style_tabs
            WEEK -> R.string.calendar_style_week
            WEEK_LARGE -> R.string.calendar_style_week_large
        }

    @Composable
    override fun localized() = stringResource(stringRes)

    companion object {
        /**
         * The style the user chose; without a choice of its own, what the older two-way switch said (the grid became the
         * tabs, everything else the list).
         */
        fun from(stored: String?, legacy: ListStyle?): CalendarStyle =
            entries.firstOrNull { it.name == stored } ?: if (legacy == ListStyle.GRID) TABS else LIST
    }
}

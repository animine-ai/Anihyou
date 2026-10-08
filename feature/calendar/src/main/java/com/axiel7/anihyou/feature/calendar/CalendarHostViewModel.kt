package com.axiel7.anihyou.feature.calendar

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.ListPreferencesRepository
import com.axiel7.anihyou.core.model.CalendarStyle
import com.axiel7.anihyou.release.core.api.EmptyReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@Stable
class CalendarHostViewModel(
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val listPreferencesRepository: ListPreferencesRepository,
    private val releasePresentationRepository: ReleasePresentationRepository = EmptyReleasePresentationRepository,
): ViewModel() {

    val onMyList = defaultPreferencesRepository.calendarOnMyList

    val style = listPreferencesRepository.calendarStyle

    /** Whether AniList entries without a match in the active source stay in the calendar (only while a source supplies rows). */
    val showAniListExtras = defaultPreferencesRepository.calendarShowAniListExtras.map { it == true }

    /** The active release source supplies rows around today: it is then the main calendar and the menu offers the AniList extras. */
    val sourceIsMain = defaultPreferencesRepository.userId
        .flatMapLatest { accountId ->
            val today = LocalDate.now()
            releasePresentationRepository.observeCalendar(
                accountId = accountId?.toLong(),
                range = today.minusDays(14)..today.plusDays(14),
            )
        }
        .map { rows -> rows.any { it.isAuthoritative } }
        .distinctUntilChanged()

    fun onMyListChanged(value: Boolean?) = viewModelScope.launch {
        defaultPreferencesRepository.setCalendarOnMyList(value)
    }

    fun onShowAniListExtrasChanged(value: Boolean) {
        viewModelScope.launch {
            defaultPreferencesRepository.setCalendarShowAniListExtras(value)
        }
    }

    fun onChangeStyle(value: CalendarStyle) {
        viewModelScope.launch {
            listPreferencesRepository.setCalendarStyle(value)
        }
    }
}

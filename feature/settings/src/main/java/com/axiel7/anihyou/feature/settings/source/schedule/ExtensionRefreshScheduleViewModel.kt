package com.axiel7.anihyou.feature.settings.source.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the page changes: only the planned full-source slots. Soft freshness and hard limits are not options. */
interface ExtensionRefreshScheduleEvent {
    fun setInterval(interval: ExtensionRefreshInterval)
    fun setAnchor(minuteOfDay: Int)
    fun reset()
}

class ExtensionRefreshScheduleViewModel(
    private val repository: ExtensionRefreshScheduleRepository,
    private val scheduler: ExtensionReleaseRefreshScheduler,
) : ViewModel(), ExtensionRefreshScheduleEvent {
    val schedule: StateFlow<ExtensionRefreshSchedule> = repository.schedule

    override fun setInterval(interval: ExtensionRefreshInterval) = change { it.copy(interval = interval) }

    override fun setAnchor(minuteOfDay: Int) =
        change { it.copy(anchorMinuteOfDay = minuteOfDay.coerceIn(0, ExtensionRefreshSchedule.MINUTES_PER_DAY - 1)) }

    override fun reset() = change { ExtensionRefreshSchedule() }

    /** Persist first, then make sure the single future slot of the new schedule exists; the old one is retired. */
    private fun change(transform: (ExtensionRefreshSchedule) -> ExtensionRefreshSchedule) {
        viewModelScope.launch {
            val current = repository.schedule.value
            val next = transform(current)
            if (next == current) return@launch
            repository.update(next)
            scheduler.ensureSlotScheduled()
        }
    }
}

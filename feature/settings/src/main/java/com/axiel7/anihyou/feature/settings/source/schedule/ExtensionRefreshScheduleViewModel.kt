package com.axiel7.anihyou.feature.settings.source.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.release.core.api.ExtensionOpenCooldown
import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the page changes: the planned full-source slots and the pause after an app opening. The other soft freshness
 * windows and the hard limits are not options.
 */
interface ExtensionRefreshScheduleEvent {
    fun setInterval(interval: ExtensionRefreshInterval)
    fun setAnchor(minuteOfDay: Int)
    /** The pause after an app opening, in minutes; anything outside [ExtensionOpenCooldown.OPTIONS_MINUTES] is the default. */
    fun setOpenCooldown(minutes: Int) {}
    fun reset()
}

class ExtensionRefreshScheduleViewModel(
    private val repository: ExtensionRefreshScheduleRepository,
    private val scheduler: ExtensionReleaseRefreshScheduler,
) : ViewModel(), ExtensionRefreshScheduleEvent {
    val schedule: StateFlow<ExtensionRefreshSchedule> = repository.schedule
    val openCooldownMinutes: StateFlow<Int> = repository.openCooldownMinutes

    override fun setInterval(interval: ExtensionRefreshInterval) = change { it.copy(interval = interval) }

    override fun setAnchor(minuteOfDay: Int) =
        change { it.copy(anchorMinuteOfDay = minuteOfDay.coerceIn(0, ExtensionRefreshSchedule.MINUTES_PER_DAY - 1)) }

    override fun setOpenCooldown(minutes: Int) {
        viewModelScope.launch { repository.updateOpenCooldown(ExtensionOpenCooldown.normalize(minutes)) }
    }

    override fun reset() {
        change { ExtensionRefreshSchedule() }
        setOpenCooldown(ExtensionOpenCooldown.DEFAULT_MINUTES)
    }

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

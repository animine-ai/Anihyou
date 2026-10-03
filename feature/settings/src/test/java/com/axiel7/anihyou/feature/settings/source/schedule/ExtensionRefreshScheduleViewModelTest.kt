package com.axiel7.anihyou.feature.settings.source.schedule

import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionRefreshScheduleViewModelTest {
    private class Store : ExtensionRefreshScheduleRepository {
        val state = MutableStateFlow(ExtensionRefreshSchedule())
        val updates = mutableListOf<ExtensionRefreshSchedule>()
        override val schedule: StateFlow<ExtensionRefreshSchedule> = state
        override suspend fun update(schedule: ExtensionRefreshSchedule) { updates += schedule; state.value = schedule }
        override suspend fun lastSlotRun(): Instant? = null
        override suspend fun recordSlotRun(at: Instant) = Unit
    }

    private class Scheduler : ExtensionReleaseRefreshScheduler {
        var ensured = 0
        override fun scheduleDue() = Unit
        override fun ensureSlotScheduled() { ensured++ }
        override fun scheduleForeground() = Unit
        override fun scheduleNow() = Unit
        override fun scheduleDeferred(trigger: ExtensionRefreshTrigger, notBefore: Instant, chain: Int) = Unit
        override fun cancel() = Unit
    }

    private val store = Store()
    private val scheduler = Scheduler()

    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun defaultIsDailyAtOneAndNothingIsWrittenWithoutAChange() = runTest {
        val model = ExtensionRefreshScheduleViewModel(store, scheduler)
        assertEquals(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_24), model.schedule.value)
        model.setInterval(ExtensionRefreshInterval.HOURS_24)
        model.setAnchor(60)
        assertEquals(emptyList<ExtensionRefreshSchedule>(), store.updates)
        assertEquals(0, scheduler.ensured)
    }

    @Test fun aChangeIsPersistedAndTheSingleFutureSlotIsEnsuredOnce() = runTest {
        val model = ExtensionRefreshScheduleViewModel(store, scheduler)
        model.setInterval(ExtensionRefreshInterval.HOURS_6)
        assertEquals(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_6), store.state.value)
        assertEquals(1, scheduler.ensured)
        model.setAnchor(7 * 60 + 30)
        assertEquals(ExtensionRefreshSchedule(450, ExtensionRefreshInterval.HOURS_6), store.state.value)
        assertEquals(2, scheduler.ensured)
    }

    @Test fun anchorStaysInsideOneDayAndResetRestoresTheDefault() = runTest {
        val model = ExtensionRefreshScheduleViewModel(store, scheduler)
        model.setAnchor(5000)
        assertEquals(1439, store.state.value.anchorMinuteOfDay)
        model.setAnchor(-5)
        assertEquals(0, store.state.value.anchorMinuteOfDay)
        model.setInterval(ExtensionRefreshInterval.MINUTES_30)
        model.reset()
        assertEquals(ExtensionRefreshSchedule(), store.state.value)
    }
}

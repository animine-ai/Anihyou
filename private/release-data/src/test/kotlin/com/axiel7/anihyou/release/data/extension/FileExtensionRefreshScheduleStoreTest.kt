package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileExtensionRefreshScheduleStoreTest {
    private val directory: File = Files.createTempDirectory("ep07-refresh-schedule").toFile()

    @Test fun missingFileMeansHourlyAndATenMinutePause() = runBlocking {
        val store = FileExtensionRefreshScheduleStore(directory)
        assertEquals(ExtensionRefreshSchedule(), store.schedule.value)
        assertEquals(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_1), store.schedule.value)
        assertEquals(10, store.openCooldownMinutes.value)
        assertNull(store.lastSlotRun())
    }

    @Test fun theDailyDefaultOfEarlierBuildsFollowsTheNewDefaultButAChosenScheduleStays() = runBlocking {
        // Earlier builds wrote their own default into the file with the first slot.
        File(directory, "extension-refresh-schedule-v1.json").writeText(
            """{"anchorMinuteOfDay":60,"intervalMinutes":1440,"lastSlotRun":"2026-10-03T11:00:00Z"}""")
        val migrated = FileExtensionRefreshScheduleStore(directory)
        assertEquals(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_1), migrated.schedule.value)
        assertEquals(Instant.parse("2026-10-03T11:00:00Z"), migrated.lastSlotRun())
        // Once written by this build the version is in the file: a daily schedule is then the user's own choice.
        migrated.update(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_24))
        assertEquals(ExtensionRefreshSchedule(60, ExtensionRefreshInterval.HOURS_24),
            FileExtensionRefreshScheduleStore(directory).schedule.value)
        // A schedule of an earlier build that is not the old default was chosen by the user and stays.
        File(directory, "extension-refresh-schedule-v1.json").writeText(
            """{"anchorMinuteOfDay":150,"intervalMinutes":360,"lastSlotRun":""}""")
        assertEquals(ExtensionRefreshSchedule(150, ExtensionRefreshInterval.HOURS_6),
            FileExtensionRefreshScheduleStore(directory).schedule.value)
    }

    @Test fun thePauseAfterAnOpeningSurvivesARestartAndNeverLeavesTheChoices() = runBlocking {
        val first = FileExtensionRefreshScheduleStore(directory)
        first.updateOpenCooldown(30)
        assertEquals(30, FileExtensionRefreshScheduleStore(directory).openCooldownMinutes.value)
        first.updateOpenCooldown(7)
        assertEquals(10, first.openCooldownMinutes.value)
        assertEquals(10, FileExtensionRefreshScheduleStore(directory).openCooldownMinutes.value)
        // Changing the schedule keeps the pause.
        first.updateOpenCooldown(5)
        first.update(ExtensionRefreshSchedule(0, ExtensionRefreshInterval.HOURS_2))
        assertEquals(5, FileExtensionRefreshScheduleStore(directory).openCooldownMinutes.value)
    }

    @Test fun scheduleAndLastSlotSurviveARestart() = runBlocking {
        val first = FileExtensionRefreshScheduleStore(directory)
        first.update(ExtensionRefreshSchedule(3 * 60 + 30, ExtensionRefreshInterval.HOURS_6))
        first.recordSlotRun(Instant.parse("2026-10-03T11:00:00Z"))
        val second = FileExtensionRefreshScheduleStore(directory)
        assertEquals(ExtensionRefreshSchedule(210, ExtensionRefreshInterval.HOURS_6), second.schedule.value)
        assertEquals(Instant.parse("2026-10-03T11:00:00Z"), second.lastSlotRun())
    }

    @Test fun aLateWriterNeverMovesTheLastSlotBackwards() = runBlocking {
        val store = FileExtensionRefreshScheduleStore(directory)
        store.recordSlotRun(Instant.parse("2026-10-03T11:00:00Z"))
        store.recordSlotRun(Instant.parse("2026-10-03T05:00:00Z"))
        assertEquals(Instant.parse("2026-10-03T11:00:00Z"), FileExtensionRefreshScheduleStore(directory).lastSlotRun())
    }

    @Test fun anUnsupportedIntervalOrCorruptFileFallsBackToTheDefault() = runBlocking {
        File(directory, "extension-refresh-schedule-v1.json").writeText(
            """{"anchorMinuteOfDay":60,"intervalMinutes":300,"lastSlotRun":""}""")
        assertEquals(ExtensionRefreshSchedule(), FileExtensionRefreshScheduleStore(directory).schedule.value)
        File(directory, "extension-refresh-schedule-v1.json").writeText("{not json")
        assertEquals(ExtensionRefreshSchedule(), FileExtensionRefreshScheduleStore(directory).schedule.value)
    }
}

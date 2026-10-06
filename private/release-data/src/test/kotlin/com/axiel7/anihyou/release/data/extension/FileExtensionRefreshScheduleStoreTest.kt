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

    @Test fun missingFileMeansDailyAtOneAm() = runBlocking {
        val store = FileExtensionRefreshScheduleStore(directory)
        assertEquals(ExtensionRefreshSchedule(), store.schedule.value)
        assertNull(store.lastSlotRun())
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

package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.api.ExtensionRefreshInterval
import com.axiel7.anihyou.release.core.api.ExtensionRefreshSchedule
import com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The full-source slot schedule and the time the last slot ran. A missing or unreadable file means the
 * default (daily 01:00 device-local time); a bad value never widens the interval set.
 */
class FileExtensionRefreshScheduleStore(directory: File) : ExtensionRefreshScheduleRepository {
    private val file = File(directory, "extension-refresh-schedule-v1.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow(read().first)
    @Volatile private var lastSlot: Instant? = read().second

    override val schedule: StateFlow<ExtensionRefreshSchedule> = state.asStateFlow()

    override suspend fun update(schedule: ExtensionRefreshSchedule) {
        mutex.withLock {
            write(schedule, lastSlot)
            state.value = schedule
        }
    }

    override suspend fun lastSlotRun(): Instant? = lastSlot

    override suspend fun recordSlotRun(at: Instant) {
        mutex.withLock {
            // A late writer never moves the record backwards.
            val next = lastSlot?.let { if (it.isAfter(at)) it else at } ?: at
            write(state.value, next)
            lastSlot = next
        }
    }

    private fun read(): Pair<ExtensionRefreshSchedule, Instant?> = runCatching {
        if (!file.exists()) return@runCatching ExtensionRefreshSchedule() to null
        val json = JSONObject(file.readText())
        val interval = ExtensionRefreshInterval.fromMinutes(json.getInt("intervalMinutes"))
            ?: return@runCatching ExtensionRefreshSchedule() to null
        val anchor = json.getInt("anchorMinuteOfDay")
        ExtensionRefreshSchedule(anchor, interval) to json.optString("lastSlotRun").takeIf { it.isNotBlank() }
            ?.let { Instant.parse(it) }
    }.getOrElse { ExtensionRefreshSchedule() to null }

    private suspend fun write(schedule: ExtensionRefreshSchedule, lastSlotRun: Instant?) = withContext(Dispatchers.IO) {
        require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val next = File(file.parentFile, file.name + ".next")
        next.writeText(JSONObject().put("anchorMinuteOfDay", schedule.anchorMinuteOfDay)
            .put("intervalMinutes", schedule.interval.minutes)
            .put("lastSlotRun", lastSlotRun?.toString() ?: "").toString())
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

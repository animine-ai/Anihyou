package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.api.ExtensionOpenCooldown
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
 * The full-source slot schedule, the pause after an app opening and the time the last slot ran. A missing or
 * unreadable file means the defaults (hourly on the full hour, ten minutes); a bad value never widens the interval set.
 *
 * Earlier builds defaulted to daily at 01:00 and wrote that default into the file with the first slot. A file without
 * the defaults version that still holds exactly that old default was never chosen by the user, so it follows the new
 * default; any other stored schedule is the user's own and stays.
 */
class FileExtensionRefreshScheduleStore(directory: File) : ExtensionRefreshScheduleRepository {
    private val file = File(directory, "extension-refresh-schedule-v1.json")
    private val mutex = Mutex()
    private val initial = read()
    private val state = MutableStateFlow(initial.schedule)
    private val cooldown = MutableStateFlow(initial.cooldownMinutes)
    @Volatile private var lastSlot: Instant? = initial.lastSlot

    override val schedule: StateFlow<ExtensionRefreshSchedule> = state.asStateFlow()

    override val openCooldownMinutes: StateFlow<Int> = cooldown.asStateFlow()

    override suspend fun update(schedule: ExtensionRefreshSchedule) {
        mutex.withLock {
            write(schedule, lastSlot, cooldown.value)
            state.value = schedule
        }
    }

    override suspend fun updateOpenCooldown(minutes: Int) {
        val value = ExtensionOpenCooldown.normalize(minutes)
        mutex.withLock {
            write(state.value, lastSlot, value)
            cooldown.value = value
        }
    }

    override suspend fun lastSlotRun(): Instant? = lastSlot

    override suspend fun recordSlotRun(at: Instant) {
        mutex.withLock {
            // A late writer never moves the record backwards.
            val next = lastSlot?.let { if (it.isAfter(at)) it else at } ?: at
            write(state.value, next, cooldown.value)
            lastSlot = next
        }
    }

    private class Stored(val schedule: ExtensionRefreshSchedule, val lastSlot: Instant?, val cooldownMinutes: Int)

    private fun read(): Stored = runCatching {
        val defaults = Stored(ExtensionRefreshSchedule(), null, ExtensionOpenCooldown.DEFAULT_MINUTES)
        if (!file.exists()) return@runCatching defaults
        val json = JSONObject(file.readText())
        val cooldownMinutes = ExtensionOpenCooldown.normalize(json.optInt("openCooldownMinutes", 0).takeIf { it > 0 })
        val lastSlot = json.optString("lastSlotRun").takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
        val interval = ExtensionRefreshInterval.fromMinutes(json.getInt("intervalMinutes"))
            ?: return@runCatching Stored(defaults.schedule, null, cooldownMinutes)
        val anchor = json.getInt("anchorMinuteOfDay")
        val untouchedOldDefault = json.optInt("defaultsVersion", 1) < DEFAULTS_VERSION &&
            interval == ExtensionRefreshInterval.HOURS_24 && anchor == OLD_DEFAULT_ANCHOR_MINUTE
        Stored(if (untouchedOldDefault) defaults.schedule else ExtensionRefreshSchedule(anchor, interval), lastSlot, cooldownMinutes)
    }.getOrElse { Stored(ExtensionRefreshSchedule(), null, ExtensionOpenCooldown.DEFAULT_MINUTES) }

    private suspend fun write(schedule: ExtensionRefreshSchedule, lastSlotRun: Instant?, cooldownMinutes: Int) =
        withContext(Dispatchers.IO) {
            require(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            val next = File(file.parentFile, file.name + ".next")
            next.writeText(JSONObject().put("anchorMinuteOfDay", schedule.anchorMinuteOfDay)
                .put("intervalMinutes", schedule.interval.minutes)
                .put("openCooldownMinutes", cooldownMinutes)
                .put("defaultsVersion", DEFAULTS_VERSION)
                .put("lastSlotRun", lastSlotRun?.toString() ?: "").toString())
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

    private companion object {
        /** 2: the default is hourly and the file carries the pause after an app opening. */
        const val DEFAULTS_VERSION = 2
        const val OLD_DEFAULT_ANCHOR_MINUTE = 60
    }
}

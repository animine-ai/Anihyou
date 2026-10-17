package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.extension.SourceRole
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Why a product refresh runs. The trigger decides the data scope and the soft-freshness bypass. */
enum class ExtensionRefreshTrigger {
    /** The planned full source slot: every role the source is granted. */
    SCHEDULED_SLOT,
    /** The app came to the real foreground: list roles, at most one automatic run per hour. */
    FOREGROUND,
    /** A fresh process start: the same scope and the same hourly dedupe as FOREGROUND. */
    PROCESS_START,
    /** An explicit user action: every granted role; may bypass soft freshness, never hard limits. */
    MANUAL,
    /** Pull to refresh on the postponements page: only the postponement role, past soft freshness, never hard limits. */
    MANUAL_POSTPONEMENTS,
    /**
     * The planned time of a release has come: only the list of released episodes is asked, past soft freshness and never
     * past the hard limits. Not a user action and not an automatic app trigger, so it has no hourly window.
     */
    DUE_RELEASE;

    val automatic: Boolean get() = this == FOREGROUND || this == PROCESS_START
    val bypassesSoftFreshness: Boolean get() = this == MANUAL || this == MANUAL_POSTPONEMENTS || this == DUE_RELEASE
    /** The only role this trigger asks for; null means every role its scope allows. */
    val onlyRole: com.axiel7.anihyou.release.core.extension.SourceRole?
        get() = when (this) {
            MANUAL_POSTPONEMENTS -> com.axiel7.anihyou.release.core.extension.SourceRole.POSTPONEMENT
            DUE_RELEASE -> com.axiel7.anihyou.release.core.extension.SourceRole.RECENT
            else -> null
        }
}

/** Product release-data work, separate from repository metadata and the debug canary. */
interface ExtensionReleaseRefreshScheduler {
    /**
     * Process start or a selection/package change. Makes sure one future full-source slot exists, retires
     * the old hourly periodic work of earlier builds and runs the process-start check.
     */
    fun scheduleDue()
    /**
     * Idempotent. Exactly one pending future slot for the stored schedule, plus at most one catch-up run
     * when a slot was missed. Never cancels a running worker, so a slot may call it for its successor.
     */
    fun ensureSlotScheduled()
    /** A real foreground transition. Shares one unique work with process start, so both never duplicate. */
    fun scheduleForeground()
    /** Explicit user action. */
    fun scheduleNow()
    /** Pull to refresh on the postponements page: the postponement role of the active source only. */
    fun schedulePostponements() {}
    /** One bounded follow-up at the time a hard limit lapses; [chain] counts consecutive deferrals. */
    fun scheduleDeferred(trigger: ExtensionRefreshTrigger, notBefore: Instant, chain: Int)
    /**
     * Targeted checks of the released-episodes list at [times] (the planned release times of followed series and a few
     * minutes after them). Idempotent: a time that is already planned is not planned again. Only the nearest times count.
     */
    fun scheduleDueReleases(times: List<Instant>) {}
    fun cancel()
}

/**
 * How long the calendar, the released episodes and the postponements rest after an app opening has asked for them. The
 * app asks every time it opens once this pause is over, so a quick second opening does not ask the source again.
 */
object ExtensionOpenCooldown {
    const val DEFAULT_MINUTES = 10
    val OPTIONS_MINUTES: List<Int> = listOf(2, 5, 10, 15, 30, 60)

    fun normalize(minutes: Int?): Int = minutes?.takeIf { it in OPTIONS_MINUTES } ?: DEFAULT_MINUTES
}

/** The persisted full-source schedule; default hourly on the full hour. */
interface ExtensionRefreshScheduleRepository {
    val schedule: kotlinx.coroutines.flow.StateFlow<ExtensionRefreshSchedule>
    suspend fun update(schedule: ExtensionRefreshSchedule)
    /** The pause after an app opening, in minutes; one of [ExtensionOpenCooldown.OPTIONS_MINUTES]. */
    val openCooldownMinutes: kotlinx.coroutines.flow.StateFlow<Int>
        get() = kotlinx.coroutines.flow.MutableStateFlow(ExtensionOpenCooldown.DEFAULT_MINUTES)
    suspend fun updateOpenCooldown(minutes: Int) {}
    /** When a planned slot last ran to an end (committed, fresh skip or deferral), or null if never. */
    suspend fun lastSlotRun(): Instant?
    suspend fun recordSlotRun(at: Instant)
}

fun interface ExtensionReleaseRefreshCoordinator {
    suspend fun refresh(workId: String, trigger: ExtensionRefreshTrigger): ShadowRefreshOutcome
}

/**
 * Soft success freshness per role. A role whose last real network success is younger than its window
 * is not requested again by an automatic trigger. Hard limits (429, host, request, byte, trust) are
 * never part of this policy.
 */
data class ExtensionFreshnessPolicy(
    val calendar: Duration = Duration.ofHours(1),
    val postponement: Duration = Duration.ofHours(1),
    val recent: Duration = Duration.ofMinutes(15),
    val direct: Duration = Duration.ofMinutes(15),
    /** At most one automatic (foreground or process start) run per this window. */
    val automaticTrigger: Duration = Duration.ofHours(1),
) {
    init {
        require(listOf(calendar, postponement, recent, direct, automaticTrigger).all { !it.isNegative && !it.isZero })
    }

    /** The same policy with the pause after an app opening: the three roles an opening asks for share one pause. */
    fun withOpenCooldown(pause: Duration): ExtensionFreshnessPolicy =
        copy(calendar = pause, postponement = pause, recent = pause, automaticTrigger = pause)

    fun forRole(role: SourceRole): Duration = when (role) {
        SourceRole.CALENDAR -> calendar
        SourceRole.POSTPONEMENT -> postponement
        SourceRole.RECENT -> recent
        SourceRole.DIRECT -> direct
    }

    /** The data roles an automatic app trigger may request; DIRECT checks belong to slots and due releases. */
    companion object { val AUTOMATIC_ROLES: Set<SourceRole> = setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT) }
}

enum class ExtensionRefreshInterval(val minutes: Int) {
    MINUTES_30(30), HOURS_1(60), HOURS_2(120), HOURS_3(180), HOURS_4(240),
    HOURS_6(360), HOURS_8(480), HOURS_12(720), HOURS_24(1440);

    companion object {
        /** Five hours is deliberately absent: it does not repeat on a fixed daily grid. */
        fun fromMinutes(value: Int): ExtensionRefreshInterval? = entries.firstOrNull { it.minutes == value }
    }
}

/**
 * The planned full-source slots as device-local wall-clock times: `anchor + k * interval` inside a day.
 * Every supported interval divides 24 hours, so the grid repeats daily. A late run never moves the anchor,
 * and missed slots are not queued: only the next slot after "now" is ever scheduled.
 */
data class ExtensionRefreshSchedule(
    val anchorMinuteOfDay: Int = DEFAULT_ANCHOR_MINUTE,
    val interval: ExtensionRefreshInterval = ExtensionRefreshInterval.HOURS_1,
) {
    init { require(anchorMinuteOfDay in 0 until MINUTES_PER_DAY) }

    private fun slotMinutes(): List<Int> {
        val step = interval.minutes
        val first = anchorMinuteOfDay % step
        return (first until MINUTES_PER_DAY step step).toList()
    }

    private fun instants(date: LocalDate, zone: ZoneId): List<Instant> = slotMinutes().map { minute ->
        // A wall-clock time inside a spring-forward gap resolves to the first valid time after the gap.
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone).toInstant()
    }

    fun nextSlotAfter(now: Instant, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate()
        return (-1L..2L).flatMap { instants(today.plusDays(it), zone) }.filter { it.isAfter(now) }.min()
    }

    /** The most recent slot at or before [now]; the missed-slot check compares a last full run against it. */
    fun lastSlotAtOrBefore(now: Instant, zone: ZoneId): Instant {
        val today = now.atZone(zone).toLocalDate()
        return (-2L..1L).flatMap { instants(today.plusDays(it), zone) }.filter { !it.isAfter(now) }.max()
    }

    companion object {
        const val MINUTES_PER_DAY = 1440
        /** 01:00 device-local time; with the hourly default only the grid matters, which is the full hour. */
        const val DEFAULT_ANCHOR_MINUTE = 60
    }
}

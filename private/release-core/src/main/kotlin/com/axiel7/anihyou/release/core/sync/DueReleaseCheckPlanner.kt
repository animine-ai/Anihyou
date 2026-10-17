package com.axiel7.anihyou.release.core.sync

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * When the released-episodes list is asked on purpose: at the planned time of a release and a few times after it, because
 * a source lists an episode a little after its time and the list is the only thing that proves the release.
 *
 * Pure planning. It decides nothing about the release itself; a planned check that finds the release confirmed is a
 * normal refresh whose result is already known, and a check that finds nothing is simply the next retry.
 */
object DueReleaseCheckPlanner {
    /** Minutes after the planned time. The first check is right after it, the later ones wait for a source that lags. */
    val RETRY_OFFSETS: List<Duration> = listOf(1L, 6L, 16L, 31L).map(Duration::ofMinutes)

    /** Planned releases further ahead than this are planned with a later refresh; the plan is renewed after every refresh. */
    val HORIZON: Duration = Duration.ofHours(36)

    /** How long after its planned time an unconfirmed release is still looked for on purpose. */
    val GIVE_UP_AFTER: Duration = Duration.ofMinutes(45)

    const val MAX_CHECKS = 24

    /**
     * @param plannedReleases the planned times of releases that are not confirmed yet
     * @return the instants to check, nearest first, each at a whole minute, at most [MAX_CHECKS], none in the past
     */
    fun plan(plannedReleases: Collection<Instant>, now: Instant, horizon: Duration = HORIZON): List<Instant> =
        plannedReleases.asSequence()
            .filter { it.isAfter(now.minus(GIVE_UP_AFTER)) && !it.isAfter(now.plus(horizon)) }
            .flatMap { planned -> RETRY_OFFSETS.asSequence().map { planned.plus(it) } }
            .map { it.truncatedTo(ChronoUnit.MINUTES) }
            .filter { it.isAfter(now) }
            .distinct()
            .sorted()
            .take(MAX_CHECKS)
            .toList()
}

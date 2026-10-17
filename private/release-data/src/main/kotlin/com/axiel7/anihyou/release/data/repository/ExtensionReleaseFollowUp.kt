package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.api.ReleaseOutboxScheduler
import com.axiel7.anihyou.release.core.api.ReleasePreferencesRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.core.sync.DueReleaseCheckPlanner
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * What follows a refresh of the active release source: the episodes the source now lists as released become release
 * notifications, and the planned times of the episodes it does not list yet become targeted checks of that list.
 *
 * Nothing here decides whether an episode is released. The presentation says so: only a confirmed row of the source (the
 * released-episodes list or the page of the episode) is announced as available, never a planned time.
 */
class ExtensionReleaseFollowUp(
    private val presentation: ReleasePresentationRepository,
    private val preferences: ReleasePreferencesRepository,
    private val accountContext: ReleaseAccountContextProvider,
    private val outbox: RoomNotificationOutboxStore,
    private val outboxScheduler: ReleaseOutboxScheduler,
    private val refreshScheduler: ExtensionReleaseRefreshScheduler,
    /** Whether a usable release source is active; without one there is nothing to announce or to check. */
    private val sourceActive: suspend () -> Boolean,
    /** The AniList title of a media if it is cached; the source's own series name stands in otherwise. */
    private val titleOf: suspend (mediaId: Int) -> String? = { null },
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Never throws except for cancellation: a follow-up must not change the outcome of the refresh it follows. */
    suspend fun run() {
        try {
            runInternal()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.w("followup", failure) { "follow-up failed: ${failure.javaClass.simpleName}" }
        }
    }

    private suspend fun runInternal() {
        if (!sourceActive()) {
            AppLog.i("followup") { "skipped: no usable release source is active" }
            return
        }
        val now = clock.instant()
        val prefs = preferences.releasePreferences.first()
        val accountId = accountContext.current(emptySet()).accountId
        if (accountId == null) {
            AppLog.i("followup") { "skipped: no AniList account" }
            return
        }
        val today = now.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        val items = presentation.currentCalendar(accountId, today.minusDays(2)..today.plusDays(2))
        val track = prefs.preferredTrack.toLanguageTrack()
        val episodes = items.filter { item ->
            item.isAuthoritative && item.mediaId != null && item.track == track && item.forecastAt != null &&
                // Only whole episodes: a film, a special or a fractional installment has no episode number to compare.
                item.installment.let { it is Installment.Episode && it.fraction == null }
        }
        val progress = accountContext.current(episodes.mapNotNull { it.mediaId }.toSet()).progressByMediaId
        AppLog.i("followup") {
            "start: items=${items.size} preferredTrack=$track episodes=${episodes.size} confirmed=${episodes.count { it.confirmed }} " +
                "progressKnown=${progress.size} notificationsEnabled=${prefs.notificationsEnabled}"
        }
        if (prefs.notificationsEnabled) announce(accountId, episodes.filter { it.confirmed }, progress, now)
        planChecks(episodes.filter { !it.confirmed }, progress, now)
    }

    private suspend fun announce(accountId: Long, confirmed: List<ReleaseUiCalendarItem>, progress: Map<Int, Int>, now: Instant) {
        var added = 0
        for (item in confirmed) {
            val mediaId = checkNotNull(item.mediaId)
            val episode = item.installment as Installment.Episode
            val released = checkNotNull(item.forecastAt)
            val known = progress[mediaId]
            val reason = when {
                released.isBefore(now.minus(ANNOUNCE_WINDOW)) -> "released more than $ANNOUNCE_WINDOW ago"
                released.isAfter(now.plus(CLOCK_SKEW)) -> "released in the future"
                known == null -> "AniList progress unknown (not on the list or not cached)"
                episode.number <= known -> "already watched (progress $known)"
                else -> null
            }
            if (reason != null) {
                AppLog.d("followup") { "no notification media=$mediaId episode=${episode.number}: $reason" }
                continue
            }
            val candidate = ReleaseNotificationCandidate(
                accountId = accountId,
                mediaId = mediaId,
                identity = SourceIdentity(item.stream, item.installment),
                evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
                observedAt = now,
                displayTitle = titleOf(mediaId) ?: sourceSeriesTitle(item),
            )
            val isNew = outbox.enqueue(candidate)
            AppLog.i("followup") {
                "notification media=$mediaId episode=${episode.number} track=${item.track} released=$released new=$isNew"
            }
            if (isNew) added++
        }
        if (added > 0) outboxScheduler.schedule()
    }

    private fun planChecks(planned: List<ReleaseUiCalendarItem>, progress: Map<Int, Int>, now: Instant) {
        // Followed means on the AniList list with an episode still ahead of the user's progress. If no progress is known at
        // all, every bound planned release is followed rather than none: a check costs one request of the hard limits.
        val followed = if (progress.isEmpty()) planned else planned.filter { item ->
            val known = progress[item.mediaId] ?: return@filter false
            (item.installment as Installment.Episode).number > known
        }
        val times = DueReleaseCheckPlanner.plan(followed.mapNotNull { it.forecastAt }, now)
        AppLog.i("followup") {
            "due checks: planned=${planned.size} followed=${followed.size} checks=${times.size} first=${times.firstOrNull()}"
        }
        if (times.isNotEmpty()) refreshScheduler.scheduleDueReleases(times)
    }

    /** "/anime/stream/some-series" as "Some Series": all a source row can say when AniList has no title cached. */
    private fun sourceSeriesTitle(item: ReleaseUiCalendarItem): String? =
        item.stream.stableSeriesKey.value.removePrefix("/anime/stream/").takeIf { it.isNotBlank() }
            ?.split('-', '_', ' ')?.filter { it.isNotBlank() }
            ?.joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }

    private fun ReleaseGermanTrack.toLanguageTrack(): LanguageTrack = when (this) {
        ReleaseGermanTrack.DE_SUB -> LanguageTrack.DE_SUB
        ReleaseGermanTrack.DE_DUB -> LanguageTrack.DE_DUB
    }

    private companion object {
        /** An episode the source lists long after its release is history, not news. */
        val ANNOUNCE_WINDOW: Duration = Duration.ofHours(12)
        val CLOCK_SKEW: Duration = Duration.ofMinutes(10)
    }
}

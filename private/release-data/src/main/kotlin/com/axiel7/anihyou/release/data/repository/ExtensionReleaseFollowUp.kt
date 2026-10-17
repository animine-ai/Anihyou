package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ListEntryInfo
import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseNotificationScope
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
    /**
     * What AniList says about these media on the user's list (progress, status, hidden flag), asked for in one request;
     * media that are not on the list are simply absent. The local cache cannot tell "not on the list" from "not loaded
     * yet" and can be out of date, so the answer comes from AniList itself and is kept for [ENTRY_TTL].
     */
    private val fetchEntries: suspend (mediaIds: Set<Int>) -> Map<Int, ListEntryInfo> = { emptyMap() },
    /** Which anime may be announced; read at every run, so a change of the setting applies to the next refresh. */
    private val scope: () -> ReleaseNotificationScope = { ReleaseNotificationScope() },
    /** Hands titles and episode counts of the followed anime to the numbering, so Behind can be decided by the source. */
    private val learnNumbering: suspend (List<com.axiel7.anihyou.release.core.api.DetailMappingRequest>) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
) {
    private val entryCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<ListEntryInfo?, Instant>>()

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
        val items = presentation.currentCalendar(accountId, today.minusDays(NUMBERING_DAYS)..today.plusDays(NUMBERING_DAYS))
        val track = prefs.preferredTrack.toLanguageTrack()
        val wholeEpisodes = items.filter { item ->
            item.isAuthoritative && item.mediaId != null && item.track == track && item.forecastAt != null &&
                // Only whole episodes: a film, a special or a fractional installment has no episode number to compare.
                item.installment.let { it is Installment.Episode && it.fraction == null }
        }
        // Notifications and checks concern the days around now; the wider range only teaches the numbering.
        val windowStart = now.minus(Duration.ofDays(2))
        val windowEnd = now.plus(Duration.ofDays(2))
        val episodes = wholeEpisodes.filter { item -> item.forecastAt!!.let { !it.isBefore(windowStart) && !it.isAfter(windowEnd) } }
        val chosen = scope()
        val entries = entriesFor(wholeEpisodes.mapNotNull { it.mediaId }.toSet(), now)
        AppLog.i("followup") {
            "start: items=${items.size} preferredTrack=$track episodes=${episodes.size} media=${wholeEpisodes.mapNotNull { it.mediaId }.toSet().size} confirmed=${episodes.count { it.confirmed }} " +
                "onList=${entries?.values?.count { it != null }} notificationsEnabled=${prefs.notificationsEnabled} scope=${chosen.mode}"
        }
        if (prefs.notificationsEnabled) announce(accountId, episodes.filter { it.confirmed }, entries, chosen, now)
        planChecks(episodes.filter { !it.confirmed }, entries, chosen, now)
    }

    /**
     * The list entry of each media (null: not on the list), or null as a whole if AniList could not be asked. Answers are
     * kept for [ENTRY_TTL], so the checks after a planned time do not each cost a request.
     */
    private suspend fun entriesFor(mediaIds: Set<Int>, now: Instant): Map<Int, ListEntryInfo?>? {
        if (mediaIds.isEmpty()) return emptyMap()
        val known = HashMap<Int, ListEntryInfo?>()
        val ask = mutableListOf<Int>()
        for (id in mediaIds) {
            val cached = entryCache[id]
            if (cached != null && Duration.between(cached.second, now) < ENTRY_TTL) known[id] = cached.first else ask += id
        }
        if (ask.isNotEmpty()) {
            val fetched = try {
                ask.chunked(ENTRY_REQUEST_SIZE).fold(emptyMap<Int, ListEntryInfo>()) { all, chunk -> all + fetchEntries(chunk.toSet()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w("followup", failure) { "asking AniList for ${ask.size} list entries failed" }
                return null
            }
            for (id in ask) {
                val entry = fetched[id]
                entryCache[id] = entry to now
                known[id] = entry
            }
            val requests = fetched.values.mapNotNull { it.numbering }
            if (requests.isNotEmpty()) try {
                learnNumbering(requests)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w("followup", failure) { "learning the numbering of ${requests.size} anime failed" }
            }
            AppLog.i("followup") { "asked AniList: ${ask.size} media, ${fetched.size} on the list" }
        }
        return known
    }

    private suspend fun announce(
        accountId: Long, confirmed: List<ReleaseUiCalendarItem>, entries: Map<Int, ListEntryInfo?>?,
        chosen: ReleaseNotificationScope, now: Instant,
    ) {
        if (entries == null) {
            // Without AniList's answer nothing is announced, never a guess; the next check asks again.
            if (confirmed.isNotEmpty()) AppLog.w("followup") { "no notification: the AniList list could not be read, tried again at the next check" }
            return
        }
        var added = 0
        for (item in confirmed) {
            val mediaId = checkNotNull(item.mediaId)
            val episode = item.installment as Installment.Episode
            val released = checkNotNull(item.forecastAt)
            val entry = entries[mediaId]
            val reason = when {
                released.isBefore(now.minus(ANNOUNCE_WINDOW)) -> "released more than $ANNOUNCE_WINDOW ago"
                released.isAfter(now.plus(CLOCK_SKEW)) -> "released in the future"
                !chosen.allows(mediaId, entry) -> if (chosen.overrides[mediaId] == false) "switched off for this anime"
                    else if (entry == null) "not on the AniList list (not in the chosen scope)"
                    else "list status ${entry.status ?: "unknown"}${if (entry.hiddenFromStatusLists) " (hidden)" else ""} is not in the chosen scope"
                entry != null && episode.number <= entry.progress -> "already watched (progress ${entry.progress})"
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
                // A failed title lookup must not cost the notification: the series name of the source stands in.
                displayTitle = (try { titleOf(mediaId) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }) ?: sourceSeriesTitle(item),
            )
            val isNew = outbox.enqueue(candidate)
            AppLog.i("followup") {
                "notification media=$mediaId episode=${episode.number} track=${item.track} released=$released new=$isNew"
            }
            if (isNew) added++
        }
        if (added > 0) outboxScheduler.schedule()
    }

    private fun planChecks(
        planned: List<ReleaseUiCalendarItem>, entries: Map<Int, ListEntryInfo?>?, chosen: ReleaseNotificationScope, now: Instant,
    ) {
        // Followed means inside the chosen scope with an episode still ahead of the user's progress. If AniList could not be
        // asked, every bound planned release is followed rather than none: a check costs one request of the hard limits.
        val followed = if (entries == null) planned else planned.filter { item ->
            val entry = entries[item.mediaId]
            chosen.allows(item.mediaId ?: return@filter false, entry) && (entry == null || (item.installment as Installment.Episode).number > entry.progress)
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
        /** AniList's answer about a list entry is reused this long; the checks after a planned time come within minutes. */
        val ENTRY_TTL: Duration = Duration.ofMinutes(10)
        const val ENTRY_REQUEST_SIZE = 50
        /** Days either side of today whose anime teach the numbering; notifications use two. */
        const val NUMBERING_DAYS = 10L
    }
}

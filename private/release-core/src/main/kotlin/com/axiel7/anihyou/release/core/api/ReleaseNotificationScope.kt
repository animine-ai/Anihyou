package com.axiel7.anihyou.release.core.api

import kotlinx.coroutines.flow.StateFlow

/** Which anime may produce a release notification. */
enum class NotificationScopeMode {
    /** Anime the user is watching (or rewatching) on AniList. The default. */
    WATCHING,
    /** Every anime of the source, whether it is on the AniList list or not. */
    ALL,
    /** Exactly the list statuses the user picks, optionally also anime that are not on the list. */
    CUSTOM,
}

/** The AniList list statuses, as the user can pick them. */
enum class NotificationListStatus { CURRENT, REPEATING, PLANNING, PAUSED, COMPLETED, DROPPED }

/** What AniList says about one anime on the user's list; progress counts the episodes already watched. */
data class ListEntryInfo(
    val progress: Int,
    val status: NotificationListStatus?,
    /** AniList's "hidden from status lists" flag: such entries are left out of the status lists by default. */
    val hiddenFromStatusLists: Boolean = false,
    /** Titles, format and episode count of the anime as AniList gave them with the entry: what numbering needs. */
    val numbering: DetailMappingRequest? = null,
    /** The title and the cover of the anime as the same answer carried them: what a notification shows, without a request of its own. */
    val title: String? = null,
    val coverUrl: String? = null,
)

/**
 * The user's choice of what is announced. The default is the anime being watched, nothing else.
 * [includeHidden] adds the entries AniList hides from the status lists; [mode] ALL announces everything.
 */
data class ReleaseNotificationScope(
    val mode: NotificationScopeMode = NotificationScopeMode.WATCHING,
    val statuses: Set<NotificationListStatus> = WATCHING_STATUSES,
    val includeNotOnList: Boolean = false,
    val includeHidden: Boolean = false,
    /** AniList's own "episode aired" notifications stay off while the release notifications of the source are on. */
    val alsoAniListAiring: Boolean = false,
    /** Single anime the user decided on: true is always announced, false never; everything else follows [mode]. */
    val overrides: Map<Int, Boolean> = emptyMap(),
) {
    /** [entry] is null for an anime that is not on the user's list. A decision about this very anime comes first. */
    fun allows(mediaId: Int, entry: ListEntryInfo?): Boolean = overrides[mediaId] ?: allowsByMode(entry)

    private fun allowsByMode(entry: ListEntryInfo?): Boolean = when (mode) {
        NotificationScopeMode.ALL -> true
        NotificationScopeMode.WATCHING ->
            entry != null && entry.status in WATCHING_STATUSES && (includeHidden || !entry.hiddenFromStatusLists)
        NotificationScopeMode.CUSTOM ->
            if (entry == null) includeNotOnList
            else entry.status in statuses && (includeHidden || !entry.hiddenFromStatusLists)
    }

    companion object {
        val WATCHING_STATUSES: Set<NotificationListStatus> =
            setOf(NotificationListStatus.CURRENT, NotificationListStatus.REPEATING)
    }
}

interface ReleaseNotificationScopeRepository {
    val scope: StateFlow<ReleaseNotificationScope>
    suspend fun update(transform: (ReleaseNotificationScope) -> ReleaseNotificationScope)
}

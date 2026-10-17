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
) {
    /** [entry] is null for an anime that is not on the user's list. */
    fun allows(entry: ListEntryInfo?): Boolean = when (mode) {
        NotificationScopeMode.ALL -> true
        NotificationScopeMode.WATCHING ->
            entry != null && entry.status in WATCHING_STATUSES && (includeHidden || !entry.hiddenFromStatusLists)
        NotificationScopeMode.CUSTOM ->
            if (entry == null) includeNotOnList
            else entry.status in statuses && (includeHidden || !entry.hiddenFromStatusLists)
    }

    /** Whether anime that are not on the list can be announced at all. */
    val allowsNotOnList: Boolean
        get() = mode == NotificationScopeMode.ALL || mode == NotificationScopeMode.CUSTOM && includeNotOnList

    companion object {
        val WATCHING_STATUSES: Set<NotificationListStatus> =
            setOf(NotificationListStatus.CURRENT, NotificationListStatus.REPEATING)
    }
}

interface ReleaseNotificationScopeRepository {
    val scope: StateFlow<ReleaseNotificationScope>
    suspend fun update(transform: (ReleaseNotificationScope) -> ReleaseNotificationScope)
}

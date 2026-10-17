package com.axiel7.anihyou.feature.settings.source.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.release.core.api.ReleaseNotificationScopeRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.matching.SearchTitleFolding
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class NotificationAnimeRow(val mediaId: Int, val title: String, val coverUrl: String?)

data class NotificationAnimeState(
    val rows: List<NotificationAnimeRow> = emptyList(),
    /** The user's decisions: true always announced, false never; a media without an entry follows the scope. */
    val overrides: Map<Int, Boolean> = emptyMap(),
    val loading: Boolean = true,
    val query: String = "",
)

interface NotificationAnimeEvent {
    fun search(text: String)
    /** [decision] null returns the anime to the general choice. */
    fun decide(mediaId: Int, decision: Boolean?)
}

/** The anime of the source's calendar (two weeks either side) with the user's own decision for each. */
class NotificationAnimeViewModel(
    private val presentation: ReleasePresentationRepository,
    private val preferences: DefaultPreferencesRepository,
    private val mediaRepository: MediaRepository,
    private val scopes: ReleaseNotificationScopeRepository,
) : ViewModel(), NotificationAnimeEvent {
    private val loaded = MutableStateFlow<List<NotificationAnimeRow>?>(null)
    private val query = MutableStateFlow("")

    val state = combine(loaded, scopes.scope, query) { rows, scope, text ->
        NotificationAnimeState(
            rows = rows.orEmpty().filter { SearchTitleFolding.matches(text, it.title) },
            overrides = scope.overrides,
            loading = rows == null,
            query = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationAnimeState())

    init {
        viewModelScope.launch {
            try {
                val userId = preferences.userId.first()?.toLong()
                val today = LocalDate.now()
                val calendarIds = presentation.currentCalendar(userId, today.minusDays(14)..today.plusDays(14))
                    .mapNotNull { it.mediaId }
                val ids = (calendarIds + scopes.scope.value.overrides.keys).distinct()
                val rows = mutableListOf<NotificationAnimeRow>()
                for (chunk in ids.chunked(50)) {
                    val result = mediaRepository.getMediaByIdsPage(chunk, page = 1, perPage = 50)
                        .first { it !is PagedResult.Loading }
                    if (result is PagedResult.Success) {
                        result.list.forEach { media ->
                            rows += NotificationAnimeRow(
                                media.id, media.basicMediaDetails.title?.userPreferred ?: "AniList #${media.id}",
                                media.coverImage?.large,
                            )
                        }
                    } else AppLog.w("ui") { "titles of ${chunk.size} anime for the notification list unavailable" }
                }
                loaded.value = rows.distinctBy { it.mediaId }.sortedBy { it.title.lowercase() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w("ui", failure) { "notification anime list failed" }
                loaded.value = emptyList()
            }
        }
    }

    override fun search(text: String) { query.value = text.take(128) }

    override fun decide(mediaId: Int, decision: Boolean?) {
        viewModelScope.launch {
            scopes.update { scope ->
                scope.copy(overrides = if (decision == null) scope.overrides - mediaId else scope.overrides + (mediaId to decision))
            }
        }
    }
}

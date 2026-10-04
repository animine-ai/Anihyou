package com.axiel7.anihyou.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.api.ExtensionPostponementPresentationRepository
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.usableExtension
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class PostponementMediaMetadata(val title: String?, val cover: String?, val adult: Boolean)

data class PostponementsUiState(
    val notices: List<ExtensionPostponementNotice> = emptyList(),
    val observedAt: Instant? = null,
    val metadata: Map<Int, PostponementMediaMetadata> = emptyMap(),
    val isRefreshing: Boolean = false,
    val query: String = "",
)

class PostponementsViewModel(
    presentationRepository: ExtensionPostponementPresentationRepository,
    productPolicyRepository: ExtensionProductPolicyRepository,
    private val mediaRepository: MediaRepository,
    sources: ExtensionSourceRepository,
    private val refreshScheduler: com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler? = null,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val query = MutableStateFlow("")

    fun search(text: String) { query.value = text.take(128) }
    private val metadata = MutableStateFlow<Map<Int, PostponementMediaMetadata>>(emptyMap())
    private val notices = combine(
        presentationRepository.presentation,
        productPolicyRepository.policy,
        sources.sources,
    ) { snapshot, policy, catalog ->
        val source = snapshot.source
        if (source != null && source == policy.activeReleaseSource &&
            catalog.usableExtension(source) != null
        ) {
            PostponementsUiState(
                notices = snapshot.notices.filter {
                    it.track == com.axiel7.anihyou.release.core.extension.ObservationTrack.UNKNOWN ||
                        it.track.name in policy.preferencesFor(source).enabledTracks
                },
                observedAt = snapshot.observedAt,
            )
        } else {
            PostponementsUiState()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PostponementsUiState(),
    )

    val uiState = combine(notices, metadata, refreshing, query) { state, details, busy, text ->
        val needle = text.trim().lowercase()
        state.copy(
            notices = if (needle.isEmpty()) state.notices else state.notices.filter { notice ->
                notice.title.lowercase().contains(needle) ||
                    notice.mediaId?.let(details::get)?.title?.lowercase()?.contains(needle) == true
            },
            metadata = details, isRefreshing = busy, query = text,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PostponementsUiState())

    /** Pull to refresh: asks the active source for its postponement notices only, through the extension. */
    fun refresh() {
        if (refreshing.value) return
        AppLog.i("ui") { "user: refresh postponements (scheduler present=${refreshScheduler != null})" }
        refreshing.value = true
        refreshScheduler?.schedulePostponements()
        viewModelScope.launch {
            val before = notices.value.observedAt
            // The run is background work: the indicator ends with the new snapshot, or after a bounded wait.
            kotlinx.coroutines.withTimeoutOrNull(REFRESH_WAIT_MILLIS) {
                notices.first { it.observedAt != before }
            }
            refreshing.value = false
        }
    }

    init {
        viewModelScope.launch {
            notices.map { state -> state.notices.mapNotNull { it.mediaId }.toSet() }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    // Existing AniList repository/cache supplies display metadata only.
                    // Mapping changes cancel obsolete requests; metadata cannot grant a binding.
                    val permits = Semaphore(4)
                    coroutineScope {
                        ids.filterNot(metadata.value::containsKey).map { id ->
                            async {
                                permits.withPermit {
                                    val result = mediaRepository.getMediaDetails(id)
                                        .catch { error ->
                                            if (error is CancellationException) throw error
                                            emit(DataResult.Error("Metadata unavailable"))
                                        }
                                        .first { it !is DataResult.Loading }
                                    if (result is DataResult.Success) result.data?.let { media ->
                                        metadata.value = metadata.value + (id to PostponementMediaMetadata(
                                            title = media.basicMediaDetails.title?.userPreferred,
                                            cover = media.coverImage?.large,
                                            adult = media.basicMediaDetails.isAdult == true,
                                        ))
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
        }
    }
}

private const val REFRESH_WAIT_MILLIS = 30_000L

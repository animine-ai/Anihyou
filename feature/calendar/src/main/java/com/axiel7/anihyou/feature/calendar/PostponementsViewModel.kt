package com.axiel7.anihyou.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.core.base.DataResult
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
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
)

class PostponementsViewModel(
    presentationRepository: ExtensionPostponementPresentationRepository,
    productPolicyRepository: ExtensionProductPolicyRepository,
    private val mediaRepository: MediaRepository,
    sources: ExtensionSourceRepository,
) : ViewModel() {
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

    val uiState = combine(notices, metadata) { state, details -> state.copy(metadata = details) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PostponementsUiState())

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

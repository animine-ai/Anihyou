package com.axiel7.anihyou.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
import com.axiel7.anihyou.release.core.api.ExtensionPostponementPresentationRepository
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PostponementsUiState(
    val notices: List<ExtensionPostponementNotice> = emptyList(),
    val observedAt: Instant? = null,
)

class PostponementsViewModel(
    presentationRepository: ExtensionPostponementPresentationRepository,
    productPolicyRepository: ExtensionProductPolicyRepository,
) : ViewModel() {
    val uiState = combine(
        presentationRepository.snapshot,
        productPolicyRepository.policy,
    ) { snapshot, policy ->
        if (snapshot.source != null && snapshot.source == policy.activeReleaseSource) {
            PostponementsUiState(
                notices = snapshot.notices,
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
}

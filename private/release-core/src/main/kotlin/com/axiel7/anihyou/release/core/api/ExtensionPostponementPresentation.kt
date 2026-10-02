package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.extension.ObservationScheduleMarker
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Presentation-only postponement notice from the selected release extension.
 *
 * A notice does not grant release authority. [mediaId] is populated only when
 * the provider identity can be bound through an already active exact/high
 * external mapping; otherwise the raw provider title remains useful display
 * text but the UI must not invent a catalogue link.
 */
data class ExtensionPostponementNotice(
    val title: String,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installmentNumber: String?,
    val track: ObservationTrack,
    val marker: ObservationScheduleMarker,
    val rawText: String?,
    val providerSeriesKey: String?,
    val mediaId: Int?,
) {
    init {
        require(title.isNotBlank() && title.length <= 1024)
        require(sourceSeason == null || sourceSeason >= 0)
        require(navigationSeason == null || navigationSeason >= 0)
        require(installmentNumber == null || installmentNumber.length <= 32)
        require(rawText == null || rawText.length <= 2048)
        require(providerSeriesKey == null || providerSeriesKey.length in 1..128)
        require(mediaId == null || mediaId > 0)
        require(marker != ObservationScheduleMarker.NONE && marker != ObservationScheduleMarker.UNKNOWN)
    }
}

data class ExtensionPostponementSnapshot(
    val source: ExtensionSelectionKey? = null,
    val releaseGeneration: Long = 0,
    val packageDigest: String? = null,
    val packageGeneration: Long? = null,
    val observedAt: Instant? = null,
    val notices: List<ExtensionPostponementNotice> = emptyList(),
) {
    init {
        require(releaseGeneration >= 0)
        require(packageGeneration == null || packageGeneration >= 0)
        require(packageDigest == null || packageDigest.matches(Regex("[0-9a-f]{64}")))
        require(notices.size <= 512)
    }
}

interface ExtensionPostponementPresentationRepository {
    val snapshot: StateFlow<ExtensionPostponementSnapshot>
}

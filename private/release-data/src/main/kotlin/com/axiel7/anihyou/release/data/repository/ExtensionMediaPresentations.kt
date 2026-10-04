package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiFreshness
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CanonicalReleaseState
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper

/**
 * Extension First for per-media consumers (Home, anime lists, details, explore, season). It folds the accepted rows of
 * exactly one active source into one presentation per mapped media and stream, from the same rows and the same
 * bindings the calendar presents, so every entry point shows the same facts:
 *
 * - confirmed means RELEASED (the source's own recent-list evidence); a planned or calendar time never confirms;
 * - the next installment is the earliest planned one after the confirmed state, never a row in conflict;
 * - a row without an effective binding to exactly one AniList media is not presented (the consumer keeps AniList);
 * - the user's progress and the pending count are not part of the rows: consumers derive them from the AniList entry.
 *
 * Source episode numbers are used as they are. An episode offset (split cour, absolute numbering) is part of the
 * navigation coordinates only and is not applied here, which is the same rule the calendar follows.
 */
internal fun List<CanonicalReleaseProjectionEntity>.toExtensionMediaPresentations(
    mappings: List<ExternalMappingEntity>,
    mediaIds: Set<Int>,
    preferences: ExtensionPreferences,
): Map<Int, List<ReleaseUiPresentation>> {
    if (mediaIds.isEmpty()) return emptyMap()
    val enabledTracks = preferences.enabledTracks - "UNKNOWN"
    val rows = mapNotNull { row ->
        val state = runCatching { ReleaseReconciliationMapper.state(row) }.getOrNull() ?: return@mapNotNull null
        val identity = CanonicalReleaseIdentity.decode(row.projectionKey) ?: return@mapNotNull null
        if (identity.track.name !in enabledTracks) return@mapNotNull null
        if (identity.installment is Installment.Special) return@mapNotNull null
        val media = mappedAniListId(identity, state.navigationSeasons, mappings) ?: return@mapNotNull null
        if (media !in mediaIds) return@mapNotNull null
        MediaRow(media, identity, state)
    }
    val trackOrder = preferences.preferredTrackOrder
    return rows
        .groupBy { StreamGroup(it.media, it.identity.seriesPath, it.identity.sourceSeason, it.identity.track, kindOf(it.identity)) }
        .mapNotNull { (group, items) -> present(group, items) }
        .groupBy { requireNotNull(it.mediaId) }
        .mapValues { (_, presentations) ->
            presentations.sortedWith(
                compareBy<ReleaseUiPresentation> { trackOrder.indexOf(it.stream.languageTrack.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.stream.stableKey },
            )
        }
}

private data class MediaRow(val media: Int, val identity: CanonicalReleaseIdentity, val state: CanonicalReleaseState)

private data class StreamGroup(
    val media: Int,
    val seriesPath: String,
    val sourceSeason: Int?,
    val track: LanguageTrack,
    val kind: ReleaseKind,
)

private fun kindOf(identity: CanonicalReleaseIdentity): ReleaseKind =
    if (identity.installment is Installment.Film) ReleaseKind.MOVIE else ReleaseKind.EPISODE

private fun wholeEpisode(identity: CanonicalReleaseIdentity): Int? = identity.installment.wholeEpisodeNumber

private fun present(group: StreamGroup, items: List<MediaRow>): ReleaseUiPresentation? {
    val released = items.filter { it.state.underlyingPhase == ReleasePhase.RELEASED }
    val confirmedThrough = released.mapNotNull { wholeEpisode(it.identity) }.maxOrNull()
    val stream = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey(group.seriesPath),
        releaseKind = group.kind,
        sourceSeason = group.sourceSeason,
        languageTrack = group.track,
    )
    val planned = items.filter {
        it.state.underlyingPhase in PLANNED_PHASES && it.state.phase != ReleasePhase.CONFLICT &&
            it.state.forecastAt != null &&
            (confirmedThrough == null || wholeEpisode(it.identity).let { episode -> episode == null || episode > confirmedThrough })
    }
    val next = planned.minWithOrNull(
        compareBy<MediaRow> { wholeEpisode(it.identity) ?: Int.MAX_VALUE }
            .thenBy { it.state.forecastAt }
            .thenBy { it.identity.key },
    )
    if (released.isEmpty() && next == null) {
        // Rows exist but none is usable (all in conflict): an explicit, non-authoritative state, so the consumer keeps AniList.
        return ReleaseUiPresentation(
            mediaId = group.media, stream = stream, authority = ReleaseUiAuthority.AMBIGUOUS,
            confirmedThroughEpisode = null, confirmedInstallments = emptyList(), confirmedPending = 0,
            nextExpectedInstallment = null, nextForecast = null, freshness = ReleaseUiFreshness.UNKNOWN,
            sourceRoot = SOURCE_ROOT + group.seriesPath, revision = items.maxOf { it.state.revision },
        )
    }
    val nextForecast = next?.let { row ->
        val at = requireNotNull(row.state.forecastAt)
        Forecast(
            identity = SourceIdentity(stream, row.identity.installment),
            forecastAt = at,
            sourceDate = at.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate(),
            sourceTime = null,
            sourceZone = ReleaseSourceTimePolicy.ANI_WORLD_ZONE,
            approximate = row.state.underlyingPhase == ReleasePhase.PREDICTED,
            observedAt = row.state.latestCompletedAt ?: at,
        )
    }
    return ReleaseUiPresentation(
        mediaId = group.media,
        stream = stream,
        authority = ReleaseUiAuthority.VALID,
        confirmedThroughEpisode = confirmedThrough,
        confirmedInstallments = released.map { it.identity.installment }.distinctBy { it.stableKey }
            .sortedBy { it.wholeEpisodeNumber ?: Int.MAX_VALUE },
        // The rows carry no account progress; the consumer derives the pending count from its AniList entry.
        confirmedPending = 0,
        nextExpectedInstallment = next?.identity?.installment,
        nextForecast = nextForecast,
        freshness = ReleaseUiFreshness.UNKNOWN,
        sourceRoot = SOURCE_ROOT + group.seriesPath,
        revision = items.maxOf { it.state.revision },
    )
}

private val PLANNED_PHASES = setOf(ReleasePhase.PREDICTED, ReleasePhase.EXPECTED, ReleasePhase.CONFIRMED)
private const val SOURCE_ROOT = "https://aniworld.to"

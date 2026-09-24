package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseState
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity

object ReleaseReducer {
    fun reduce(
        previous: ReleaseState?,
        snapshot: ReleaseSnapshot,
        accountProgress: Int,
        revision: Long? = null,
    ): ReductionResult {
        require(accountProgress >= 0) { "account progress must be non-negative" }

        if (previous != null && previous.stream != snapshot.stream) {
            return reject(previous, accountProgress, "release stream identity changed")
        }

        val incompatible = (snapshot.confirmations.map { it.identity } + snapshot.forecasts.map { it.identity })
            .firstOrNull { !isInstallmentCompatible(snapshot.stream, it.installment) }
        if (incompatible != null) {
            return reject(
                previous ?: emptyState(snapshot),
                accountProgress,
                "installment ${incompatible.installment.stableKey} conflicts with ${snapshot.stream.releaseKind.name}",
            )
        }

        val base = previous ?: emptyState(snapshot)
        val confirmations = mergeConfirmations(base.confirmations, snapshot.confirmations)
        val forecasts = mergeForecasts(base.forecasts, snapshot.forecasts)
        val mapping = chooseMapping(base.mapping, snapshot.mapping)
        val freshness = preserveLastGoodFreshness(base.freshness, snapshot.freshness)
        val diagnostics = (base.diagnostics + snapshot.freshness.diagnostic.orEmpty())
            .filter { it.isNotBlank() }
            .distinct()
        val nextRevision = revision ?: (base.revision + 1L)
        val state = base.copy(
            confirmations = confirmations,
            forecasts = forecasts,
            freshness = freshness,
            mapping = mapping,
            sourcePresent = snapshot.sourcePresent,
            sourceRoot = snapshot.sourceRoot ?: base.sourceRoot,
            diagnostics = diagnostics,
            revision = nextRevision,
        )
        return ReductionResult.Applied(
            state = state,
            projection = ReleaseProjector.project(state, accountProgress),
        )
    }

    private fun emptyState(snapshot: ReleaseSnapshot): ReleaseState = ReleaseState(
        stream = snapshot.stream,
        freshness = snapshot.freshness,
        mapping = snapshot.mapping,
        sourcePresent = snapshot.sourcePresent,
        sourceRoot = snapshot.sourceRoot,
    )

    private fun reject(
        previous: ReleaseState,
        accountProgress: Int,
        reason: String,
    ): ReductionResult.Rejected {
        val state = previous.copy(
            freshness = previous.freshness.copy(
                status = FreshnessStatus.ERROR,
                diagnostic = reason,
            ),
            diagnostics = (previous.diagnostics + reason).distinct(),
        )
        return ReductionResult.Rejected(
            state = state,
            projection = ReleaseProjector.project(state, accountProgress),
            reason = reason,
        )
    }

    private fun mergeConfirmations(
        previous: List<Confirmation>,
        incoming: List<Confirmation>,
    ): List<Confirmation> = (previous + incoming)
        .distinctBy { Triple(it.identity.stableKey, it.evidenceKind, it.confirmedObservedAt) }
        .sortedWith(compareBy<Confirmation> { it.confirmedObservedAt }.thenBy { it.identity.stableKey })

    private fun mergeForecasts(
        previous: List<com.axiel7.anihyou.release.core.model.Forecast>,
        incoming: List<com.axiel7.anihyou.release.core.model.Forecast>,
    ): List<com.axiel7.anihyou.release.core.model.Forecast> {
        return (previous + incoming)
            .groupBy { it.identity.stableKey }
            .values
            .mapNotNull { entries ->
                entries.maxWithOrNull(
                    compareBy<com.axiel7.anihyou.release.core.model.Forecast> { it.observedAt }
                        .thenBy { it.forecastAt },
                )
            }
            .sortedWith(compareBy<com.axiel7.anihyou.release.core.model.Forecast> { it.observedAt }.thenBy { it.identity.stableKey })
    }

    private fun chooseMapping(
        previous: ReleaseMapping?,
        incoming: ReleaseMapping?,
    ): ReleaseMapping? = when {
        previous?.origin == MappingOrigin.MANUAL && incoming?.origin != MappingOrigin.MANUAL -> previous
        incoming != null -> incoming
        else -> previous
    }

    private fun preserveLastGoodFreshness(previous: Freshness, incoming: Freshness): Freshness {
        val failure = incoming.status == FreshnessStatus.ERROR || incoming.status == FreshnessStatus.STALE
        if (!failure) return incoming
        return incoming.copy(
            lastSuccessAt = incoming.lastSuccessAt ?: previous.lastSuccessAt,
            parserVersion = incoming.parserVersion ?: previous.parserVersion,
            sourceHash = incoming.sourceHash ?: previous.sourceHash,
        )
    }

    private fun isInstallmentCompatible(stream: ReleaseStreamKey, installment: Installment): Boolean = when (stream.releaseKind) {
        com.axiel7.anihyou.release.core.model.ReleaseKind.EPISODE -> installment is Installment.Episode
        com.axiel7.anihyou.release.core.model.ReleaseKind.MOVIE -> installment is Installment.Film
        com.axiel7.anihyou.release.core.model.ReleaseKind.SPECIAL,
        com.axiel7.anihyou.release.core.model.ReleaseKind.OVA,
        com.axiel7.anihyou.release.core.model.ReleaseKind.ONA -> installment is Installment.Special
    }
}

sealed interface ReductionResult {
    val state: ReleaseState
    val projection: MediaReleaseProjection

    data class Applied(
        override val state: ReleaseState,
        override val projection: MediaReleaseProjection,
    ) : ReductionResult

    data class Rejected(
        override val state: ReleaseState,
        override val projection: MediaReleaseProjection,
        val reason: String,
    ) : ReductionResult
}

object ReleaseProjector {
    fun project(state: ReleaseState, accountProgress: Int): MediaReleaseProjection {
        require(accountProgress >= 0) { "account progress must be non-negative" }
        val confirmedThrough = state.confirmations
            .mapNotNull { it.identity.installment.wholeEpisodeNumber }
            .maxOrNull()
        val candidates = state.forecasts.filter { forecast ->
            val episode = forecast.identity.installment.wholeEpisodeNumber
            confirmedThrough == null || episode == null || episode > confirmedThrough
        }
        val latestObservation = candidates.maxOfOrNull { it.observedAt }
        val nextForecast = candidates
            .filter { latestObservation == null || it.observedAt == latestObservation }
            .minWithOrNull(
                compareBy<com.axiel7.anihyou.release.core.model.Forecast> {
                    it.identity.installment.wholeEpisodeNumber ?: Int.MAX_VALUE
                }.thenBy { it.forecastAt }.thenBy { it.identity.stableKey },
            )
        val authority = authorityFor(state)
        val pending = if (authority == AuthorityStatus.VALID && confirmedThrough != null) {
            (confirmedThrough - accountProgress).coerceAtLeast(0)
        } else {
            0
        }
        return MediaReleaseProjection(
            mediaId = state.mapping?.mediaId,
            stream = state.stream,
            authority = authority,
            confirmedThroughEpisode = confirmedThrough,
            confirmedInstallments = state.confirmations.map { it.identity.installment }.distinctBy { it.stableKey },
            nextForecast = nextForecast,
            pendingCount = pending,
            freshness = state.freshness,
            mapping = state.mapping,
            sourceRoot = state.sourceRoot,
            revision = state.revision,
            diagnostics = state.diagnostics,
        )
    }

    private fun authorityFor(state: ReleaseState): AuthorityStatus {
        if (state.freshness.status == FreshnessStatus.DISABLED) return AuthorityStatus.DISABLED
        if (state.freshness.status == FreshnessStatus.ERROR) return AuthorityStatus.ERROR
        if (!state.sourcePresent) return AuthorityStatus.STALE
        if (state.freshness.status == FreshnessStatus.STALE || state.freshness.status == FreshnessStatus.UNKNOWN) {
            return AuthorityStatus.STALE
        }
        return when (state.mapping?.confidence) {
            MappingConfidence.EXACT, MappingConfidence.HIGH -> AuthorityStatus.VALID
            MappingConfidence.AMBIGUOUS -> AuthorityStatus.AMBIGUOUS
            MappingConfidence.NONE, null -> AuthorityStatus.UNMAPPED
        }
    }
}

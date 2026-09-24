package com.axiel7.anihyou.release.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@JvmInline
value class ProviderId(val value: String) {
    init { require(value.isNotBlank()) { "provider id must not be blank" } }
}

@JvmInline
value class SourceSeriesKey(val value: String) {
    init { require(value.isNotBlank()) { "source series key must not be blank" } }
}

enum class LanguageTrack {
    DE_SUB,
    DE_DUB,
}

enum class ReleaseKind {
    EPISODE,
    MOVIE,
    SPECIAL,
    OVA,
    ONA,
}

sealed interface Installment {
    val stableKey: String
    val wholeEpisodeNumber: Int?

    data class Episode(
        val number: Int,
        val fraction: Int? = null,
    ) : Installment {
        init {
            require(number >= 0) { "episode number must be non-negative" }
            require(fraction == null || fraction in 1..99) { "episode fraction must be between 1 and 99" }
        }

        override val stableKey: String =
            if (fraction == null) "episode:$number" else "episode:$number.$fraction"
        override val wholeEpisodeNumber: Int? = if (fraction == null) number else null
    }

    data class Film(
        val number: Int? = null,
    ) : Installment {
        init { require(number == null || number >= 0) { "film number must be non-negative" } }

        override val stableKey: String = "film:${number ?: "unnumbered"}"
        override val wholeEpisodeNumber: Int? = null
    }

    data class Special(
        val number: Int? = null,
    ) : Installment {
        init { require(number == null || number >= 0) { "special number must be non-negative" } }

        override val stableKey: String = "special:${number ?: "unnumbered"}"
        override val wholeEpisodeNumber: Int? = null
    }
}

data class ReleaseStreamKey(
    val providerId: ProviderId,
    val stableSeriesKey: SourceSeriesKey,
    val releaseKind: ReleaseKind,
    val sourceSeason: Int?,
    val languageTrack: LanguageTrack,
) {
    init { require(sourceSeason == null || sourceSeason >= 0) { "source season must be non-negative" } }

    val stableKey: String = listOf(
        providerId.value,
        stableSeriesKey.value,
        releaseKind.name,
        sourceSeason?.toString() ?: "unknown-season",
        languageTrack.name,
    ).joinToString("/")
}

data class SourceIdentity(
    val stream: ReleaseStreamKey,
    val installment: Installment,
) {
    val stableKey: String = "${stream.stableKey}/${installment.stableKey}"
}

enum class ConfirmationEvidenceKind {
    RECENT_LIST_EXPLICIT_MARKER,
    CURRENT_PAGE_EXPLICIT_MARKER,
    MANUAL_CONFIRMATION,
}

data class Confirmation(
    val identity: SourceIdentity,
    val evidenceKind: ConfirmationEvidenceKind,
    val confirmedObservedAt: Instant,
)

data class Forecast(
    val identity: SourceIdentity,
    val forecastAt: Instant,
    val sourceDate: LocalDate?,
    val sourceTime: String?,
    val sourceZone: ZoneId?,
    val approximate: Boolean,
    val observedAt: Instant,
)

enum class FreshnessStatus {
    UNKNOWN,
    FRESH,
    STALE,
    ERROR,
    DISABLED,
}

data class Freshness(
    val status: FreshnessStatus,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val observedAt: Instant?,
    val parserVersion: String?,
    val sourceHash: String?,
    val diagnostic: String? = null,
) {
    companion object {
        fun unknown(): Freshness = Freshness(
            status = FreshnessStatus.UNKNOWN,
            lastAttemptAt = null,
            lastSuccessAt = null,
            observedAt = null,
            parserVersion = null,
            sourceHash = null,
        )
    }
}

enum class MappingConfidence {
    EXACT,
    HIGH,
    AMBIGUOUS,
    NONE,
}

enum class MappingOrigin {
    AUTO,
    MANUAL,
}

data class ReleaseMapping(
    val mediaId: Int,
    val confidence: MappingConfidence,
    val score: Double?,
    val runnerUpMargin: Double?,
    val evidence: String,
    val matcherVersion: String,
    val origin: MappingOrigin,
) {
    init {
        require(mediaId > 0) { "media id must be positive" }
        require(evidence.isNotBlank()) { "mapping evidence must not be blank" }
        require(matcherVersion.isNotBlank()) { "matcher version must not be blank" }
    }
}

enum class AuthorityStatus {
    VALID,
    AMBIGUOUS,
    UNMAPPED,
    STALE,
    ERROR,
    DISABLED,
}

data class ReleaseSnapshot(
    val stream: ReleaseStreamKey,
    val confirmations: List<Confirmation>,
    val forecasts: List<Forecast>,
    val freshness: Freshness,
    val mapping: ReleaseMapping?,
    val sourcePresent: Boolean = true,
    val sourceRoot: String? = null,
    val observedAt: Instant,
) {
    init {
        require(confirmations.all { it.identity.stream == stream }) {
            "all confirmations must belong to the snapshot stream"
        }
        require(forecasts.all { it.identity.stream == stream }) {
            "all forecasts must belong to the snapshot stream"
        }
    }
}

data class ReleaseState(
    val stream: ReleaseStreamKey,
    val confirmations: List<Confirmation> = emptyList(),
    val forecasts: List<Forecast> = emptyList(),
    val freshness: Freshness = Freshness.unknown(),
    val mapping: ReleaseMapping? = null,
    val sourcePresent: Boolean = true,
    val sourceRoot: String? = null,
    val diagnostics: List<String> = emptyList(),
    val revision: Long = 0L,
)

data class MediaReleaseProjection(
    val mediaId: Int?,
    val stream: ReleaseStreamKey,
    val authority: AuthorityStatus,
    val confirmedThroughEpisode: Int?,
    val confirmedInstallments: List<Installment>,
    val nextForecast: Forecast?,
    val pendingCount: Int,
    val freshness: Freshness,
    val mapping: ReleaseMapping?,
    val sourceRoot: String?,
    val revision: Long,
    val diagnostics: List<String> = emptyList(),
)

data class CalendarReleaseProjection(
    val mediaId: Int?,
    val stream: ReleaseStreamKey,
    val installment: Installment,
    val forecastAt: Instant?,
    val confirmed: Boolean,
    val authority: AuthorityStatus,
    val revision: Long,
    val sourceDate: LocalDate? = null,
)

data class AniListFallbackPresentation(
    val releasedEpisode: Int?,
    val nextAiringEpisode: Int?,
    val nextAiringAt: Instant?,
)

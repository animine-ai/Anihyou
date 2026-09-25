package com.axiel7.anihyou.release.core.model

import java.net.URI
import java.time.Instant

/**
 * A source role, not a generic source vote.
 *
 * Only the two positive AniWorld roles may establish release authority. All other
 * roles are metadata, mapping, correction or prediction inputs.
 */
enum class ReleaseSourceType(
    val isAniWorld: Boolean,
    val isReleaseAuthoritativeSource: Boolean,
) {
    ANIWORLD_CALENDAR(true, false),
    ANIWORLD_RECENT(true, true),
    ANIWORLD_POSTPONEMENT(true, false),
    ANIWORLD_DIRECT_PAGE(true, true),
    ANILIST_METADATA(false, false),
    ANILIST_ACCOUNT(false, false),
    ANILIST_IDENTIFIER_FALLBACK(false, false),
    MALSYNC_MAPPING(false, false),
    HISTORICAL_PREDICTION(false, false),
}

enum class ReleaseEvidenceType {
    FORECAST,
    CONFIRMATION,
    CORRECTION,
    VERIFICATION,
    PREDICTION,
    METADATA,
    ACCOUNT,
    MAPPING,
}

enum class ReleasePhase {
    UNKNOWN,
    PREDICTED,
    EXPECTED,
    CONFIRMED,
    RELEASED,
    MISSING,
    CONFLICT,
}

enum class ScheduleCondition {
    UNKNOWN,
    ON_SCHEDULE,
    EARLY,
    DELAYED,
    HIATUS,
}

enum class ReleaseAuthority {
    NONE,
    ANIWORLD,
}

enum class SourceHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    BLOCKED,
    DISABLED,
}

/**
 * Confidence is deliberately a vector. A high source score cannot compensate for
 * missing identity, installment or language evidence.
 */
data class ConfidenceVector(
    val source: Double,
    val identity: Double,
    val installment: Double,
    val languageTrack: Double,
    val timing: Double,
) {
    init {
        listOf(source, identity, installment, languageTrack, timing).forEach {
            require(it.isFinite() && it in 0.0..1.0) {
                "confidence components must be finite values between 0 and 1"
            }
        }
    }

    /**
     * The weakest component is the effective confidence. This is fail-closed.
     */
    val overall: Double
        get() = minOf(source, identity, installment, languageTrack, timing)

    companion object {
        fun unknown(): ConfidenceVector = ConfidenceVector(
            source = 0.0,
            identity = 0.0,
            installment = 0.0,
            languageTrack = 0.0,
            timing = 0.0,
        )
    }
}

/**
 * The source that established or revalidated the internal AniWorld identity.
 *
 * This is deliberately separate from [ReleaseSourceType]. An identity source
 * does not acquire release authority merely by being recorded here.
 */
enum class AniWorldIdentitySourceType {
    URL,
    PAGE,
    CALENDAR,
    RECENT,
    POSTPONEMENT,
    DIRECT_PAGE,
    MANUAL,
}

/**
 * Canonical AniWorld series identity.
 *
 * The identity key is provider scoped and path based. Titles, AniList IDs, MAL
 * IDs and account membership are metadata or mapping concerns and never change
 * the canonical identity. Validation timestamps and parser provenance are
 * retained for auditability but are intentionally excluded from equality.
 */
data class AniWorldSiteIdentifier(
    /** Provider-local path slug, without the leading `/anime/`. */
    val slug: String,
    val canonicalSeriesPath: String = "/anime/stream/$slug",
    val provider: String = PROVIDER,
    /** Provider-scoped canonical ID. For AniWorld this is the normalized path slug. */
    val canonicalId: String = slug,
    val canonicalUrl: String = "https://aniworld.to$canonicalSeriesPath",
    val normalizedTitle: String? = null,
    val sourceType: AniWorldIdentitySourceType = AniWorldIdentitySourceType.URL,
    val firstSeenAt: Instant? = null,
    val lastValidatedAt: Instant? = null,
    val sourceHash: String? = null,
    val parserVersion: String? = null,
) {
    init {
        require(provider == PROVIDER) { "AniWorld provider must be $PROVIDER" }
        require(isValidSlug(slug)) { "AniWorld slug is invalid" }
        require(canonicalSeriesPath == "/anime/stream/$slug") {
            "AniWorld series path must be the canonical path for the slug"
        }
        require(canonicalId == slug) {
            "AniWorld canonical id must equal the canonical provider path slug"
        }
        require(canonicalUrl == "https://$CANONICAL_HOST$canonicalSeriesPath") {
            "AniWorld canonical URL must use the canonical HTTPS host and series path"
        }
        require(normalizedTitle == null || normalizedTitle.isNotBlank()) {
            "normalized title must not be blank when present"
        }
        require(firstSeenAt == null || lastValidatedAt == null || !lastValidatedAt.isBefore(firstSeenAt)) {
            "last validation must not precede first observation"
        }
        require(sourceHash == null || sourceHash.isNotBlank()) {
            "source hash must not be blank when present"
        }
        require(parserVersion == null || parserVersion.isNotBlank()) {
            "parser version must not be blank when present"
        }
    }

    /** Stable provider/path key retained for ReleaseEvidence compatibility. */
    val stableKey: String = "$provider:$canonicalSeriesPath"

    override fun equals(other: Any?): Boolean =
        other is AniWorldSiteIdentifier &&
            provider == other.provider &&
            canonicalId == other.canonicalId

    override fun hashCode(): Int = 31 * provider.hashCode() + canonicalId.hashCode()

    companion object {
        const val PROVIDER = "aniworld"
        const val CANONICAL_HOST = "aniworld.to"
        private val SLUG_SEGMENT_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._~-]*")

        private fun isValidSlug(value: String): Boolean =
            SLUG_SEGMENT_PATTERN.matches(value)
    }
}

/**
 * A season/episode identity below a canonical series. A season-only page is
 * represented by [AniWorldIdentityResolution.season] and does not manufacture
 * an episode identity.
 */
data class AniWorldInstallmentIdentity(
    val series: AniWorldSiteIdentifier,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installment: Installment,
    val languageTrack: LanguageTrack? = null,
) {
    init {
        require(sourceSeason == null || sourceSeason >= 0) {
            "source season must be non-negative"
        }
        require(navigationSeason == null || navigationSeason >= 0) {
            "navigation season must be non-negative"
        }
    }

    val seriesIdentifier: AniWorldSiteIdentifier
        get() = series

    val stableKey: String = listOf(
        series.stableKey,
        sourceSeason?.toString() ?: "source-season:unknown",
        navigationSeason?.toString() ?: "navigation-season:unknown",
        installment.stableKey,
        languageTrack?.name ?: "track:unknown",
    ).joinToString("/")
}

/**
 * Append-only normalized source evidence. A nullable track is allowed for
 * non-authoritative metadata/correction records, but the authority reducer will
 * never release an item without a positive DE_SUB or DE_DUB track.
 */
data class ReleaseEvidence(
    val id: String,
    val sourceType: ReleaseSourceType,
    val sourceUrl: String,
    val sourceHash: String,
    val parserVersion: String,
    val observedAt: Instant,
    val sourceReportedAt: Instant?,
    val approximateTime: Boolean,
    val siteIdentifier: AniWorldSiteIdentifier?,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installment: Installment,
    val languageTrack: LanguageTrack?,
    val evidenceType: ReleaseEvidenceType,
    /** Schedule correction carried by postponement evidence; never inferred from missing data. */
    val scheduleCondition: ScheduleCondition = ScheduleCondition.UNKNOWN,
    val confidence: ConfidenceVector,
) {
    init {
        require(id.isNotBlank()) { "evidence id must not be blank" }
        require(sourceUrl.isHttpUrl()) { "evidence source URL must be HTTP(S)" }
        require(sourceHash.isNotBlank()) { "evidence source hash must not be blank" }
        require(parserVersion.isNotBlank()) { "evidence parser version must not be blank" }
        require(sourceSeason == null || sourceSeason >= 0) {
            "source season must be non-negative"
        }
        require(navigationSeason == null || navigationSeason >= 0) {
            "navigation season must be non-negative"
        }
        require(!sourceType.isAniWorld || siteIdentifier != null) {
            "AniWorld evidence requires a canonical site identity"
        }
    }

    /**
     * Stable stream identity used by append-only storage and reducer isolation.
     */
    val identityKey: String = listOf(
        siteIdentifier?.stableKey ?: "site:unknown",
        sourceSeason?.toString() ?: "source-season:unknown",
        navigationSeason?.toString() ?: "navigation-season:unknown",
        installment.stableKey,
        languageTrack?.name ?: "track:unknown",
    ).joinToString("/")

    private fun String.isHttpUrl(): Boolean = runCatching {
        URI(this)
    }.getOrNull()?.let { it.scheme == "http" || it.scheme == "https" } == true
}

/**
 * The reducer's decision is an authority projection, not a source snapshot.
 */
data class ReleaseDecision(
    val siteIdentifier: AniWorldSiteIdentifier?,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installment: Installment,
    val languageTrack: LanguageTrack?,
    val phase: ReleasePhase,
    val scheduleCondition: ScheduleCondition,
    val authority: ReleaseAuthority,
    val contributingEvidenceIds: List<String> = emptyList(),
    val authoritativeEvidenceIds: List<String> = emptyList(),
    val releaseAt: Instant? = null,
    val lastObservedAt: Instant? = null,
    val decidedAt: Instant,
    val revision: Long = 0L,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(sourceSeason == null || sourceSeason >= 0) {
            "source season must be non-negative"
        }
        require(navigationSeason == null || navigationSeason >= 0) {
            "navigation season must be non-negative"
        }
        require(revision >= 0L) { "decision revision must be non-negative" }
        require(contributingEvidenceIds.all(String::isNotBlank)) {
            "contributing evidence ids must not be blank"
        }
        require(authoritativeEvidenceIds.all(String::isNotBlank)) {
            "authoritative evidence ids must not be blank"
        }
        require(contributingEvidenceIds.distinct().size == contributingEvidenceIds.size) {
            "contributing evidence ids must be unique"
        }
        require(authoritativeEvidenceIds.distinct().size == authoritativeEvidenceIds.size) {
            "authoritative evidence ids must be unique"
        }
        require(contributingEvidenceIds.toSet().containsAll(authoritativeEvidenceIds.toSet())) {
            "authoritative evidence must be contributing evidence"
        }
        if (phase == ReleasePhase.CONFIRMED || phase == ReleasePhase.RELEASED) {
            require(authority == ReleaseAuthority.ANIWORLD) {
                "confirmed/released decisions require AniWorld authority"
            }
        }
        if (phase == ReleasePhase.RELEASED) {
            require(siteIdentifier != null) {
                "released decisions require a canonical AniWorld identity"
            }
            require(languageTrack != null) {
                "released decisions require an explicit language track"
            }
            require(authoritativeEvidenceIds.isNotEmpty()) {
                "released decisions require positive authority evidence"
            }
        }
        if (authority == ReleaseAuthority.ANIWORLD) {
            require(siteIdentifier != null) {
                "AniWorld authority requires a canonical site identity"
            }
            require(languageTrack != null) {
                "AniWorld authority requires an explicit language track"
            }
            require(authoritativeEvidenceIds.isNotEmpty()) {
                "AniWorld authority requires positive evidence"
            }
        }
    }

    val identityKey: String = listOf(
        siteIdentifier?.stableKey ?: "site:unknown",
        sourceSeason?.toString() ?: "source-season:unknown",
        navigationSeason?.toString() ?: "navigation-season:unknown",
        installment.stableKey,
        languageTrack?.name ?: "track:unknown",
    ).joinToString("/")

    companion object {
        fun fromEvidence(
            evidence: ReleaseEvidence,
            phase: ReleasePhase = ReleasePhase.UNKNOWN,
            scheduleCondition: ScheduleCondition = ScheduleCondition.UNKNOWN,
            authority: ReleaseAuthority = ReleaseAuthority.NONE,
            decidedAt: Instant = evidence.observedAt,
            revision: Long = 0L,
        ): ReleaseDecision {
            val authoritativeIds = if (authority == ReleaseAuthority.ANIWORLD) {
                listOf(evidence.id)
            } else {
                emptyList()
            }
            return ReleaseDecision(
                siteIdentifier = evidence.siteIdentifier,
                sourceSeason = evidence.sourceSeason,
                navigationSeason = evidence.navigationSeason,
                installment = evidence.installment,
                languageTrack = evidence.languageTrack,
                phase = phase,
                scheduleCondition = scheduleCondition,
                authority = authority,
                contributingEvidenceIds = listOf(evidence.id),
                authoritativeEvidenceIds = authoritativeIds,
                releaseAt = null,
                lastObservedAt = evidence.observedAt,
                decidedAt = decidedAt,
                revision = revision,
            )
        }
    }
}

/**
 * Last-good state for one source role. It is intentionally independent from the
 * release decision so a degraded source cannot erase successful evidence.
 */
data class SourceHealth(
    val sourceType: ReleaseSourceType,
    val status: SourceHealthStatus,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val consecutiveFailures: Int = 0,
    val parserVersion: String? = null,
    val sourceHash: String? = null,
    val diagnostic: String? = null,
) {
    init {
        require(consecutiveFailures >= 0) {
            "consecutive failures must be non-negative"
        }
    }
}

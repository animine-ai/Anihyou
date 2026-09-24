package com.axiel7.anihyou.release.core.model

import java.time.Instant

/** Provider-neutral external namespace, for example `anilist` or `mal`. */
@JvmInline
value class ExternalProvider(val value: String) {
    init {
        require(PROVIDER_PATTERN.matches(value)) {
            "external provider must be a lowercase provider key"
        }
    }

    override fun toString(): String = value

    companion object {
        private val PROVIDER_PATTERN = Regex("[a-z][a-z0-9._-]*")

        val ANILIST = ExternalProvider("anilist")
        val MYANIMELIST = ExternalProvider("myanimelist")
        val MAL = MYANIMELIST
    }
}

enum class MappingSource {
    MANUAL,
    PERSISTED,
    MALSYNC,
    FALLBACK,
}

/** State of an external mapping binding, not release authority. */
enum class MappingStatus {
    UNRESOLVED,
    ACTIVE,
    AMBIGUOUS,
    INVALID,
    STALE,
    REVOKED,
}

/** Why a durable or transient mapping lookup ended in a particular state. */
enum class MappingAttemptResultKind {
    CACHE_HIT,
    MAPPED,
    UNMAPPED,
    AMBIGUOUS,
    UNSUPPORTED,
    NETWORK_FAILURE,
    MALFORMED_RESPONSE,
    RATE_LIMITED,
    HTTP_FAILURE,
    PERSISTENCE_REJECTED,
}

/**
 * A binding from one AniWorld mapping subject to one external ID.
 *
 * This intentionally does not contain release fields. A mapping can identify
 * a catalogue object but cannot establish release authority.
 */
data class ExternalMapping(
    val subject: AniWorldMappingSubject,
    val externalProvider: ExternalProvider,
    val externalId: String?,
    val mappingSource: MappingSource,
    val confidence: MappingConfidence,
    val createdAt: Instant,
    val validatedAt: Instant?,
    val status: MappingStatus,
    val staleAt: Instant? = null,
    val provenance: String = "",
    val parserVersion: String? = null,
) {
    init {
        if (status == MappingStatus.ACTIVE || status == MappingStatus.STALE) {
            require(!externalId.isNullOrBlank()) {
                "active or stale mappings require an external id"
            }
            require(confidence == MappingConfidence.EXACT || confidence == MappingConfidence.HIGH) {
                "active or stale mappings require exact or high confidence"
            }
        }
        if (status == MappingStatus.AMBIGUOUS || status == MappingStatus.UNRESOLVED) {
            require(externalId == null) {
                "ambiguous and unresolved mappings cannot bind an external id"
            }
            require(confidence == MappingConfidence.AMBIGUOUS || confidence == MappingConfidence.NONE) {
                "ambiguous and unresolved mappings require ambiguous or none confidence"
            }
        }
        require(externalId == null || externalId.isNotBlank()) {
            "external id must not be blank when present"
        }
        require(validatedAt == null || !validatedAt.isBefore(createdAt)) {
            "mapping validation must not precede creation"
        }
        require(staleAt == null || !staleAt.isBefore(createdAt)) {
            "mapping staleness must not precede creation"
        }
        require(provenance.length <= MAX_PROVENANCE_LENGTH) {
            "mapping provenance exceeds the bounded length"
        }
        require(parserVersion == null || parserVersion.length <= MAX_PARSER_VERSION_LENGTH) {
            "mapping parser version exceeds the bounded length"
        }
    }

    val mappingSubjectKey: String
        get() = subject.stableKey

    companion object {
        const val MAX_PROVENANCE_LENGTH = 512
        const val MAX_PARSER_VERSION_LENGTH = 128
    }
}

/** Candidate provenance is retained without turning a candidate into a binding. */
data class ExternalMappingCandidate(
    val subject: AniWorldMappingSubject,
    val externalProvider: ExternalProvider,
    val externalId: String,
    val mappingSource: MappingSource,
    val confidence: MappingConfidence,
) {
    init {
        require(externalId.isNotBlank()) { "mapping candidate id must not be blank" }
        require(confidence != MappingConfidence.NONE) {
            "mapping candidates need positive or ambiguous confidence"
        }
    }
}

sealed interface ExternalMappingResolution {
    data class Mapped(
        val mapping: ExternalMapping,
    ) : ExternalMappingResolution {
        init {
            require(mapping.status == MappingStatus.ACTIVE || mapping.status == MappingStatus.STALE) {
                "mapped resolution requires an active or stale mapping"
            }
        }
    }

    data class Unmapped(
        val subject: AniWorldMappingSubject,
        val diagnostic: String,
    ) : ExternalMappingResolution {
        init { require(diagnostic.isNotBlank()) { "unmapped diagnostic must not be blank" } }
    }

    data class Ambiguous(
        val subject: AniWorldMappingSubject,
        val candidates: List<ExternalMappingCandidate>,
        val diagnostic: String,
    ) : ExternalMappingResolution {
        init {
            require(candidates.size > 1) { "ambiguous resolution needs multiple candidates" }
            require(candidates.all { it.subject == subject }) {
                "ambiguous candidates must belong to the requested subject"
            }
            require(candidates.map { it.externalProvider to it.externalId }.distinct().size == candidates.size) {
                "ambiguous candidates must be distinct"
            }
            require(diagnostic.isNotBlank()) { "ambiguous diagnostic must not be blank" }
        }
    }
}

/** A bounded, append-only audit record for mapping attempts. */
data class ExternalMappingAttempt(
    val subject: AniWorldMappingSubject,
    val externalProvider: ExternalProvider,
    val source: MappingSource,
    val attemptedAt: Instant,
    val resultKind: MappingAttemptResultKind,
    val diagnostic: String,
    val httpStatus: Int? = null,
    val retryAt: Instant? = null,
    val cooldownUntil: Instant? = null,
    val parserVersion: String? = null,
) {
    init {
        require(diagnostic.isNotBlank()) { "mapping attempt diagnostic must not be blank" }
        require(diagnostic.length <= MAX_DIAGNOSTIC_LENGTH) {
            "mapping attempt diagnostic exceeds the bounded length"
        }
        require(httpStatus == null || httpStatus in 100..599) {
            "mapping attempt HTTP status is invalid"
        }
        require(parserVersion == null || parserVersion.length <= MAX_PARSER_VERSION_LENGTH) {
            "mapping attempt parser version exceeds the bounded length"
        }
    }

    companion object {
        const val MAX_DIAGNOSTIC_LENGTH = 512
        const val MAX_PARSER_VERSION_LENGTH = 128
    }
}

/**
 * Durable mapping precedence. It is intentionally independent of any source
 * adapter so an outage or malformed response cannot poison an existing cache.
 */
object ExternalMappingPrecedence {
    fun canReplace(existing: ExternalMapping?, incoming: ExternalMapping): Boolean {
        if (existing == null) return true
        require(existing.subject == incoming.subject) {
            "mapping subjects must match before precedence is evaluated"
        }
        require(existing.externalProvider == incoming.externalProvider) {
            "mapping providers must match before precedence is evaluated"
        }

        if (existing.mappingSource == MappingSource.MANUAL) return false
        if (incoming.externalId == null) return false
        if (incoming.mappingSource == MappingSource.MANUAL) return true
        if (incoming.mappingSource == MappingSource.MALSYNC) {
            return existing.mappingSource == MappingSource.FALLBACK ||
                existing.mappingSource == MappingSource.MALSYNC
        }
        return sourceRank(incoming.mappingSource) >= sourceRank(existing.mappingSource)
    }

    private fun sourceRank(source: MappingSource): Int = when (source) {
        MappingSource.MANUAL -> 4
        MappingSource.PERSISTED -> 3
        MappingSource.MALSYNC -> 2
        MappingSource.FALLBACK -> 1
    }
}

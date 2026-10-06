package com.axiel7.anihyou.release.core.matching

import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.SourceIdentity

data class ReleaseMatchRequest(
    val source: SourceIdentity,
    val title: String,
    val format: String? = null,
    val aliases: Set<String> = emptySet(),
    val season: Int? = null,
    val part: Int? = null,
    val manualMediaId: Int? = null,
    val historicalMediaId: Int? = null,
) {
    init {
        require(title.isNotBlank()) { "release title must not be blank" }
        require(season == null || season > 0) { "season must be positive" }
        require(part == null || part > 0) { "part must be positive" }
        require(manualMediaId == null || manualMediaId > 0) { "manual media id must be positive" }
        require(historicalMediaId == null || historicalMediaId > 0) {
            "historical media id must be positive"
        }
    }
}

enum class MatchTier(val precedence: Int) {
    MANUAL(6),
    HISTORICAL(5),
    EXACT_NORMALIZED(4),
    EXACT_BASE_SEASON(3),
    ALIAS(2),
    FUZZY(1),
}

data class ScoredIdentityCandidate(
    val candidate: IdentityCandidate,
    val tier: MatchTier,
    val score: Double,
    val evidence: String,
)

sealed interface MatchDecision {
    data class Matched(
        val mediaId: Int,
        val tier: MatchTier,
        val score: Double,
        val runnerUpMargin: Double?,
        val evidence: String,
        val candidate: IdentityCandidate?,
    ) : MatchDecision

    data class Ambiguous(
        val candidates: List<ScoredIdentityCandidate>,
        val reason: String,
    ) : MatchDecision

    data class Unmapped(
        val candidates: List<ScoredIdentityCandidate>,
        val reason: String,
    ) : MatchDecision
}

class ReleaseMatcher(
    val matcherVersion: String = "wp04-matcher-v1",
) {
    init { require(matcherVersion.isNotBlank()) { "matcher version must not be blank" } }

    fun match(
        request: ReleaseMatchRequest,
        candidates: List<IdentityCandidate>,
    ): MatchDecision {
        request.manualMediaId?.let { mediaId ->
            return MatchDecision.Matched(
                mediaId = mediaId,
                tier = MatchTier.MANUAL,
                score = 1.0,
                runnerUpMargin = null,
                evidence = "manual-mapping",
                candidate = candidates.firstOrNull { it.mediaId == mediaId },
            )
        }

        val compatible = candidates
            .filter { isCompatible(request, it) }
            .distinctBy { it.mediaId }
        request.historicalMediaId?.let { mediaId ->
            compatible.firstOrNull { it.mediaId == mediaId }?.let { candidate ->
                return MatchDecision.Matched(
                    mediaId = mediaId,
                    tier = MatchTier.HISTORICAL,
                    score = 0.99,
                    runnerUpMargin = null,
                    evidence = "historical-mapping",
                    candidate = candidate,
                )
            }
        }

        val scored = compatible.mapNotNull { score(request, it) }
            .groupBy { it.candidate.mediaId }
            .values
            .mapNotNull { group ->
                group.maxWithOrNull(
                    compareBy<ScoredIdentityCandidate> { it.tier.precedence }
                        .thenBy { it.score }
                        .thenBy { it.evidence },
                )
            }
            .sortedWith(
                compareByDescending<ScoredIdentityCandidate> { it.tier.precedence }
                    .thenByDescending { it.score }
                    .thenBy { it.candidate.mediaId },
            )

        val winner = scored.firstOrNull()
            ?: return MatchDecision.Unmapped(emptyList(), "no-compatible-candidate")
        val runner = scored.getOrNull(1)
        val margin = runner?.let { winner.score - it.score }
        if (winner.tier == MatchTier.FUZZY && winner.score < MIN_FUZZY_SCORE) {
            return MatchDecision.Unmapped(scored.take(MAX_REPORTED_CANDIDATES), "fuzzy-score-below-threshold")
        }
        if (runner != null && runner.tier == winner.tier && (margin ?: 0.0) < MIN_UNIQUE_MARGIN) {
            return MatchDecision.Ambiguous(scored.take(MAX_REPORTED_CANDIDATES), "same-tier-margin-too-small")
        }
        return MatchDecision.Matched(
            mediaId = winner.candidate.mediaId,
            tier = winner.tier,
            score = winner.score,
            runnerUpMargin = margin,
            evidence = winner.evidence,
            candidate = winner.candidate,
        )
    }

    private fun score(
        request: ReleaseMatchRequest,
        candidate: IdentityCandidate,
    ): ScoredIdentityCandidate? {
        val requestTitle = TitleNormalizer.normalize(request.title)
        val candidateTitles = candidate.titles.map(TitleNormalizer::normalize)
        val requestSeason = request.season ?: requestTitle.season
        val requestPart = request.part ?: requestTitle.part
        val aliasTitles = request.aliases.map(TitleNormalizer::normalize)

        candidateTitles.firstOrNull { title ->
            title.canonical == requestTitle.canonical && qualifiersCompatible(requestSeason, requestPart, title)
        }?.let {
            return ScoredIdentityCandidate(candidate, MatchTier.EXACT_NORMALIZED, 1.0, "exact-normalized-title")
        }
        candidateTitles.firstOrNull { title ->
            title.base == requestTitle.base &&
                qualifiersCompatible(requestSeason, requestPart, title) &&
                (requestSeason != null || requestPart != null || title.season == null && title.part == null)
        }?.let {
            return ScoredIdentityCandidate(candidate, MatchTier.EXACT_BASE_SEASON, 0.96, "exact-base-season")
        }
        aliasTitles.firstOrNull { alias ->
            candidateTitles.any { title ->
                (title.canonical == alias.canonical || title.base == alias.base) &&
                    qualifiersCompatible(requestSeason, requestPart, title)
            }
        }?.let {
            return ScoredIdentityCandidate(candidate, MatchTier.ALIAS, 0.92, "alias-title")
        }
        if (candidateTitles.any { title ->
                qualifiersCompatible(requestSeason, requestPart, title) &&
                    isSafePrefix(requestTitle.tokens, title.tokens)
            }
        ) {
            return ScoredIdentityCandidate(candidate, MatchTier.ALIAS, 0.88, "bounded-title-prefix")
        }

        val bestSimilarity = candidateTitles
            .filter { qualifiersCompatible(requestSeason, requestPart, it) }
            .maxOfOrNull { tokenSimilarity(requestTitle.tokens, it.tokens) }
            ?: return null
        if (requestTitle.tokens.size < 2 || bestSimilarity < FUZZY_SIMILARITY_FLOOR) return null
        return ScoredIdentityCandidate(
            candidate = candidate,
            tier = MatchTier.FUZZY,
            score = bestSimilarity,
            evidence = "guarded-token-similarity",
        )
    }

    private fun isCompatible(
        request: ReleaseMatchRequest,
        candidate: IdentityCandidate,
    ): Boolean {
        val requestedFormat = request.format?.let(::formatClass)
        val candidateFormat = candidate.format?.let(::formatClass)
        if (request.format != null && (requestedFormat == FormatClass.UNKNOWN || candidateFormat == null)) return false
        if (requestedFormat != null && requestedFormat != FormatClass.UNKNOWN &&
            candidateFormat != requestedFormat
        ) return false
        if (!kindAccepts(request.source.stream.releaseKind, candidateFormat)) return false

        val requestTitle = TitleNormalizer.normalize(request.title)
        val candidateQualifiers = candidate.titles.map(TitleNormalizer::normalize)
        val requestSeason = request.season ?: requestTitle.season
        val requestPart = request.part ?: requestTitle.part
        return candidateQualifiers.any { qualifiersCompatible(requestSeason, requestPart, it) }
    }

    private fun qualifiersCompatible(
        requestSeason: Int?,
        requestPart: Int?,
        candidate: NormalizedTitle,
    ): Boolean =
        (requestSeason == null || candidate.season == null || requestSeason == candidate.season) &&
            (requestPart == null || candidate.part == null || requestPart == candidate.part)

    private fun isSafePrefix(left: List<String>, right: List<String>): Boolean {
        if (left.size < 2 || right.size < 2) return false
        val shorter = if (left.size <= right.size) left else right
        val longer = if (left.size <= right.size) right else left
        return longer.take(shorter.size) == shorter && shorter.joinToString("").length >= 8
    }

    private fun tokenSimilarity(left: List<String>, right: List<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.toSet().intersect(right.toSet()).size.toDouble()
        val union = left.toSet().union(right.toSet()).size.toDouble()
        val containment = intersection / minOf(left.toSet().size, right.toSet().size).toDouble()
        return ((intersection / union) * 0.65 + containment * 0.35)
            .coerceIn(0.0, 1.0)
    }

    private fun kindAccepts(kind: ReleaseKind, candidateFormat: FormatClass?): Boolean = when (kind) {
        ReleaseKind.MOVIE -> candidateFormat == null || candidateFormat == FormatClass.MOVIE
        ReleaseKind.SPECIAL -> candidateFormat == null || candidateFormat == FormatClass.SPECIAL
        ReleaseKind.OVA -> candidateFormat == null || candidateFormat == FormatClass.OVA
        ReleaseKind.ONA -> candidateFormat == null || candidateFormat == FormatClass.ONA
        ReleaseKind.EPISODE -> candidateFormat == null || candidateFormat == FormatClass.TV
    }

    private fun formatClass(raw: String): FormatClass = when (
        raw.trim().uppercase().replace('-', '_').replace(' ', '_')
    ) {
        "TV", "TV_SHORT", "SERIES", "ANIME" -> FormatClass.TV
        "MOVIE", "FILM" -> FormatClass.MOVIE
        "SPECIAL" -> FormatClass.SPECIAL
        "OVA" -> FormatClass.OVA
        "ONA" -> FormatClass.ONA
        else -> FormatClass.UNKNOWN
    }

    private enum class FormatClass { TV, MOVIE, SPECIAL, OVA, ONA, UNKNOWN }

    private companion object {
        const val FUZZY_SIMILARITY_FLOOR = 0.72
        const val MIN_FUZZY_SCORE = 0.78
        const val MIN_UNIQUE_MARGIN = 0.08
        const val MAX_REPORTED_CANDIDATES = 4
    }
}

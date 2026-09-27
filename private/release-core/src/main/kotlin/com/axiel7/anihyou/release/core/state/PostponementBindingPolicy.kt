package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.net.URI
import java.time.Instant

data class PostponementBindingProof(
    val sourceUrl: String,
    val sourceHash: String,
    val parserVersion: String,
    val recordOrdinal: Int,
    val linkedSeriesPath: String?,
    val explicitSourceSeason: Int?,
    val installment: Installment?,
    val tracks: Set<LanguageTrack>,
    val correctionAt: Instant?,
    val providerRecordLink: Boolean,
    val providerStatement: Boolean,
    val observedAt: Instant? = null,
    val statementSeriesPath: String? = null,
    val statementSourceSeason: Int? = null,
    val statementInstallment: Installment? = null,
    val statementTracks: Set<LanguageTrack> = emptySet(),
    val filmNamespace: Boolean = false,
)

sealed interface PostponementBindingResult {
    data class Bound(val canonicalKey: String) : PostponementBindingResult
    data class Unbound(val reason: Reason) : PostponementBindingResult
    data class Ambiguous(val reason: Reason) : PostponementBindingResult
    enum class Reason { SOURCE_PROOF_MISSING, IDENTITY_MISSING, INSTALLMENT_MISSING, TRACK_MISSING,
        SEASON_MISSING, INVALID_FILM, INVALID_ROUTE, AMBIGUOUS_TRACK, SEMANTICS_MISSING, EVIDENCE_CONFLICT }
}

object PostponementBindingPolicy {
    fun bind(proof: PostponementBindingProof): PostponementBindingResult {
        val source = runCatching { URI(proof.sourceUrl) }.getOrNull()
        if (source == null || source.scheme != "https" || source.host?.lowercase() !in setOf("aniworld.to", "www.aniworld.to") ||
            source.userInfo != null || source.port !in setOf(-1, 443) || source.fragment != null ||
            !proof.sourceHash.matches(Regex("[0-9a-f]{64}")) || proof.parserVersion.isBlank() ||
            proof.parserVersion.length > 64 || proof.recordOrdinal !in 0..511 || proof.observedAt == null ||
            !proof.providerRecordLink || !proof.providerStatement) return unbound(PostponementBindingResult.Reason.SOURCE_PROOF_MISSING)
        val path = proof.linkedSeriesPath ?: return unbound(PostponementBindingResult.Reason.IDENTITY_MISSING)
        val slug = path.removePrefix("/anime/stream/")
        val canonicalPath = runCatching { AniWorldSiteIdentifier(slug).canonicalSeriesPath }.getOrNull()
        if (canonicalPath != path) return unbound(PostponementBindingResult.Reason.INVALID_ROUTE)
        if (proof.statementSeriesPath != null && proof.statementSeriesPath != path ||
            proof.statementSourceSeason != proof.explicitSourceSeason &&
                (proof.statementSourceSeason != null || proof.explicitSourceSeason != null) ||
            proof.statementInstallment != null && proof.installment != null && proof.statementInstallment != proof.installment ||
            proof.statementTracks.isNotEmpty() && proof.statementTracks != proof.tracks)
            return PostponementBindingResult.Ambiguous(PostponementBindingResult.Reason.EVIDENCE_CONFLICT)
        val installment = proof.installment ?: return unbound(PostponementBindingResult.Reason.INSTALLMENT_MISSING)
        val track = proof.tracks.singleOrNull() ?: return if (proof.tracks.size > 1)
            PostponementBindingResult.Ambiguous(PostponementBindingResult.Reason.AMBIGUOUS_TRACK)
        else unbound(PostponementBindingResult.Reason.TRACK_MISSING)
        val season = when (installment) {
            is Installment.Episode -> proof.explicitSourceSeason?.takeIf { it >= 0 }
                ?: return unbound(PostponementBindingResult.Reason.SEASON_MISSING)
            is Installment.Film -> {
                if (installment.number == null || installment.number <= 0 || proof.explicitSourceSeason != null || !proof.filmNamespace)
                    return unbound(PostponementBindingResult.Reason.INVALID_FILM)
                null
            }
            is Installment.Special -> return unbound(PostponementBindingResult.Reason.INVALID_FILM)
        }
        if (proof.correctionAt == null || proof.statementInstallment == null || proof.statementSeriesPath == null ||
            proof.statementTracks.isEmpty()) return unbound(PostponementBindingResult.Reason.SEMANTICS_MISSING)
        return PostponementBindingResult.Bound(encode(path, season, installment, track.name))
    }

    private fun unbound(reason: PostponementBindingResult.Reason) = PostponementBindingResult.Unbound(reason)
    private fun encode(path: String, season: Int?, installment: Installment, track: String): String {
        val (kind, number, fraction) = when (installment) {
            is Installment.Episode -> Triple("EPISODE", installment.number.toString(), installment.fraction?.toString())
            is Installment.Film -> Triple("FILM", installment.number.toString(), null)
            is Installment.Special -> error("non-bindable special")
        }
        return listOf("canonical-release-v1", "aniworld", path, kind, season?.toString(), number, fraction, track)
            .joinToString("") { it?.let { s -> "${s.toByteArray().size}:$s" } ?: "-1:" }
    }
}

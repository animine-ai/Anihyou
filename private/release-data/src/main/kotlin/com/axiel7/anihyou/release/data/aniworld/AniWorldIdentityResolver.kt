package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldInstallmentIdentity
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.util.Locale

enum class AniWorldIdentityFailureKind {
    INVALID_URL,
    UNSUPPORTED_HOST,
    INVALID_PATH,
    AMBIGUOUS_PATH,
}

sealed interface AniWorldIdentityResolution {
    data class Success(
        val series: AniWorldSiteIdentifier,
        /** A season page has a season but no synthetic episode identity. */
        val season: Int?,
        val installment: AniWorldInstallmentIdentity?,
        val sourceUrl: String,
    ) : AniWorldIdentityResolution

    data class Failure(
        val kind: AniWorldIdentityFailureKind,
        val diagnostic: String,
    ) : AniWorldIdentityResolution {
        init { require(diagnostic.isNotBlank()) { "identity failure diagnostic must not be blank" } }
    }
}

/**
 * Resolves only deterministic AniWorld URL/page identity. It never searches
 * AniList, calls MALSync or creates ReleaseEvidence.
 */
class AniWorldIdentityResolver(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun resolve(
        sourceUrl: String,
        normalizedTitle: String? = null,
        sourceType: AniWorldIdentitySourceType = AniWorldIdentitySourceType.URL,
        observedAt: Instant = clock.instant(),
        sourceHash: String? = null,
        parserVersion: String? = null,
    ): AniWorldIdentityResolution {
        val route = when (val parsed = AniWorldCanonicalRouteParser.parse(sourceUrl)) {
            is AniWorldCanonicalRouteResult.Success -> parsed.route
            is AniWorldCanonicalRouteResult.Failure -> {
                return AniWorldIdentityResolution.Failure(
                    kind = parsed.kind.toIdentityFailureKind(),
                    diagnostic = parsed.diagnostic,
                )
            }
        }
        val identifier = runCatching {
            AniWorldSiteIdentifier(
                slug = route.slug,
                canonicalSeriesPath = route.canonicalSeriesPath,
                canonicalId = route.slug,
                canonicalUrl = route.canonicalSeriesUrl,
                normalizedTitle = normalizeTitle(normalizedTitle),
                sourceType = sourceType,
                firstSeenAt = observedAt,
                lastValidatedAt = observedAt,
                sourceHash = sourceHash?.trim()?.ifBlank { null },
                parserVersion = parserVersion?.trim()?.ifBlank { null },
            )
        }.getOrElse {
            return AniWorldIdentityResolution.Failure(
                kind = AniWorldIdentityFailureKind.INVALID_PATH,
                diagnostic = "AniWorld series identity failed validation",
            )
        }
        val installment = route.installment?.let { installment ->
            AniWorldInstallmentIdentity(
                series = identifier,
                sourceSeason = route.season,
                navigationSeason = route.season,
                installment = installment,
            )
        }
        return AniWorldIdentityResolution.Success(
            series = identifier,
            season = route.season,
            installment = installment,
            sourceUrl = sourceUrl.trim(),
        )
    }

    private fun normalizeTitle(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{P}\\p{S}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
    }

    private fun AniWorldRouteFailureKind.toIdentityFailureKind(): AniWorldIdentityFailureKind = when (this) {
        AniWorldRouteFailureKind.INVALID_URL -> AniWorldIdentityFailureKind.INVALID_URL
        AniWorldRouteFailureKind.UNSUPPORTED_HOST -> AniWorldIdentityFailureKind.UNSUPPORTED_HOST
        AniWorldRouteFailureKind.INVALID_PATH -> AniWorldIdentityFailureKind.INVALID_PATH
        AniWorldRouteFailureKind.AMBIGUOUS_PATH -> AniWorldIdentityFailureKind.AMBIGUOUS_PATH
    }
}

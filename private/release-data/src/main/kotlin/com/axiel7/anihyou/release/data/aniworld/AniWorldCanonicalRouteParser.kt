package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.Installment
import java.net.URI
import java.text.Normalizer
import java.util.Locale

enum class AniWorldCanonicalRouteKind {
    SERIES,
    SEASON,
    EPISODE,
    FILMS_OVERVIEW,
    FILM,
}

enum class AniWorldRouteFailureKind {
    INVALID_URL,
    UNSUPPORTED_HOST,
    INVALID_PATH,
    AMBIGUOUS_PATH,
}

data class AniWorldCanonicalRoute(
    val slug: String,
    val canonicalSeriesPath: String,
    val canonicalSeriesUrl: String,
    val season: Int?,
    val installment: Installment?,
    val kind: AniWorldCanonicalRouteKind,
) {
    init {
        require(SLUG_PATTERN.matches(slug)) { "AniWorld route slug is invalid" }
        require(canonicalSeriesPath == "/anime/stream/$slug") {
            "AniWorld route must use the canonical stream series path"
        }
        require(canonicalSeriesUrl == "https://${AniWorldSiteIdentifierHost.HOST}$canonicalSeriesPath") {
            "AniWorld route must use the canonical HTTPS series URL"
        }
        require(season == null || season >= 0) { "AniWorld route season must be non-negative" }
        when (kind) {
            AniWorldCanonicalRouteKind.SERIES -> {
                require(season == null && installment == null)
            }
            AniWorldCanonicalRouteKind.SEASON -> {
                require(season != null && installment == null)
            }
            AniWorldCanonicalRouteKind.EPISODE -> {
                require(installment is Installment.Episode)
            }
            AniWorldCanonicalRouteKind.FILMS_OVERVIEW -> {
                require(season == null && installment == null)
            }
            AniWorldCanonicalRouteKind.FILM -> {
                require(season == null && installment is Installment.Film)
            }
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._~-]*")
    }
}

sealed interface AniWorldCanonicalRouteResult {
    data class Success(
        val route: AniWorldCanonicalRoute,
    ) : AniWorldCanonicalRouteResult

    data class Failure(
        val kind: AniWorldRouteFailureKind,
        val diagnostic: String,
    ) : AniWorldCanonicalRouteResult {
        init { require(diagnostic.isNotBlank()) { "route failure diagnostic must not be blank" } }
    }
}

sealed interface AniWorldCardRouteResult {
    data class Success(
        val route: AniWorldCanonicalRoute,
    ) : AniWorldCardRouteResult

    data class Failure(
        val diagnostic: String,
    ) : AniWorldCardRouteResult {
        init { require(diagnostic.isNotBlank()) { "card route failure diagnostic must not be blank" } }
    }
}

/**
 * The one AniWorld route grammar shared by identity and both evidence parsers.
 * The optional legacy source-key form is only an input compatibility bridge;
 * every successful result is normalized to /anime/stream/<slug>.
 */
object AniWorldCanonicalRouteParser {
    fun parse(sourceUrl: String): AniWorldCanonicalRouteResult {
        val trimmedUrl = sourceUrl.trim()
        val uri = runCatching { URI(trimmedUrl) }.getOrNull()
            ?: return failure(AniWorldRouteFailureKind.INVALID_URL, "URL is not parseable")
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null || uri.port != -1) {
            return failure(
                AniWorldRouteFailureKind.INVALID_URL,
                "identity source must be an HTTPS URL without user info or a port",
            )
        }
        if (host != AniWorldSiteIdentifierHost.HOST && host != "www.${AniWorldSiteIdentifierHost.HOST}") {
            return failure(
                AniWorldRouteFailureKind.UNSUPPORTED_HOST,
                "identity source host is not AniWorld",
            )
        }
        val rawPath = uri.rawPath
            ?: return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld URL has no path")
        if (rawPath.contains("%2f", ignoreCase = true)) {
            return failure(
                AniWorldRouteFailureKind.AMBIGUOUS_PATH,
                "encoded path separators cannot be used as identity boundaries",
            )
        }
        if (rawPath.contains("//")) {
            return failure(
                AniWorldRouteFailureKind.AMBIGUOUS_PATH,
                "empty AniWorld route path segments are not canonical",
            )
        }
        val pathSegments = uri.path
            ?.trimEnd('/')
            ?.split('/')
            ?.filter(String::isNotEmpty)
            ?.map { normalizePathSegment(it) }
        if (pathSegments == null || pathSegments.any { it == null }) {
            return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld path contains an invalid segment")
        }
        val normalizedSegments = pathSegments.filterNotNull()
        if (normalizedSegments.firstOrNull() != "anime") {
            return failure(
                AniWorldRouteFailureKind.INVALID_PATH,
                "AniWorld identity path must start with /anime/",
            )
        }
        return parseAnimeSegments(normalizedSegments.drop(1))
    }

    /** Normalize a provider data key without allowing it to become a route heuristic. */
    fun parseSourceKey(sourceKey: String): AniWorldCanonicalRouteResult {
        val candidate = sourceKey.trim().trim('/')
        if (candidate.isBlank()) {
            return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld source key is blank")
        }
        val routeUrl = when {
            candidate.startsWith("anime/") -> "https://${AniWorldSiteIdentifierHost.HOST}/$candidate"
            candidate.startsWith("stream/") -> "https://${AniWorldSiteIdentifierHost.HOST}/anime/$candidate"
            '/' in candidate -> {
                return failure(
                    AniWorldRouteFailureKind.INVALID_PATH,
                    "AniWorld source key must be a single series slug",
                )
            }
            else -> null
        }
        return if (routeUrl != null) {
            parse(routeUrl)
        } else {
            val slug = normalizePathSegment(candidate)
                ?: return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld source key is invalid")
            seriesRoute(slug)
        }
    }

    private fun parseAnimeSegments(segments: List<String>): AniWorldCanonicalRouteResult {
        if (segments.isEmpty()) {
            return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld series slug is missing")
        }
        val (slug, tail) = if (segments.first() == "stream") {
            if (segments.size < 2) {
                return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld stream route slug is missing")
            }
            segments[1] to segments.drop(2)
        } else {
            segments.first() to segments.drop(1)
        }
        if (!SLUG_PATTERN.matches(slug)) {
            return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld series slug is invalid")
        }
        val parsedTail = parseTail(tail)
            ?: return failure(
                AniWorldRouteFailureKind.INVALID_PATH,
                "AniWorld route contains an unsupported or incomplete season, episode or film path",
            )
        return route(
            slug = slug,
            season = parsedTail.season,
            installment = parsedTail.installment,
            kind = parsedTail.kind,
        )
    }

    private fun parseTail(segments: List<String>): ParsedTail? {
        if (segments.isEmpty()) {
            return ParsedTail(null, null, AniWorldCanonicalRouteKind.SERIES)
        }
        if (segments.first() == "filme") {
            if (segments.size == 1) {
                return ParsedTail(null, null, AniWorldCanonicalRouteKind.FILMS_OVERVIEW)
            }
            if (segments.size != 2) return null
            val match = FILM_SEGMENT.matchEntire(segments[1]) ?: return null
            val number = match.groupValues[1].toIntOrNull() ?: return null
            return ParsedTail(null, Installment.Film(number), AniWorldCanonicalRouteKind.FILM)
        }

        val seasonMarker = readSeasonMarker(segments.first(), segments.getOrNull(1))
        if (seasonMarker is MarkerResult.Invalid) return null
        if (seasonMarker is MarkerResult.Valid) {
            val remaining = segments.drop(seasonMarker.consumed)
            if (remaining.isEmpty()) {
                return ParsedTail(seasonMarker.value, null, AniWorldCanonicalRouteKind.SEASON)
            }
            val episodeMarker = readEpisodeMarker(remaining.first(), remaining.getOrNull(1))
            if (episodeMarker !is MarkerResult.Valid || remaining.size != episodeMarker.consumed) {
                return null
            }
            return ParsedTail(
                seasonMarker.value,
                episodeMarker.value,
                AniWorldCanonicalRouteKind.EPISODE,
            )
        }

        val episodeMarker = readEpisodeMarker(segments.first(), segments.getOrNull(1))
        if (episodeMarker !is MarkerResult.Valid || segments.size != episodeMarker.consumed) {
            return null
        }
        return ParsedTail(null, episodeMarker.value, AniWorldCanonicalRouteKind.EPISODE)
    }

    private fun readSeasonMarker(current: String, next: String?): MarkerResult<Int> {
        val combined = COMBINED_SEASON.matchEntire(current)
        if (combined != null) return combined.toNumberOrInvalid(consumed = 1)
        if (current == "staffel" || current == "season") {
            val number = next?.toIntOrNull()
            return if (number != null && number >= 0) {
                MarkerResult.Valid(number, consumed = 2)
            } else {
                MarkerResult.Invalid
            }
        }
        if (current.startsWith("staffel") || current.startsWith("season")) {
            return MarkerResult.Invalid
        }
        return MarkerResult.None
    }

    private fun readEpisodeMarker(current: String, next: String?): MarkerResult<Installment.Episode> {
        val combined = COMBINED_EPISODE.matchEntire(current)
        if (combined != null) {
            val number = combined.groupValues[2].toIntOrNull()
            val fraction = combined.groupValues.getOrNull(3)?.takeIf(String::isNotEmpty)?.toIntOrNull()
            return if (number != null && fraction != null && fraction !in 1..99) {
                MarkerResult.Invalid
            } else if (number != null) {
                MarkerResult.Valid(Installment.Episode(number, fraction), consumed = 1)
            } else {
                MarkerResult.Invalid
            }
        }
        if (current == "episode" || current == "folge" || current == "ep") {
            val number = next?.toIntOrNull()
            return if (number != null && number >= 0) {
                MarkerResult.Valid(Installment.Episode(number), consumed = 2)
            } else {
                MarkerResult.Invalid
            }
        }
        if (current.startsWith("episode") || current.startsWith("folge") || current.startsWith("ep")) {
            return MarkerResult.Invalid
        }
        return MarkerResult.None
    }

    private fun route(
        slug: String,
        season: Int?,
        installment: Installment?,
        kind: AniWorldCanonicalRouteKind,
    ): AniWorldCanonicalRouteResult.Success = AniWorldCanonicalRouteResult.Success(
        AniWorldCanonicalRoute(
            slug = slug,
            canonicalSeriesPath = "/anime/stream/$slug",
            canonicalSeriesUrl = "https://${AniWorldSiteIdentifierHost.HOST}/anime/stream/$slug",
            season = season,
            installment = installment,
            kind = kind,
        ),
    )

    private fun seriesRoute(slug: String): AniWorldCanonicalRouteResult {
        if (!SLUG_PATTERN.matches(slug)) {
            return failure(AniWorldRouteFailureKind.INVALID_PATH, "AniWorld series slug is invalid")
        }
        return route(slug, null, null, AniWorldCanonicalRouteKind.SERIES)
    }

    private fun normalizePathSegment(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        if (normalized.isEmpty() || normalized.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
            return null
        }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun failure(kind: AniWorldRouteFailureKind, diagnostic: String) =
        AniWorldCanonicalRouteResult.Failure(kind, diagnostic)

    private data class ParsedTail(
        val season: Int?,
        val installment: Installment?,
        val kind: AniWorldCanonicalRouteKind,
    )

    private sealed interface MarkerResult<out T> {
        data object None : MarkerResult<Nothing>
        data object Invalid : MarkerResult<Nothing>
        data class Valid<T>(val value: T, val consumed: Int) : MarkerResult<T>
    }

    private fun MatchResult.toNumberOrInvalid(consumed: Int): MarkerResult<Int> =
        groupValues[2].toIntOrNull()?.let { value ->
            if (value >= 0) MarkerResult.Valid(value, consumed) else MarkerResult.Invalid
        } ?: MarkerResult.Invalid

    private val SLUG_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._~-]*")
    private val COMBINED_SEASON = Regex("(?i)^(staffel|season)[-_]?(\\d{1,3})$")
    private val COMBINED_EPISODE = Regex("(?i)^(episode|folge|ep)[-_]?(\\d{1,4})(?:[._-](\\d{1,2}))?$")
    private val FILM_SEGMENT = Regex("(?i)^film-(\\d{1,4})$")
}

/**
 * Resolves a card's explicit key and href together. A disagreement is never
 * resolved by precedence because that would manufacture an identity.
 */
object AniWorldCardRouteResolver {
    fun resolve(
        sourceUrl: String,
        sourceKey: String?,
        href: String?,
    ): AniWorldCardRouteResult {
        val attributeValue = sourceKey?.trim()?.takeIf(String::isNotBlank)
        val hrefValue = href?.trim()?.takeIf(String::isNotBlank)
        val attributeRoute = attributeValue?.let { AniWorldCanonicalRouteParser.parseSourceKey(it) }
        if (attributeRoute is AniWorldCanonicalRouteResult.Failure) {
            return AniWorldCardRouteResult.Failure(
                "data-source-key route rejected: ${attributeRoute.diagnostic}".take(256),
            )
        }

        if (hrefValue != null) {
            val linkUri = runCatching { URI(sourceUrl).resolve(hrefValue) }.getOrNull()
                ?: return AniWorldCardRouteResult.Failure("card href is not a valid URL")
            val linkRoute = AniWorldCanonicalRouteParser.parse(linkUri.toString())
            if (linkRoute is AniWorldCanonicalRouteResult.Failure) {
                return AniWorldCardRouteResult.Failure(
                    "card href route rejected: ${linkRoute.diagnostic}".take(256),
                )
            }
            val linkSuccess = linkRoute as AniWorldCanonicalRouteResult.Success
            val attributeSuccess = attributeRoute as? AniWorldCanonicalRouteResult.Success
            if (attributeSuccess != null && attributeSuccess.route.slug != linkSuccess.route.slug) {
                return AniWorldCardRouteResult.Failure(
                    "card data-source-key conflicts with href series identity",
                )
            }
            return AniWorldCardRouteResult.Success(linkSuccess.route)
        }

        val attributeSuccess = attributeRoute as? AniWorldCanonicalRouteResult.Success
        if (attributeSuccess != null) return AniWorldCardRouteResult.Success(attributeSuccess.route)

        return when (val pageRoute = AniWorldCanonicalRouteParser.parse(sourceUrl)) {
            is AniWorldCanonicalRouteResult.Success -> AniWorldCardRouteResult.Success(pageRoute.route)
            is AniWorldCanonicalRouteResult.Failure -> AniWorldCardRouteResult.Failure(
                "card has no valid AniWorld route: ${pageRoute.diagnostic}".take(256),
            )
        }
    }
}

private object AniWorldSiteIdentifierHost {
    const val HOST = "aniworld.to"
}

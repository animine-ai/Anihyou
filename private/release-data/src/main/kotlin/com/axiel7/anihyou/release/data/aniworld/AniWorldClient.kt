package com.axiel7.anihyou.release.data.aniworld

import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException

/** Transport performs one transaction. This client validates each next hop before contact. */
class AniWorldClient(
    private val transport: AniWorldHttpTransport,
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    private val limits: AniWorldLimits = AniWorldLimits(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(allowedHosts.isNotEmpty())
        require(timeoutMillis > 0)
    }

    suspend fun fetch(request: AniWorldPageRequest): AniWorldClientResult {
        var uri = parseAllowedUri(request.url) ?: return failure(
            AniWorldFailureKind.REDIRECT_HOST, "requested URL is not an allowed HTTPS URL",
        )
        val visited = mutableSetOf<String>()
        var redirects = 0
        while (true) {
            if (!visited.add(uri.normalize().toString()))
                return failure(AniWorldFailureKind.REDIRECT_HOST, "redirect loop")
            val response = try {
                transport.fetch(AniWorldTransportRequest(
                    url = uri.toString(), maxBytes = limits.maxBodyBytes, timeoutMillis = timeoutMillis,
                    // Validators refer to the original exact URL; do not forward across redirects.
                    ifNoneMatch = request.ifNoneMatch?.takeIf { redirects == 0 },
                    ifModifiedSince = request.ifModifiedSince?.takeIf { redirects == 0 },
                ))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                return failure(AniWorldFailureKind.TRANSPORT_FAILURE,
                    "provider transport failed closed: " + (exception::class.simpleName ?: "transport-error"))
            }
            if (response.finalUrl != uri.toString())
                return failure(AniWorldFailureKind.REDIRECT_HOST, "transport changed the requested URL")
            if (response.bodyTooLarge || response.rawBodyBytes > limits.maxBodyBytes ||
                response.body.toByteArray(Charsets.UTF_8).size > limits.maxBodyBytes
            ) return failure(AniWorldFailureKind.OVERSIZED_BODY, "bounded body limit exceeded")

            if (response.statusCode in REDIRECT_STATUSES) {
                if (redirects >= MAX_REDIRECTS)
                    return failure(AniWorldFailureKind.REDIRECT_HOST, "redirect hop limit")
                val location = response.location?.takeIf { it.isNotBlank() && it.length <= 2048 }
                    ?: return failure(AniWorldFailureKind.REDIRECT_HOST, "redirect Location missing or invalid")
                val next = runCatching { uri.resolve(URI(location)) }.getOrNull()
                    ?: return failure(AniWorldFailureKind.REDIRECT_HOST, "redirect Location malformed")
                uri = parseAllowedUri(next.toString())
                    ?: return failure(AniWorldFailureKind.REDIRECT_HOST, "redirect target outside HTTPS allowlist")
                redirects++
                continue
            }
            if (response.statusCode == 429) return failure(
                AniWorldFailureKind.RATE_LIMITED, "provider returned HTTP 429",
                retryAfterSeconds = parseRetryAfter(response.retryAfter),
            )
            if (response.statusCode == 304) return failure(
                AniWorldFailureKind.NOT_MODIFIED_CACHE_MISS,
                "HTTP 304 cannot supply a page without an exact-URL cached representation",
            )
            if (response.statusCode !in 200..299) return failure(
                AniWorldFailureKind.HTTP_STATUS, "provider returned HTTP " + response.statusCode,
            )
            if (!isHtml(response.contentType)) return failure(
                AniWorldFailureKind.NON_HTML_CONTENT, "provider response is not HTML",
            )
            if (response.body.isBlank()) return failure(
                AniWorldFailureKind.EMPTY_STRUCTURE, "provider response body is empty",
            )
            return AniWorldClientResult.Success(response)
        }
    }

    private fun parseRetryAfter(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.length in 1..128 } ?: return null
        val seconds = if (value.all(Char::isDigit)) {
            value.toLongOrNull() ?: MAX_RETRY_AFTER_SECONDS
        } else {
            val date = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
                .getOrNull() ?: return null
            runCatching { Duration.between(clock.instant(), date).seconds }.getOrNull()
        }
        return seconds?.takeIf { it >= 0 }?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
    }

    suspend fun fetchRecent(sourceRoot: String): AniWorldClientResult = fetch(
        AniWorldPageRequest(AniWorldPageRole.RECENT_CURRENT, URI(sourceRoot).resolve("/neue-episoden").toString()),
    )

    suspend fun fetchCalendar(sourceRoot: String): AniWorldClientResult = fetch(
        AniWorldPageRequest(AniWorldPageRole.FUTURE_CALENDAR, URI(sourceRoot).resolve("/animekalender").toString()),
    )

    private fun parseAllowedUri(value: String): URI? = runCatching {
        URI(value).takeIf { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null && allowedHosts.any { it.equals(uri.host, ignoreCase = true) } &&
                uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443)
        }
    }.getOrNull()

    private fun isHtml(contentType: String?): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim().orEmpty()
        return mediaType.equals("text/html", ignoreCase = true) ||
            mediaType.equals("application/xhtml+xml", ignoreCase = true)
    }

    private fun failure(kind: AniWorldFailureKind, message: String, retryAfterSeconds: Long? = null) =
        AniWorldClientResult.Failure(kind, message, retryAfterSeconds)

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val MAX_REDIRECTS = 5
        /** Six hours is a bounded scheduling hint; transport never waits or retries. */
        const val MAX_RETRY_AFTER_SECONDS = 6 * 60 * 60L
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf("aniworld.to", "www.aniworld.to")
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}

sealed interface AniWorldClientResult {
    data class Success(val response: AniWorldHttpResponse) : AniWorldClientResult
    data class Failure(
        val kind: AniWorldFailureKind,
        val diagnostic: String,
        val retryAfterSeconds: Long? = null,
    ) : AniWorldClientResult
}

package com.axiel7.anihyou.release.data.aniworld

import java.net.URI

class AniWorldClient(
    private val transport: AniWorldHttpTransport,
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    private val limits: AniWorldLimits = AniWorldLimits(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(allowedHosts.isNotEmpty()) { "at least one allowed host is required" }
        require(timeoutMillis > 0) { "timeout must be positive" }
    }

    suspend fun fetch(request: AniWorldPageRequest): AniWorldClientResult {
        val requestedUri = parseHttpsUri(request.url)
            ?: return AniWorldClientResult.Failure(
                AniWorldFailureKind.REDIRECT_HOST,
                "requested source URL is not a valid HTTPS URL",
            )
        if (!isAllowedHost(requestedUri)) {
            return AniWorldClientResult.Failure(
                AniWorldFailureKind.REDIRECT_HOST,
                "requested source host is outside the provider allowlist",
            )
        }

        val response = transport.fetch(
            AniWorldTransportRequest(
                url = requestedUri.toString(),
                maxBytes = limits.maxBodyBytes,
                timeoutMillis = timeoutMillis,
            ),
        )
        if (response.statusCode !in 200..299) {
            return AniWorldClientResult.Failure(
                AniWorldFailureKind.HTTP_STATUS,
                "provider returned HTTP " + response.statusCode,
            )
        }
        val finalUri = parseHttpsUri(response.finalUrl)
            ?: return AniWorldClientResult.Failure(
                AniWorldFailureKind.REDIRECT_HOST,
                "provider response did not contain a valid HTTPS final URL",
            )
        if (!isAllowedHost(finalUri)) {
            return AniWorldClientResult.Failure(
                AniWorldFailureKind.REDIRECT_HOST,
                "provider redirect ended outside the provider allowlist",
            )
        }
        if (!isHtml(response.contentType)) {
            return AniWorldClientResult.Failure(
                AniWorldFailureKind.NON_HTML_CONTENT,
                "provider response is not HTML",
            )
        }
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8).size
        if (bodyBytes > limits.maxBodyBytes) {
            return AniWorldClientResult.Failure(
                AniWorldFailureKind.OVERSIZED_BODY,
                "provider response exceeds the bounded body limit",
            )
        }
        if (response.body.isBlank()) {
            return AniWorldClientResult.Failure(
                AniWorldFailureKind.EMPTY_STRUCTURE,
                "provider response body is empty",
            )
        }
        return AniWorldClientResult.Success(
            response.copy(finalUrl = finalUri.toString()),
        )
    }

    suspend fun fetchRecent(sourceRoot: String): AniWorldClientResult =
        fetch(
            AniWorldPageRequest(
                role = AniWorldPageRole.RECENT_CURRENT,
                url = resolve(sourceRoot, "/neue-episoden"),
            ),
        )

    suspend fun fetchCalendar(sourceRoot: String): AniWorldClientResult =
        fetch(
            AniWorldPageRequest(
                role = AniWorldPageRole.FUTURE_CALENDAR,
                url = resolve(sourceRoot, "/animekalender"),
            ),
        )

    private fun resolve(sourceRoot: String, path: String): String =
        URI(sourceRoot).resolve(path).toString()

    private fun parseHttpsUri(value: String): URI? = runCatching {
        URI(value).takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() }
    }.getOrNull()

    private fun isAllowedHost(uri: URI): Boolean =
        allowedHosts.any { it.equals(uri.host, ignoreCase = true) }

    private fun isHtml(contentType: String?): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim().orEmpty()
        return mediaType.equals("text/html", ignoreCase = true) ||
            mediaType.equals("application/xhtml+xml", ignoreCase = true)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf("aniworld.to", "www.aniworld.to")
    }
}

sealed interface AniWorldClientResult {
    data class Success(
        val response: AniWorldHttpResponse,
    ) : AniWorldClientResult

    data class Failure(
        val kind: AniWorldFailureKind,
        val diagnostic: String,
    ) : AniWorldClientResult
}

package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.ShadowPollStore
import com.axiel7.anihyou.release.core.api.ShadowGenerationManifest
import com.axiel7.anihyou.release.core.api.ShadowRequestOutcome
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class AniWorldRequestBudgetDeniedException(message: String) : IllegalStateException(message)

/** Reserves durable budget before every network hop, including redirects. */
class BudgetedAniWorldHttpTransport(
    private val delegate: AniWorldHttpTransport,
    private val store: ShadowPollStore,
    private val manifest: ShadowGenerationManifest,
    private val clock: Clock = Clock.systemUTC(),
    private val permits: Semaphore = GLOBAL_V3_PERMITS,
) : AniWorldHttpTransport {
    private val rootByUrl = ConcurrentHashMap<String, String>()
    private val rootStartedNanos = ConcurrentHashMap<String, Long>()
    private val directRoots = manifest.sources.filter { it.sourceType == "ANIWORLD_DIRECT_PAGE" }.map { it.requestUrl }.toSet()

    override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse {
        val url = canonicalUrl(request.url) ?: throw IllegalArgumentException("unsafe request URL")
        val root = rootByUrl[url] ?: url.also { rootByUrl[it] = it }
        val role = if (root in directRoots) "DIRECT" else when {
            root == "https://aniworld.to/animekalender" -> "CALENDAR"
            root == "https://aniworld.to/neue-episoden" -> "RECENT"
            root == "https://aniworld.to/support/frage/anime-verschiebungen" -> "POSTPONEMENT"
            else -> throw IllegalArgumentException("URL is outside the generation manifest")
        }
        val now = clock.instant()
        if (!now.isBefore(manifest.deadlineAt)) throw SocketTimeoutException("generation deadline reached")
        val rootStarted = rootStartedNanos.computeIfAbsent(root) { System.nanoTime() }
        val sourceRemaining = SOURCE_TIMEOUT_NANOS - (System.nanoTime() - rootStarted)
        if (sourceRemaining <= 0) throw SocketTimeoutException("source absolute deadline reached")
        val reservation = store.reserveRequest(manifest.token, role, root, url, now)
            ?: throw AniWorldRequestBudgetDeniedException("durable request budget or cooldown denied this hop")
        val started = System.nanoTime()
        try {
            val generationRemainingMs = Duration.between(now, manifest.deadlineAt).toMillis()
            val timeoutMs = minOf(TimeUnit.NANOSECONDS.toMillis(sourceRemaining).coerceAtLeast(1),
                generationRemainingMs.coerceAtLeast(1))
            val response = withTimeoutOrNull(timeoutMs) {
                permits.withPermit {
                    delegate.fetch(request.copy(timeoutMillis = minOf(request.timeoutMillis, MAX_HOP_TIMEOUT_MS)))
                }
            } ?: throw SocketTimeoutException("AniWorld request deadline reached")
            if (response.statusCode in REDIRECTS && response.location != null) {
                val target = runCatching { URI(url).resolve(response.location).normalize().toString() }.getOrNull()
                target?.let { canonicalUrl(it) }?.let { rootByUrl[it] = root }
            }
            val status = when {
                response.statusCode in 200..299 -> "HTTP_2XX"
                response.statusCode == 429 -> "HTTP_429"
                response.statusCode == 304 -> "HTTP_304"
                response.statusCode in REDIRECTS -> "REDIRECT"
                else -> "HTTP_${response.statusCode}"
            }
            store.completeRequest(reservation, ShadowRequestOutcome(status, clock.instant(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                if (response.statusCode == 429) retryAfter(response.retryAfter) else null))
            return response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            store.completeRequest(reservation, ShadowRequestOutcome("TRANSPORT_FAILURE", clock.instant(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)))
            throw failure
        }
    }

    private fun canonicalUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || uri.host?.lowercase() !in setOf("aniworld.to", "www.aniworld.to") ||
            uri.userInfo != null || uri.port !in setOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath.contains("%2f", true) || uri.rawPath.split('/').any { it == "." || it == ".." }) return null
        URI("https", null, "aniworld.to", -1, uri.path, null, null).normalize().toString()
    }.getOrNull()

    private fun retryAfter(header: String?): Long? {
        val value = header?.trim()?.takeIf { it.length in 1..256 } ?: return null
        val seconds = value.toLongOrNull() ?: runCatching {
            Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
        }.getOrNull() ?: return null
        return seconds.coerceIn(0, 21_600)
    }

    companion object {
        private val GLOBAL_V3_PERMITS = Semaphore(2)
        private const val MAX_HOP_TIMEOUT_MS = 10_000L
        private val SOURCE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30)
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

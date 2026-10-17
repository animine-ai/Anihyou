package com.axiel7.anihyou.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * What the app knows of AniList's rate limit. AniList answers 429 once the requests of a minute are used up (it has been in a
 * degraded state of 30 a minute for years), and every part of the app asks on its own: the matcher's first run alone sends
 * some forty requests in a second, and the cover of a notification, the list that decides it and the lists of the library
 * were the ones that then failed. One shared state makes all of them wait for the same moment instead of failing.
 */
class AniListRateLimit(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** A request waits at most this long for the limit to lift; beyond it the request fails at once, without a call. */
    val maxWaitMillis: Long = MAX_WAIT_MILLIS,
) {
    private var blockedUntil = 0L

    /** How long a request has to wait before it may go out: 0 to go on, null if the limit lasts longer than [maxWaitMillis]. */
    @Synchronized
    fun delayBeforeRequest(): Long? {
        val wait = blockedUntil - nowMillis()
        return when {
            wait <= 0L -> 0L
            wait <= maxWaitMillis -> wait
            else -> null
        }
    }

    /** AniList said 429; [retryAfterSeconds] is its Retry-After header, if it sent one. */
    @Synchronized
    fun rateLimited(retryAfterSeconds: Long?) {
        val seconds = (retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
        blockedUntil = maxOf(blockedUntil, nowMillis() + seconds * 1_000L)
    }

    /** The quota headers of any answer: with nothing left, the next request would only be refused, so it waits for the reset. */
    @Synchronized
    fun quota(remaining: Int?, resetEpochSeconds: Long?) {
        if (remaining == null || remaining > 0 || resetEpochSeconds == null) return
        val wait = (resetEpochSeconds * 1_000L - nowMillis()).coerceIn(0L, MAX_RETRY_AFTER_SECONDS * 1_000L)
        blockedUntil = maxOf(blockedUntil, nowMillis() + wait)
    }

    /** Seconds until the limit lifts, at least one while it holds. */
    @Synchronized
    fun retryAfterSeconds(): Long = ((blockedUntil - nowMillis() + 999L) / 1_000L).coerceAtLeast(1L)

    companion object {
        const val MAX_WAIT_MILLIS = 20_000L
        const val DEFAULT_RETRY_AFTER_SECONDS = 10L
        const val MAX_RETRY_AFTER_SECONDS = 120L
    }
}

/**
 * Holds a request back while AniList's limit is on, and sends it again when AniList refuses it with 429 and says the limit
 * lifts soon. A limit that lasts longer fails the request at once with a 429 of its own (same body as AniList's, so the
 * callers' messages do not change) instead of calling AniList again and prolonging it.
 */
class AniListRateLimitInterceptor(
    private val limit: AniListRateLimit = AniListRateLimit(),
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val maxRetries: Int = MAX_RETRIES,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var retries = 0
        while (true) {
            val wait = limit.delayBeforeRequest() ?: return rejected(chain.request())
            if (wait > 0L) waitFor(chain, wait)
            val response = chain.proceed(chain.request())
            limit.quota(response.header("X-RateLimit-Remaining")?.toIntOrNull(), response.header("X-RateLimit-Reset")?.toLongOrNull())
            if (response.code != TOO_MANY_REQUESTS) return response
            limit.rateLimited(response.header("Retry-After")?.trim()?.toLongOrNull())
            if (retries++ >= maxRetries || limit.delayBeforeRequest() == null) return response
            response.close()
        }
    }

    /** In slices, so a cancelled call does not keep its thread asleep for the whole wait. */
    private fun waitFor(chain: Interceptor.Chain, millis: Long) {
        var left = millis
        while (left > 0L) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val slice = minOf(left, SLICE_MILLIS)
            sleep(slice)
            left -= slice
        }
        if (chain.call().isCanceled()) throw IOException("Canceled")
    }

    private fun rejected(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(TOO_MANY_REQUESTS)
        .message("Too Many Requests")
        .header("Retry-After", limit.retryAfterSeconds().toString())
        .body(REJECTED_BODY.toResponseBody("application/json".toMediaType()))
        .build()

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val MAX_RETRIES = 2
        const val SLICE_MILLIS = 250L
        const val REJECTED_BODY = """{"errors":[{"message":"Too Many Requests.","status":429}],"data":null}"""
    }
}

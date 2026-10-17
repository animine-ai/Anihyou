package com.axiel7.anihyou.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListRateLimitTest {
    private var now = 1_000_000L
    private val limit = AniListRateLimit(nowMillis = { now }, maxWaitMillis = 20_000L)

    @Test fun nothingWaitsUntilAniListRefuses() {
        assertEquals(0L, limit.delayBeforeRequest())
    }

    @Test fun aRefusalMakesRequestsWaitForTheRetryAfterAndThenGoOn() {
        limit.rateLimited(retryAfterSeconds = 12)
        assertEquals(12_000L, limit.delayBeforeRequest())
        now += 5_000L
        assertEquals(7_000L, limit.delayBeforeRequest())
        now += 7_000L
        assertEquals(0L, limit.delayBeforeRequest())
    }

    @Test fun aLimitBeyondTheLongestWaitFailsTheRequestInsteadOfHoldingItBack() {
        limit.rateLimited(retryAfterSeconds = 60)
        assertNull(limit.delayBeforeRequest())
        now += 41_000L
        assertEquals(19_000L, limit.delayBeforeRequest())
    }

    @Test fun withoutRetryAfterTheDefaultAppliesAndAHugeOneIsCapped() {
        limit.rateLimited(null)
        assertEquals(AniListRateLimit.DEFAULT_RETRY_AFTER_SECONDS * 1_000L, limit.delayBeforeRequest())
        now += 100_000L
        limit.rateLimited(9_999)
        assertEquals(AniListRateLimit.MAX_RETRY_AFTER_SECONDS, limit.retryAfterSeconds())
    }

    @Test fun anEmptyQuotaWaitsForTheResetButAFullOneDoesNot() {
        limit.quota(remaining = 5, resetEpochSeconds = now / 1_000L + 30)
        assertEquals(0L, limit.delayBeforeRequest())
        limit.quota(remaining = 0, resetEpochSeconds = now / 1_000L + 8)
        assertEquals(8_000L, limit.delayBeforeRequest())
        limit.quota(remaining = 0, resetEpochSeconds = now / 1_000L - 100)
        assertEquals(8_000L, limit.delayBeforeRequest())
    }

    @Test fun aShorterLimitNeverShortensALongerOne() {
        limit.rateLimited(15)
        limit.rateLimited(3)
        assertEquals(15_000L, limit.delayBeforeRequest())
    }

    // --- the interceptor, with a network that answers from a script -------------------------------------------------

    private class Network(private val answers: ArrayDeque<Pair<Int, Map<String, String>>>) : Interceptor {
        var calls = 0
        override fun intercept(chain: Interceptor.Chain): Response {
            calls++
            val (code, headers) = answers.removeFirst()
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 429) "Too Many Requests" else "OK")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }
    }

    private val slept = mutableListOf<Long>()

    private fun call(vararg answers: Pair<Int, Map<String, String>>, maxRetries: Int = 2): Pair<Response, Network> {
        val network = Network(ArrayDeque(answers.toList()))
        val client = OkHttpClient.Builder()
            .addInterceptor(AniListRateLimitInterceptor(limit, sleep = { millis -> slept += millis; now += millis }, maxRetries = maxRetries))
            .addInterceptor(network)
            .build()
        return client.newCall(Request.Builder().url("https://graphql.anilist.co/").build()).execute() to network
    }

    @Test fun aRefusalWithAShortRetryAfterIsSentAgainAfterTheWait() {
        val (response, network) = call(429 to mapOf("Retry-After" to "2"), 200 to emptyMap())
        assertEquals(200, response.code)
        assertEquals(2, network.calls)
        assertEquals(2_000L, slept.sum())
    }

    @Test fun aRefusalWithALongRetryAfterIsHandedBackAtOnceAndTheNextRequestIsNotSentAtAll() {
        val (first, firstNetwork) = call(429 to mapOf("Retry-After" to "60"))
        assertEquals(429, first.code)
        assertEquals(1, firstNetwork.calls)
        assertTrue(slept.isEmpty())
        val (second, secondNetwork) = call(200 to emptyMap())
        assertEquals(429, second.code)
        assertEquals(0, secondNetwork.calls)
        assertTrue(second.header("Retry-After")!!.toLong() in 1..60)
        assertTrue(second.body!!.string().contains("Too Many Requests."))
    }

    @Test fun theRetriesStopAtTheirNumber() {
        val (response, network) = call(
            429 to mapOf("Retry-After" to "1"), 429 to mapOf("Retry-After" to "1"), 429 to mapOf("Retry-After" to "1"), 200 to emptyMap(),
        )
        assertEquals(429, response.code)
        assertEquals(3, network.calls)
    }

    @Test fun aRequestWaitsForAnEarlierRefusalOfAnotherCaller() {
        limit.rateLimited(retryAfterSeconds = 4)
        val (response, network) = call(200 to emptyMap())
        assertEquals(200, response.code)
        assertEquals(1, network.calls)
        assertEquals(4_000L, slept.sum())
        assertTrue(slept.all { it <= 250L })
    }
}

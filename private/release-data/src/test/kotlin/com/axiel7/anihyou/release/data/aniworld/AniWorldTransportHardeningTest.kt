package com.axiel7.anihyou.release.data.aniworld

import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AniWorldTransportHardeningTest {
    private val start = "https://aniworld.to/neue-episoden"
    private val page = AniWorldPageRequest(AniWorldPageRole.RECENT_CURRENT, start)
    private val clock = Clock.fixed(Instant.parse("2026-09-26T07:00:00Z"), ZoneOffset.UTC)

    private class Fake(val responder: (AniWorldTransportRequest) -> AniWorldHttpResponse) : AniWorldHttpTransport {
        val requests = mutableListOf<AniWorldTransportRequest>()
        override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse {
            requests += request
            return responder(request)
        }
    }
    private fun ok(request: AniWorldTransportRequest, body: String = "<main>ok</main>") =
        AniWorldHttpResponse(200, "text/html", request.url, body)
    private fun redirect(request: AniWorldTransportRequest, to: String, code: Int = 302) =
        AniWorldHttpResponse(code, null, request.url, "", location = to)

    @Test fun sameHostRelativeAndApexWwwRedirects() = runBlocking {
        listOf("/next", "https://aniworld.to/next", "https://www.aniworld.to/next").forEach { target ->
            val fake = Fake { r -> if (r.url == start) redirect(r, target) else ok(r) }
            val result = AniWorldClient(fake).fetch(page) as AniWorldClientResult.Success
            assertEquals(2, fake.requests.size)
            assertEquals(if (target.startsWith("/")) "https://aniworld.to/next" else target,
                result.response.finalUrl)
        }
    }

    @Test fun everyRedirectStatusIsFollowedOnce() = runBlocking {
        listOf(301, 302, 303, 307, 308).forEach { status ->
            val fake = Fake { r -> if (r.url == start) redirect(r, "/next", status) else ok(r) }
            assertTrue(AniWorldClient(fake).fetch(page) is AniWorldClientResult.Success)
            assertEquals(2, fake.requests.size)
        }
    }

    @Test fun foreignHopAndBounceAreRejectedBeforeContact() = runBlocking {
        for (first in listOf("https://evil.example/return", "/safe")) {
            val fake = Fake { r ->
                when (r.url) {
                    start -> redirect(r, first)
                    "https://aniworld.to/safe" -> redirect(r, "https://evil.example/return")
                    else -> error("unsafe network contact: " + r.url)
                }
            }
            val result = AniWorldClient(fake).fetch(page) as AniWorldClientResult.Failure
            assertEquals(AniWorldFailureKind.REDIRECT_HOST, result.kind)
            assertTrue(fake.requests.none { it.url.contains("evil.example") })
            assertEquals(if (first == "/safe") 2 else 1, fake.requests.size)
        }
    }

    @Test fun downgradeMalformedAndMissingLocationAreRejected() = runBlocking {
        for (target in listOf<String?>("http://aniworld.to/next", "https://[invalid", null, "")) {
            val fake = Fake { r -> AniWorldHttpResponse(302, null, r.url, "", location = target) }
            assertEquals(AniWorldFailureKind.REDIRECT_HOST,
                (AniWorldClient(fake).fetch(page) as AniWorldClientResult.Failure).kind)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test fun selfAndMultiUrlLoopsFailBeforeRepeatContact() = runBlocking {
        val self = Fake { r -> redirect(r, start) }
        assertTrue(AniWorldClient(self).fetch(page) is AniWorldClientResult.Failure)
        assertEquals(1, self.requests.size)
        val pair = Fake { r -> redirect(r, if (r.url == start) "/next" else start) }
        assertTrue(AniWorldClient(pair).fetch(page) is AniWorldClientResult.Failure)
        assertEquals(2, pair.requests.size)
    }

    @Test fun hopLimitAllowsFiveAndRejectsSixth() = runBlocking {
        val exact = Fake { r ->
            val n = r.url.substringAfterLast('/').toIntOrNull() ?: 0
            if (n < 5) redirect(r, "/" + (n + 1)) else ok(r)
        }
        assertTrue(AniWorldClient(exact).fetch(page) is AniWorldClientResult.Success)
        assertEquals(6, exact.requests.size)
        val extra = Fake { r ->
            val n = r.url.substringAfterLast('/').toIntOrNull() ?: 0
            redirect(r, "/" + (n + 1))
        }
        assertTrue(AniWorldClient(extra).fetch(page) is AniWorldClientResult.Failure)
        assertEquals(6, extra.requests.size)
    }

    @Test fun ordinaryBodySizeContentTypeAndBlankChecks() = runBlocking {
        val accepted = Fake { ok(it, "12345") }
        assertTrue(AniWorldClient(accepted, limits = AniWorldLimits(maxBodyBytes = 5)).fetch(page)
            is AniWorldClientResult.Success)
        val large = Fake { ok(it, "123456") }
        assertEquals(AniWorldFailureKind.OVERSIZED_BODY,
            (AniWorldClient(large, limits = AniWorldLimits(maxBodyBytes = 5)).fetch(page)
                as AniWorldClientResult.Failure).kind)
        val rawOverflow = Fake { AniWorldHttpResponse(200, "text/html", it.url, "",
            bodyTooLarge = true, rawBodyBytes = 6) }
        assertEquals(AniWorldFailureKind.OVERSIZED_BODY,
            (AniWorldClient(rawOverflow, limits = AniWorldLimits(maxBodyBytes = 5)).fetch(page)
                as AniWorldClientResult.Failure).kind)
        for ((body, type, expected) in listOf(
            Triple("ok", "application/json", AniWorldFailureKind.NON_HTML_CONTENT),
            Triple(" ", "text/html", AniWorldFailureKind.EMPTY_STRUCTURE),
        )) {
            val fake = Fake { AniWorldHttpResponse(200, type, it.url, body) }
            assertEquals(expected, (AniWorldClient(fake).fetch(page) as AniWorldClientResult.Failure).kind)
        }
    }

    @Test fun rateLimitParsesDeltaDateInvalidAndClampsWithoutRetry() = runBlocking {
        val cases = listOf(
            "120" to 120L,
            "Sat, 26 Sep 2026 07:02:00 GMT" to 120L,
            "garbage" to null,
            "-5" to null,
            "999999999" to AniWorldClient.MAX_RETRY_AFTER_SECONDS,
        )
        for ((header, expected) in cases) {
            val fake = Fake { AniWorldHttpResponse(429, null, it.url, "", retryAfter = header) }
            val result = AniWorldClient(fake, clock = clock).fetch(page) as AniWorldClientResult.Failure
            assertEquals(AniWorldFailureKind.RATE_LIMITED, result.kind)
            assertEquals(expected, result.retryAfterSeconds)
            assertEquals(1, fake.requests.size)
        }
        val unavailable = Fake { AniWorldHttpResponse(503, null, it.url, "") }
        assertEquals(AniWorldFailureKind.HTTP_STATUS,
            (AniWorldClient(unavailable).fetch(page) as AniWorldClientResult.Failure).kind)
    }

    @Test fun conditionalMetadataAndCacheMissDoNotCreatePage() = runBlocking {
        val fake = Fake { ok(it).copy(etag = "\"etag-1\"", lastModified = "Sat, 26 Sep 2026 07:00:00 GMT") }
        val result = AniWorldClient(fake).fetch(page.copy(ifNoneMatch = "\"etag-0\"",
            ifModifiedSince = "Fri, 25 Sep 2026 07:00:00 GMT")) as AniWorldClientResult.Success
        assertEquals("\"etag-0\"", fake.requests.single().ifNoneMatch)
        assertEquals("Fri, 25 Sep 2026 07:00:00 GMT", fake.requests.single().ifModifiedSince)
        assertEquals("\"etag-1\"", result.response.etag)
        assertNotNull(result.response.lastModified)
        val plain = Fake { ok(it) }
        AniWorldClient(plain).fetch(page)
        assertNull(plain.requests.single().ifNoneMatch)
        assertNull(plain.requests.single().ifModifiedSince)
        val notModified = Fake { AniWorldHttpResponse(304, null, it.url, "", etag = "\"etag-1\"") }
        assertEquals(AniWorldFailureKind.NOT_MODIFIED_CACHE_MISS,
            (AniWorldClient(notModified).fetch(page) as AniWorldClientResult.Failure).kind)
        val redirected = Fake { if (it.url == start) redirect(it, "/next") else ok(it) }
        AniWorldClient(redirected).fetch(page.copy(ifNoneMatch = "\"etag\""))
        assertNull(redirected.requests.last().ifNoneMatch)
    }

    @Test fun boundedStreamReadsOnlyLimitPlusOneBytes() {
        val bytes = ByteArray(100) { 'x'.code.toByte() }
        assertEquals(6, ByteArrayInputStream(bytes).readBounded(5).size)
        assertEquals(5, ByteArrayInputStream(bytes.copyOf(5)).readBounded(5).size)
    }

    @Test fun cancellationPropagates() {
        val cancellation = CancellationException("cancelled")
        val fake = Fake { throw cancellation }
        try {
            runBlocking { AniWorldClient(fake).fetch(page) }
            fail("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}

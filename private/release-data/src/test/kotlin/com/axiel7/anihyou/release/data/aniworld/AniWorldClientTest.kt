package com.axiel7.anihyou.release.data.aniworld

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldClientTest {
    private val request = AniWorldPageRequest(
        role = AniWorldPageRole.RECENT_CURRENT,
        url = "https://aniworld.to/neue-episoden",
    )

    @Test
    fun successfulHtmlResponsePreservesFinalUrlAndPassesBoundedRequest() = runBlocking {
        val transport = RecordingTransport(
            AniWorldHttpResponse(
                statusCode = 200,
                contentType = "text/html; charset=UTF-8",
                finalUrl = request.url,
                body = "<html>ok</html>",
            ),
        )
        val client = AniWorldClient(transport, limits = AniWorldLimits(maxBodyBytes = 100))

        val result = client.fetch(request)

        assertTrue(result is AniWorldClientResult.Success)
        assertEquals(request.url, transport.requests.single().url)
        assertEquals(100, transport.requests.single().maxBytes)
        assertEquals(30_000L, transport.requests.single().timeoutMillis)
    }

    @Test
    fun nonHtmlStatusRedirectAndOversizedResponsesFailTyped() = runBlocking {
        val nonHtml = clientFor(
            AniWorldHttpResponse(200, "application/json", request.url, "{}"),
        ).fetch(request)
        val status = clientFor(
            AniWorldHttpResponse(503, "text/html", request.url, "retry"),
        ).fetch(request)
        val redirect = clientFor(
            AniWorldHttpResponse(200, "text/html", "https://evil.example/login", "blocked"),
        ).fetch(request)
        val oversized = AniWorldClient(
            RecordingTransport(AniWorldHttpResponse(200, "text/html", request.url, "123456")),
            limits = AniWorldLimits(maxBodyBytes = 5),
        ).fetch(request)

        assertEquals(AniWorldFailureKind.NON_HTML_CONTENT, (nonHtml as AniWorldClientResult.Failure).kind)
        assertEquals(AniWorldFailureKind.HTTP_STATUS, (status as AniWorldClientResult.Failure).kind)
        assertEquals(AniWorldFailureKind.REDIRECT_HOST, (redirect as AniWorldClientResult.Failure).kind)
        assertEquals(AniWorldFailureKind.OVERSIZED_BODY, (oversized as AniWorldClientResult.Failure).kind)
    }

    @Test
    fun requestedHostAndBlankBodyFailBeforeNormalization() = runBlocking {
        val invalidHost = clientFor(
            AniWorldHttpResponse(200, "text/html", "https://aniworld.to/neue-episoden", "ok"),
        ).fetch(
            request.copy(url = "https://evil.example/neue-episoden"),
        )
        val blank = clientFor(
            AniWorldHttpResponse(200, "text/html", request.url, " "),
        ).fetch(request)

        assertEquals(AniWorldFailureKind.REDIRECT_HOST, (invalidHost as AniWorldClientResult.Failure).kind)
        assertEquals(AniWorldFailureKind.EMPTY_STRUCTURE, (blank as AniWorldClientResult.Failure).kind)
    }

    @Test
    fun cancellationIsNotConvertedIntoAProviderFailure() {
        val cancellation = CancellationException("cancelled")
        val transport = AniWorldHttpTransport { throw cancellation }
        val client = AniWorldClient(transport)

        try {
            runBlocking { client.fetch(request) }
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation, actual)
        }
    }

    @Test
    fun convenienceRequestsUseOnlyTheTwoProviderPageRoles() = runBlocking {
        val transport = RecordingTransport(
            AniWorldHttpResponse(200, "text/html", "https://aniworld.to/neue-episoden", "<html>ok</html>"),
        )
        val client = AniWorldClient(transport)

        client.fetchRecent("https://aniworld.to")
        transport.response = transport.response.copy(finalUrl = "https://aniworld.to/animekalender")
        client.fetchCalendar("https://aniworld.to")

        assertEquals(
            listOf("https://aniworld.to/neue-episoden", "https://aniworld.to/animekalender"),
            transport.requests.map { it.url },
        )
    }

    private fun clientFor(response: AniWorldHttpResponse): AniWorldClient =
        AniWorldClient(RecordingTransport(response))

    private class RecordingTransport(
        var response: AniWorldHttpResponse,
    ) : AniWorldHttpTransport {
        val requests = mutableListOf<AniWorldTransportRequest>()

        override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse {
            requests += request
            return response
        }
    }
}

package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.FailureKind
import com.axiel7.anihyou.release.core.api.ProviderFetchRequest
import com.axiel7.anihyou.release.core.api.ProviderFetchResult
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldProviderTest {
    private val observedAt = Instant.parse("2026-09-11T00:00:00Z")
    private val recentUrl = "https://aniworld.to/neue-episoden"
    private val calendarUrl = "https://aniworld.to/animekalender"

    @Test
    fun providerReturnsOneNormalizedSnapshotWithSeparateConfirmationAndForecast() = runBlocking {
        val transport = RecordingTransport(
            recent = response(recentUrl, recentHtml(LanguageTrack.DE_DUB, 9)),
            calendar = response(calendarUrl, calendarHtml(LanguageTrack.DE_DUB, 10)),
        )
        val provider = AniWorldProvider(
            client = AniWorldClient(transport),
            clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        )

        val result = provider.fetch(
            ProviderFetchRequest(
                range = LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30),
                tracks = setOf(LanguageTrack.DE_DUB),
            ),
        )

        val snapshot = (result as ProviderFetchResult.Success).snapshots.single()
        assertEquals(LanguageTrack.DE_DUB, snapshot.stream.languageTrack)
        assertEquals(1, snapshot.confirmations.size)
        assertEquals(Installment.Episode(9), snapshot.confirmations.single().identity.installment)
        assertEquals(1, snapshot.forecasts.size)
        assertEquals(Installment.Episode(10), snapshot.forecasts.single().identity.installment)
        assertEquals(Instant.parse("2026-09-16T18:15:00Z"), snapshot.forecasts.single().forecastAt)
        assertEquals(2, transport.requests.size)
        assertTrue(snapshot.freshness.sourceHash!!.isNotBlank())
    }

    @Test
    fun requestedTrackFiltersSubAndDubWithoutMergingThem() = runBlocking {
        val transport = RecordingTransport(
            recent = response(recentUrl, recentHtml(LanguageTrack.DE_SUB, 9)),
            calendar = response(calendarUrl, calendarHtml(LanguageTrack.DE_DUB, 10)),
        )
        val provider = AniWorldProvider(
            client = AniWorldClient(transport),
            clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        )

        val result = provider.fetch(
            ProviderFetchRequest(
                range = LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30),
                tracks = setOf(LanguageTrack.DE_DUB),
            ),
        )

        val snapshots = (result as ProviderFetchResult.Success).snapshots
        assertEquals(1, snapshots.size)
        assertEquals(LanguageTrack.DE_DUB, snapshots.single().stream.languageTrack)
        assertTrue(snapshots.single().confirmations.isEmpty())
        assertEquals(Installment.Episode(10), snapshots.single().forecasts.single().identity.installment)
    }

    @Test
    fun parserFailureMapsToTypedProviderFailureAndDoesNotReturnPartialData() = runBlocking {
        val transport = RecordingTransport(
            recent = response(
                recentUrl,
                "<html><head><title>Cloudflare challenge</title></head><body>verify you are human</body></html>",
            ),
            calendar = response(calendarUrl, calendarHtml(LanguageTrack.DE_DUB, 10)),
        )
        val provider = AniWorldProvider(
            client = AniWorldClient(transport),
            clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        )

        val result = provider.fetch(
            ProviderFetchRequest(
                range = LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30),
            ),
        )

        assertTrue(result is ProviderFetchResult.Failure)
        assertEquals(FailureKind.BLOCKED, (result as ProviderFetchResult.Failure).kind)
        assertTrue(transport.requests.single().url.endsWith("/neue-episoden"))
    }

    @Test
    fun calendarFailureAfterRecentSuccessStillFailsTheWholeFetch() = runBlocking {
        val transport = RecordingTransport(
            recent = response(recentUrl, recentHtml(LanguageTrack.DE_DUB, 9)),
            calendar = AniWorldHttpResponse(503, "text/html", calendarUrl, "retry"),
        )
        val provider = AniWorldProvider(
            client = AniWorldClient(transport),
            clock = Clock.fixed(observedAt, ZoneOffset.UTC),
        )

        val result = provider.fetch(
            ProviderFetchRequest(
                range = LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30),
            ),
        )

        assertTrue(result is ProviderFetchResult.Failure)
        assertEquals(FailureKind.NETWORK, (result as ProviderFetchResult.Failure).kind)
        assertEquals(2, transport.requests.size)
    }

    private fun recentHtml(track: LanguageTrack, episode: Int): String {
        val marker = if (track == LanguageTrack.DE_SUB) {
            "Deutsch Untertitel Flagge"
        } else {
            "Deutsche Flagge, German Flag"
        }
        return """
            <html><body><main><h1>Neue Episoden</h1>
            <article data-source-key="series-alpha">
              <a href="/anime/series-alpha">
                <img class="flag" title="Folge $episode" alt="$marker">
              </a>
            </article>
            </main></body></html>
        """.trimIndent()
    }

    private fun calendarHtml(track: LanguageTrack, episode: Int): String {
        val marker = if (track == LanguageTrack.DE_SUB) {
            "Deutsch Untertitel Flagge"
        } else {
            "Deutsche Flagge, German Flag"
        }
        return """
            <html><body><main><h1>Animekalender</h1>
            <article data-source-key="series-alpha">
              <a href="/anime/series-alpha">
                <img class="flag" title="Folge $episode" alt="$marker">
              </a>
              <time datetime="2026-09-16T20:15:00+02:00"></time>
            </article>
            </main></body></html>
        """.trimIndent()
    }

    private fun response(url: String, body: String): AniWorldHttpResponse =
        AniWorldHttpResponse(200, "text/html; charset=UTF-8", url, body)

    private class RecordingTransport(
        private val recent: AniWorldHttpResponse,
        private val calendar: AniWorldHttpResponse,
    ) : AniWorldHttpTransport {
        val requests = mutableListOf<AniWorldTransportRequest>()

        override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse {
            requests += request
            return if (request.url.endsWith("/neue-episoden")) recent else calendar
        }
    }
}

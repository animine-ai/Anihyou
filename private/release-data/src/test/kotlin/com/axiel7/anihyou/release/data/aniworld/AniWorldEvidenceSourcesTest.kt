package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.ReleaseEvidenceSource
import com.axiel7.anihyou.release.core.api.SourceFailureKind
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldEvidenceSourcesTest {
    private val observedAt = Instant.parse("2026-09-08T11:40:00Z")
    private val clock = Clock.fixed(observedAt, ZoneOffset.UTC)

    @Test
    fun calendarKeepsApproximateTimeAndDoesNotApplyAHiddenOffset() = runBlocking {
        val result = calendar(calendarHtml(time = "2026-09-08T22:10:00+02:00", approximate = true))

        val evidence = singleEvidence(result)
        assertEquals(ReleaseSourceType.ANIWORLD_CALENDAR, evidence.sourceType)
        assertEquals(ReleaseEvidenceType.FORECAST, evidence.evidenceType)
        assertEquals(Instant.parse("2026-09-08T20:10:00Z"), evidence.sourceReportedAt)
        assertTrue(evidence.approximateTime)
        assertEquals(observedAt, evidence.observedAt)
    }

    @Test
    fun calendarInvalidDateIsRejected() = runBlocking {
        val result = calendar(calendarHtml(time = "2026-13-08T22:10:00+02:00"))

        assertFailure(result, SourceFailureKind.PARSE)
    }

    @Test
    fun calendarEmptyAndBlockedPagesFailClosed() = runBlocking {
        val empty = calendar("<html><body><main><h1>Animekalender</h1></main></body></html>")
        val blocked = calendar(
            "<html><head><title>Cloudflare challenge</title></head>" +
                "<body>verify you are human</body></html>",
        )

        assertFailure(empty, SourceFailureKind.PARSE)
        assertFailure(blocked, SourceFailureKind.BLOCKED)
    }

    @Test
    fun calendarDstGapAndOverlapDoNotInventAnInstant() = runBlocking {
        val gap = calendar(
            calendarHtml(
                time = "",
                dataDate = "2026-03-29",
                dataTime = "02:30",
                dataZone = "Europe/Berlin",
            ),
        )
        val overlap = calendar(
            calendarHtml(
                time = "",
                dataDate = "2026-10-25",
                dataTime = "02:30",
                dataZone = "Europe/Berlin",
            ),
        )

        assertFailure(gap, SourceFailureKind.PARSE)
        assertFailure(overlap, SourceFailureKind.PARSE)
    }

    @Test
    fun recentEpisodeProducesConfirmationEvidence() = runBlocking {
        val result = recent(recentHtml("DE_DUB", includeSourceReportedAt = true))

        val evidence = singleEvidence(result)
        assertEquals(ReleaseSourceType.ANIWORLD_RECENT, evidence.sourceType)
        assertEquals(ReleaseEvidenceType.CONFIRMATION, evidence.evidenceType)
        assertEquals(LanguageTrack.DE_DUB, evidence.languageTrack)
        assertEquals(Instant.parse("2026-09-08T11:34:00Z"), evidence.sourceReportedAt)
        assertEquals(observedAt, evidence.observedAt)
    }

    @Test
    fun knownWrongLanguageIsIgnoredWithoutChangingTheGermanTrack() = runBlocking {
        val result = recent(
            """
            <html><body><main><h1>Neue Episoden</h1>
              <article data-source-key="foreign" data-episode="4">
                <img class="flag" title="Episode 4" alt="English language flag">
              </article>
              <article data-source-key="series-alpha" data-episode="7">
                <img class="flag" title="Folge 7" data-track="DE_SUB">
              </article>
            </main></body></html>
            """.trimIndent(),
        )

        val evidence = evidenceValues(result)
        assertEquals(1, evidence.size)
        assertEquals(LanguageTrack.DE_SUB, evidence.single().languageTrack)
        assertEquals("series-alpha", evidence.single().siteIdentifier!!.slug)
    }

    @Test
    fun postponementKeepsSubAndDubIndependent() = runBlocking {
        val result = postponement(
            """
            <html><body><main><h1>Verschobene Episoden</h1>
              <article data-source-key="series-alpha" data-episode="8">
                <span>Verschoben</span>
                <img class="flag" title="Folge 8" data-track="DE_SUB">
              </article>
              <article data-source-key="series-alpha" data-episode="8">
                <span>Verschoben</span>
                <img class="flag" title="Folge 8" data-track="DE_DUB">
              </article>
            </main></body></html>
            """.trimIndent(),
        )

        val evidence = evidenceValues(result)
        assertEquals(2, evidence.size)
        assertEquals(setOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB), evidence.mapNotNull { it.languageTrack }.toSet())
        assertTrue(evidence.all { it.scheduleCondition == ScheduleCondition.DELAYED })
        assertTrue(evidence.all { it.evidenceType == ReleaseEvidenceType.CORRECTION })
    }

    @Test
    fun dubPostponementDoesNotCreateSubEvidence() = runBlocking {
        val result = postponement(
            """
            <html><body><main><h1>Verschobene Episoden</h1>
              <article data-source-key="series-alpha" data-episode="8">
                <span>Verschoben</span>
                <img class="flag" title="Folge 8" data-track="DE_DUB">
              </article>
            </main></body></html>
            """.trimIndent(),
        )

        val evidence = evidenceValues(result)
        assertEquals(listOf(LanguageTrack.DE_DUB), evidence.map { it.languageTrack })
    }

    @Test
    fun unknownReturnDateIsHiatus() = runBlocking {
        val result = postponement(
            """
            <html><body><main><h1>Verschobene Episoden</h1>
              <article data-source-key="series-alpha" data-episode="8">
                <span>Unbekannter Rückkehrtermin</span>
                <img class="flag" title="Folge 8" data-track="DE_SUB">
              </article>
            </main></body></html>
            """.trimIndent(),
        )

        assertEquals(ScheduleCondition.HIATUS, singleEvidence(result).scheduleCondition)
    }

    @Test
    fun directPagePreservesSourceReportedAtSeparatelyFromObservation() = runBlocking {
        val result = direct(
            """
            <html><body><main><h1>Series Alpha Folge 7 Stream</h1>
              <article data-source-key="series-alpha" data-episode="7">
                <span>Veröffentlicht bei uns: 08.09.2026 13:34</span>
                <img class="flag" title="Folge 7" data-track="DE_DUB">
              </article>
            </main></body></html>
            """.trimIndent(),
        )

        val evidence = singleEvidence(result)
        assertEquals(Instant.parse("2026-09-08T11:34:00Z"), evidence.sourceReportedAt)
        assertEquals(observedAt, evidence.observedAt)
        assertTrue(evidence.observedAt.isAfter(evidence.sourceReportedAt!!))
    }

    @Test
    fun directPageWithoutPublicationTimestampKeepsTimestampNull() = runBlocking {
        val result = direct(
            """
            <html><body><main><h1>Series Alpha Folge 7 Stream</h1>
              <article data-source-key="series-alpha" data-episode="7">
                <img class="flag" title="Folge 7" data-track="DE_DUB">
              </article>
            </main></body></html>
            """.trimIndent(),
        )

        val evidence = singleEvidence(result)
        assertNull(evidence.sourceReportedAt)
        assertEquals(observedAt, evidence.observedAt)
    }

    @Test
    fun calendarFailureDoesNotDiscardRecentEvidence() = runBlocking {
        val transport = RoutingTransport(
            mapOf(
                "/animekalender" to response("<html><body><main><h1>Animekalender</h1></main></body></html>"),
                "/neue-episoden" to response(recentHtml("DE_SUB")),
            ),
        )
        val collection = AniWorldEvidenceIngestionCoordinator(
            listOf(
                AniWorldCalendarEvidenceAdapter(AniWorldClient(transport), clock = clock),
                AniWorldRecentEpisodeEvidenceAdapter(AniWorldClient(transport), clock = clock),
            ),
        ).collect()

        assertEquals(1, collection.evidence.size)
        assertEquals(ReleaseSourceType.ANIWORLD_RECENT, collection.evidence.single().sourceType)
        assertTrue(collection.results[0] is SourceResult.Failure)
        assertTrue(collection.results[1] is SourceResult.Success)
    }

    @Test
    fun recentFailureDoesNotDiscardCalendarForecast() = runBlocking {
        val transport = RoutingTransport(
            mapOf(
                "/animekalender" to response(calendarHtml("2026-09-08T22:10:00+02:00")),
                "/neue-episoden" to response(
                    "<html><head><title>Cloudflare</title></head><body>verify you are human</body></html>",
                ),
            ),
        )
        val collection = AniWorldEvidenceIngestionCoordinator(
            listOf(
                AniWorldRecentEpisodeEvidenceAdapter(AniWorldClient(transport), clock = clock),
                AniWorldCalendarEvidenceAdapter(AniWorldClient(transport), clock = clock),
            ),
        ).collect()

        assertEquals(1, collection.evidence.size)
        assertEquals(ReleaseSourceType.ANIWORLD_CALENDAR, collection.evidence.single().sourceType)
        assertTrue(collection.results[0] is SourceResult.Failure)
        assertTrue(collection.results[1] is SourceResult.Success)
    }

    private suspend fun calendar(html: String): SourceResult<List<ReleaseEvidence>> =
        AniWorldCalendarEvidenceAdapter(
            client = AniWorldClient(RoutingTransport(mapOf("/animekalender" to response(html)))),
            clock = clock,
        ).collect()

    private suspend fun recent(html: String): SourceResult<List<ReleaseEvidence>> =
        AniWorldRecentEpisodeEvidenceAdapter(
            client = AniWorldClient(RoutingTransport(mapOf("/neue-episoden" to response(html)))),
            clock = clock,
        ).collect()

    private suspend fun postponement(html: String): SourceResult<List<ReleaseEvidence>> =
        AniWorldPostponementEvidenceAdapter(
            client = AniWorldClient(
                RoutingTransport(mapOf("/verschobene-episoden" to response(html))),
            ),
            clock = clock,
        ).collect()

    private suspend fun direct(html: String): SourceResult<List<ReleaseEvidence>> =
        AniWorldDirectVerificationEvidenceAdapter(
            client = AniWorldClient(
                RoutingTransport(mapOf("/anime/series-alpha/episode-7" to response(html))),
            ),
            episodeUrl = "https://aniworld.to/anime/series-alpha/episode-7",
            clock = clock,
        ).collect()

    private fun singleEvidence(result: SourceResult<List<ReleaseEvidence>>): ReleaseEvidence =
        evidenceValues(result).single()

    private fun evidenceValues(result: SourceResult<List<ReleaseEvidence>>): List<ReleaseEvidence> = when (result) {
        is SourceResult.Success -> result.value
        is SourceResult.PartialSuccess -> result.value
        is SourceResult.Failure -> error("unexpected source failure: " + result.diagnostic)
    }

    private fun assertFailure(result: SourceResult<List<ReleaseEvidence>>, expected: SourceFailureKind) {
        assertTrue(result is SourceResult.Failure)
        assertEquals(expected, (result as SourceResult.Failure).kind)
    }

    private fun recentHtml(track: String, includeSourceReportedAt: Boolean = false): String =
        """
        <html><body><main><h1>Neue Episoden</h1>
          <article data-source-key="series-alpha" data-episode="7">
            ${if (includeSourceReportedAt) "<span>Veröffentlicht bei uns: 08.09.2026 13:34</span>" else ""}
            <img class="flag" title="Folge 7" data-track="$track">
          </article>
        </main></body></html>
        """.trimIndent()

    private fun calendarHtml(
        time: String,
        approximate: Boolean = false,
        dataDate: String? = null,
        dataTime: String? = null,
        dataZone: String? = null,
    ): String {
        val timeElement = if (time.isNotBlank()) {
            "<time datetime=\"$time\">${if (approximate) "~22:10" else "22:10"}</time>"
        } else {
            "<span>~02:30</span>"
        }
        val attrs = listOfNotNull(
            dataDate?.let { "data-date=\"$it\"" },
            dataTime?.let { "data-time=\"$it\"" },
            dataZone?.let { "data-zone=\"$it\"" },
        ).joinToString(" ")
        return """
            <html><body><main><h1>Animekalender</h1>
              <article data-source-key="series-alpha" data-episode="7" $attrs>
                <img class="flag" title="Folge 7" data-track="DE_DUB">
                $timeElement
              </article>
            </main></body></html>
        """.trimIndent()
    }

    private fun response(body: String): AniWorldHttpResponse = AniWorldHttpResponse(
        statusCode = 200,
        contentType = "text/html; charset=UTF-8",
        finalUrl = "https://aniworld.to/source",
        body = body,
    )

    private class RoutingTransport(
        private val responses: Map<String, AniWorldHttpResponse>,
    ) : AniWorldHttpTransport {
        override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse =
            responses.entries.firstOrNull { request.url.endsWith(it.key) }?.value?.copy(finalUrl = request.url)
                ?: AniWorldHttpResponse(503, "text/html", request.url, "unavailable")
    }
}

package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.ReleaseEvidenceSource
import com.axiel7.anihyou.release.core.api.SourceFailureKind
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
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
import org.junit.Assert.assertNotEquals
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
    fun currentCalendarStructureKeepsZeroSeasonEpisodeAndForecastOnly() = runBlocking {
        val result = calendar("""
            <html><body><main><h1>Animekalender</h1>
              <article data-source-key="alpha" data-source-season="0" data-episode="0">
                <img class="flag" data-track="DE_SUB" title="Untertitel">
                <img class="flag" data-track="DE_DUB" title="Synchron">
                <time datetime="2026-09-26T12:30:00+02:00">~12:30</time>
              </article>
            </main></body></html>
        """.trimIndent())
        val evidence = evidenceValues(result)
        assertEquals(setOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB),
            evidence.mapNotNull { it.languageTrack }.toSet())
        assertTrue(evidence.all { it.sourceSeason == 0 && it.approximateTime &&
            it.evidenceType == ReleaseEvidenceType.FORECAST &&
            it.scheduleCondition == ScheduleCondition.UNKNOWN })
    }

    @Test
    fun currentRecentStructureKeepsTracksSeparateAndIgnoresForeignRow() = runBlocking {
        val result = recent("""
            <html><body><main><h1>Neue Episoden</h1>
              <article data-source-key="alpha" data-source-season="0" data-episode="0">
                <img class="flag" data-track="DE_SUB" title="Untertitel">
                <img class="flag" data-track="DE_DUB" title="Synchron">
              </article>
              <article data-source-key="beta" data-source-season="1" data-episode="2">
                <img class="flag" alt="English language flag">
              </article>
            </main></body></html>
        """.trimIndent())
        val evidence = evidenceValues(result)
        assertEquals(2, evidence.size)
        assertTrue(evidence.all { it.sourceSeason == 0 &&
            it.evidenceType == ReleaseEvidenceType.CONFIRMATION })
        assertEquals(setOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB),
            evidence.mapNotNull { it.languageTrack }.toSet())
    }

    @Test
    fun rateLimitAndNotModifiedNeverYieldEvidenceOrNegativeCoverage() = runBlocking {
        for ((status, kind) in listOf(
            429 to SourceFailureKind.RATE_LIMITED,
            304 to SourceFailureKind.NOT_MODIFIED_CACHE_MISS,
        )) {
            val transport = RoutingTransport(mapOf("/animekalender" to
                AniWorldHttpResponse(status, null, "https://aniworld.to/animekalender", "",
                    retryAfter = if (status == 429) "120" else null)))
            val collection = AniWorldEvidenceIngestionCoordinator(listOf(
                AniWorldCalendarEvidenceAdapter(AniWorldClient(transport), clock = clock),
            )).collect()
            assertTrue(collection.evidence.isEmpty())
            val failure = collection.results.single() as SourceResult.Failure
            assertEquals(kind, failure.kind)
            assertNull(failure.sourceHealth?.lastSuccessAt)
            if (status == 429) assertEquals(120L, failure.retryAfterSeconds)
        }
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
    fun supportListKeepsSubAndDubUnboundWithoutCorrectionEvidence() = runBlocking {
        val result = postponement(
            """
            <html><body><main><h1>Anime Verschiebungen</h1><ul>
              <li>Series Alpha S00 E00 Sub: 08.09.2026 auf 15.09.2026 verschoben</li>
              <li>Series Alpha S00 E00 Dub: 08.09.2026 auf 16.09.2026 verschoben</li>
            </ul>
            </main></body></html>
            """.trimIndent(),
        )
        assertFailure(result, SourceFailureKind.PARSE)
        assertEquals("/support/frage/anime-verschiebungen",
            AniWorldPostponementEvidenceAdapter.DEFAULT_POSTPONEMENT_PATH)
    }

    @Test
    fun supportListParserPreservesExplicitTracksAndZeroIdentityWithoutBinding() {
        val rows = AniWorldPostponementSupportList.parse(
            "<main><h1>Anime Verschiebungen</h1><ul>" +
                "<li>Alpha S00/E00 Sub verschoben</li><li>Alpha S00/E00 Dub verschoben</li></ul></main>",
            "https://aniworld.to/support/frage/anime-verschiebungen",
        )!!
        assertEquals(0, rows.first().season)
        assertEquals(0, rows.first().episode)
        assertEquals(setOf(LanguageTrack.DE_SUB), rows.first().tracks)
        assertEquals(setOf(LanguageTrack.DE_DUB), rows.last().tracks)
        assertEquals(rows.first().sourceHash, rows.last().sourceHash)
    }

    @Test
    fun titleOnlySupportRowCannotBecomeAuthoritativeHiatus() = runBlocking {
        val result = postponement(
            """
            <html><body><main><h1>Anime Verschiebungen</h1><ul>
              <li>Series Alpha S01/E08 Sub: Unbekannter Rückkehrtermin</li>
            </ul></main></body></html>
            """.trimIndent(),
        )

        assertFailure(result, SourceFailureKind.PARSE)
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
    fun evidenceIdIsDeterministicAndSeasonSafe() {
        val seasonOne = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/1/1/episode:1/DE_SUB",
        )
        val seasonTwo = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/2/2/episode:1/DE_SUB",
        )

        assertNotEquals(seasonOne, seasonTwo)
        assertEquals(
            seasonOne,
            deterministicEvidenceId(
                identityKey = "aniworld:/anime/stream/alpha/1/1/episode:1/DE_SUB",
            ),
        )
        assertTrue(seasonOne.startsWith("aniworld-v3:ANIWORLD_RECENT:"))
        assertTrue(seasonOne.length <= 256)
    }

    @Test
    fun evidenceIdSeparatesLanguageTrackSourceAndInstallment() {
        val sub = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/1/1/episode:1/DE_SUB",
        )
        val dub = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/1/1/episode:1/DE_DUB",
        )
        val calendar = deterministicEvidenceId(
            sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
            identityKey = "aniworld:/anime/stream/alpha/1/1/episode:1/DE_SUB",
        )
        val episodeTwo = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/1/1/episode:2/DE_SUB",
        )
        val filmOne = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/1/1/film:1/DE_SUB",
        )
        val filmTwo = deterministicEvidenceId(
            identityKey = "aniworld:/anime/stream/alpha/1/1/film:2/DE_SUB",
        )

        assertNotEquals(sub, dub)
        assertNotEquals(sub, calendar)
        assertNotEquals(sub, episodeTwo)
        assertNotEquals(filmOne, filmTwo)
    }

    @Test
    fun evidenceIdUsesCompleteSourceHashAndEvidenceFields() {
        val commonPrefix = "1234567890abcdef"
        val first = deterministicEvidenceId(
            sourceHash = commonPrefix + "000000000000000000000000000000000000000000000000",
        )
        val second = deterministicEvidenceId(
            sourceHash = commonPrefix + "ffffffffffffffffffffffffffffffffffffffffffffffff",
        )
        val changedEvidence = deterministicEvidenceId(
            approximateTime = true,
            scheduleCondition = ScheduleCondition.DELAYED,
            sourceReportedAt = Instant.parse("2026-09-08T11:34:00Z"),
        )

        assertNotEquals(first, second)
        assertNotEquals(first, changedEvidence)
    }

    @Test
    fun adapterEvidenceIdChangesWhenSeasonChanges() = runBlocking {
        val seasonOne = singleEvidence(
            recent(recentHtml("DE_SUB", sourceSeason = 1, navigationSeason = 1)),
        )
        val seasonTwo = singleEvidence(
            recent(recentHtml("DE_SUB", sourceSeason = 2, navigationSeason = 2)),
        )

        assertNotEquals(seasonOne.id, seasonTwo.id)
        assertTrue(seasonOne.identityKey.contains("/1/1/episode:7/DE_SUB"))
        assertTrue(seasonTwo.identityKey.contains("/2/2/episode:7/DE_SUB"))
    }

    @Test
    fun unchangedSnapshotAtDifferentPollTimesKeepsEvidenceId() = runBlocking {
        val html = recentHtml("DE_SUB")
        val first = singleEvidence(recentAt(html, observedAt))
        val second = singleEvidence(recentAt(html, observedAt.plusSeconds(300)))

        assertEquals(first.id, second.id)
        assertNotEquals(first.observedAt, second.observedAt)
    }

    @Test
    fun titleChangesDoNotEnterTheEvidenceIdentityFingerprint() {
        val beforeRename = AniWorldSiteIdentifier(
            slug = "alpha",
            normalizedTitle = "Alpha",
        )
        val afterRename = AniWorldSiteIdentifier(
            slug = "alpha",
            normalizedTitle = "Alpha: The New Title",
        )

        assertEquals(beforeRename, afterRename)
        assertEquals(beforeRename.stableKey, afterRename.stableKey)
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

    private fun deterministicEvidenceId(
        sourceType: ReleaseSourceType = ReleaseSourceType.ANIWORLD_RECENT,
        sourceHash: String = "source-hash-000000000000000000000000000000000000000000000000000000000000",
        identityKey: String = "aniworld:/anime/stream/alpha/1/1/episode:1/DE_SUB",
        evidenceType: ReleaseEvidenceType = ReleaseEvidenceType.CONFIRMATION,
        sourceReportedAt: Instant? = null,
        approximateTime: Boolean = false,
        scheduleCondition: ScheduleCondition = ScheduleCondition.UNKNOWN,
    ): String = AniWorldEvidenceId.create(
        sourceType = sourceType,
        sourceHash = sourceHash,
        identityKey = identityKey,
        evidenceType = evidenceType,
        sourceReportedAt = sourceReportedAt,
        approximateTime = approximateTime,
        scheduleCondition = scheduleCondition,
    )

    private suspend fun calendar(html: String): SourceResult<List<ReleaseEvidence>> =
        AniWorldCalendarEvidenceAdapter(
            client = AniWorldClient(RoutingTransport(mapOf("/animekalender" to response(html)))),
            clock = clock,
        ).collect()

    private suspend fun recent(html: String): SourceResult<List<ReleaseEvidence>> =
        recentAt(html, observedAt)

    private suspend fun recentAt(
        html: String,
        pollTime: Instant,
    ): SourceResult<List<ReleaseEvidence>> =
        AniWorldRecentEpisodeEvidenceAdapter(
            client = AniWorldClient(RoutingTransport(mapOf("/neue-episoden" to response(html)))),
            clock = Clock.fixed(pollTime, ZoneOffset.UTC),
        ).collect()

    private suspend fun postponement(html: String): SourceResult<List<ReleaseEvidence>> =
        AniWorldPostponementEvidenceAdapter(
            client = AniWorldClient(
                RoutingTransport(mapOf("/support/frage/anime-verschiebungen" to response(html))),
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

    private fun recentHtml(
        track: String,
        includeSourceReportedAt: Boolean = false,
        sourceSeason: Int? = null,
        navigationSeason: Int? = null,
        episode: Int = 7,
    ): String {
        val seasonAttributes = listOfNotNull(
            sourceSeason?.let { "data-source-season=\"$it\"" },
            navigationSeason?.let { "data-navigation-season=\"$it\"" },
        ).joinToString(" ")
        return """
        <html><body><main><h1>Neue Episoden</h1>
          <article data-source-key="series-alpha" data-episode="$episode" $seasonAttributes>
            ${if (includeSourceReportedAt) "<span>Veröffentlicht bei uns: 08.09.2026 13:34</span>" else ""}
            <img class="flag" title="Folge $episode" data-track="$track">
          </article>
        </main></body></html>
        """.trimIndent()
    }

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

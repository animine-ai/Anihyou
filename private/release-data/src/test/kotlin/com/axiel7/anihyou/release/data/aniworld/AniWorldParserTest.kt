package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseKind
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldParserTest {
    private val observedAt = Instant.parse("2026-09-11T00:00:00Z")
    private val parser = AniWorldParser()
    private val recentUrl = "https://aniworld.to/neue-episoden"
    private val calendarUrl = "https://aniworld.to/animekalender"

    @Test
    fun durableFixturesReplayWithSemanticPageRolesAndRecordedHashes() {
        val cases = listOf(
            FixtureCase(
                name = "recent-positive-de-dub.html",
                role = AniWorldPageRole.RECENT_CURRENT,
                hash = "cd829360a927de896cb627f3076121babb44a276c8a40c7063485e0ce71deb09",
            ),
            FixtureCase(
                name = "recent-de-sub-separate.html",
                role = AniWorldPageRole.RECENT_CURRENT,
                hash = "fcb90a66e97f3ab8347ad4c56af667a5436495bc92080b3663bdc9ccfb67880b",
            ),
            FixtureCase(
                name = "future-calendar-forecast.html",
                role = AniWorldPageRole.FUTURE_CALENDAR,
                hash = "eecf11c87ea1751dcb4e83f4caf8f795756601955e489f0780540414ff7e1144",
            ),
            FixtureCase(
                name = "support-page-roles.html",
                role = AniWorldPageRole.SUPPORT_EXPLANATION,
                hash = "b32d1363dd972ee9a9eb440961dc207f44e8866d733c3b9f899ac1090d8019b9",
            ),
        )

        cases.forEach { case ->
            val html = fixture(case.name)
            assertEquals(case.hash, sha256(html))
            val result = parser.inspect(html, case.role)
            assertTrue("fixture \${case.name} should inspect", result is AniWorldInspectionResult.Success)
            assertEquals(case.role, (result as AniWorldInspectionResult.Success).inspection.role)
        }

        val positive = parser.inspect(
            fixture("recent-positive-de-dub.html"),
            AniWorldPageRole.RECENT_CURRENT,
        ) as AniWorldInspectionResult.Success
        assertEquals(
            listOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB),
            positive.inspection.flagTracksInDocumentOrder,
        )

        val subOnly = parser.inspect(
            fixture("recent-de-sub-separate.html"),
            AniWorldPageRole.RECENT_CURRENT,
        ) as AniWorldInspectionResult.Success
        assertEquals(listOf(LanguageTrack.DE_SUB), subOnly.inspection.flagTracksInDocumentOrder)

        val calendar = parser.inspect(
            fixture("future-calendar-forecast.html"),
            AniWorldPageRole.FUTURE_CALENDAR,
        ) as AniWorldInspectionResult.Success
        assertEquals(listOf(LanguageTrack.DE_DUB), calendar.inspection.flagTracksInDocumentOrder)
    }

    @Test
    fun missingPageAnchorAndChangedFlagSemanticsFailClosed() {
        val missing = parser.inspect(
            fixture("recent-missing-page-anchor.html"),
            AniWorldPageRole.RECENT_CURRENT,
        )
        val changed = parser.inspect(
            fixture("recent-changed-semantic-anchor.html"),
            AniWorldPageRole.RECENT_CURRENT,
        )

        assertEquals(AniWorldFailureKind.MISSING_PAGE_ANCHOR, (missing as AniWorldInspectionResult.Failure).kind)
        assertEquals(AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER, (changed as AniWorldInspectionResult.Failure).kind)
    }

    @Test
    fun parserNormalizesRecentConfirmationWithoutPositionalFlagAssumption() {
        val result = parser.parse(
            html = """
                <html><body><main><h1>Neue Episoden</h1>
                <article data-source-key="series-alpha" data-season="2" data-new-token="kept">
                  <a href="/anime/series-alpha">
                    <img class="flag" title="Folge 9 - Test" alt="Deutsche Flagge, German Flag">
                  </a>
                </article>
                </main></body></html>
            """.trimIndent(),
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )
        val page = (result as AniWorldParseResult.Success).page
        val snapshot = page.snapshots.single()
        assertEquals(LanguageTrack.DE_DUB, snapshot.stream.languageTrack)
        assertEquals(ReleaseKind.EPISODE, snapshot.stream.releaseKind)
        assertEquals(2, snapshot.stream.sourceSeason)
        assertEquals("series-alpha", snapshot.stream.stableSeriesKey.value)
        assertEquals(1, snapshot.confirmations.size)
        assertEquals(Installment.Episode(9), snapshot.confirmations.single().identity.installment)
        assertTrue(snapshot.forecasts.isEmpty())
        assertEquals(1, page.observations.single().rawTokens.size)
        assertEquals("data-new-token", page.observations.single().rawTokens.single().key)
        assertNotNull(snapshot.freshness.sourceHash)
    }

    @Test
    fun calendarRowsAreForecastOnlyAndPreserveSourceTimeMetadata() {
        val result = parser.parse(
            html = """
                <html><body><main><h1>Animekalender</h1>
                <article data-source-key="series-alpha" data-season="4">
                  <a href="/anime/series-alpha">
                    <img class="flag" title="S04E06 - Test" alt="Deutsche Flagge, German Flag">
                  </a>
                  <time datetime="2026-09-16T20:15:00+02:00">16.09.2026 20:15</time>
                </article>
                </main></body></html>
            """.trimIndent(),
            sourceUrl = calendarUrl,
            role = AniWorldPageRole.FUTURE_CALENDAR,
            observedAt = observedAt,
        )
        val page = (result as AniWorldParseResult.Success).page
        val snapshot = page.snapshots.single()
        assertTrue(snapshot.confirmations.isEmpty())
        assertEquals(1, snapshot.forecasts.size)
        val forecast = snapshot.forecasts.single()
        assertEquals(Installment.Episode(6), forecast.identity.installment)
        assertEquals(Instant.parse("2026-09-16T18:15:00Z"), forecast.forecastAt)
        assertEquals("16.09.2026", forecast.sourceDate?.let { "%02d.%02d.%04d".format(it.dayOfMonth, it.monthValue, it.year) })
        assertEquals("20:15", forecast.sourceTime)
        assertEquals("+02:00", forecast.sourceZone?.id)
    }

    @Test
    fun typedFractionalFilmAndSpecialInstallmentsRemainDistinct() {
        val result = parser.parse(
            html = """
                <html><body><main><h1>Neue Episoden</h1>
                <article data-source-key="fractional">
                  <img class="flag" data-episode="10.5" title="Folge 10.5" alt="Deutsche Flagge, German Flag">
                </article>
                <article data-source-key="film" data-film="3">
                  <img class="flag" title="Film 3" alt="Deutsche Flagge, German Flag">
                </article>
                <article data-source-key="special" data-special="2">
                  <img class="flag" title="Special 2" alt="Deutsche Flagge, German Flag">
                </article>
                </main></body></html>
            """.trimIndent(),
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )
        val page = (result as AniWorldParseResult.Success).page
        assertEquals(3, page.snapshots.size)
        val byKey = page.snapshots.associateBy { it.stream.stableSeriesKey.value }
        assertEquals(Installment.Episode(10, 5), byKey["fractional"]!!.confirmations.single().identity.installment)
        assertEquals(ReleaseKind.MOVIE, byKey["film"]!!.stream.releaseKind)
        assertEquals(Installment.Film(3), byKey["film"]!!.confirmations.single().identity.installment)
        assertEquals(ReleaseKind.SPECIAL, byKey["special"]!!.stream.releaseKind)
        assertEquals(Installment.Special(2), byKey["special"]!!.confirmations.single().identity.installment)
    }

    @Test
    fun emptyValidPageIsDifferentFromBlockedPage() {
        val empty = parser.parse(
            html = "<html><body><main><h1>Neue Episoden</h1></main></body></html>",
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )
        val blocked = parser.parse(
            html = "<html><head><title>Cloudflare challenge</title></head><body>verify you are human</body></html>",
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )

        assertTrue(empty is AniWorldParseResult.Success)
        assertTrue((empty as AniWorldParseResult.Success).page.snapshots.isEmpty())
        assertEquals(AniWorldFailureKind.BLOCKED_PAGE, (blocked as AniWorldParseResult.Failure).kind)
    }

    @Test
    fun dstGapAndOverlapNeverInventAnInstant() {
        val gap = parser.parse(
            calendarHtml(date = "2026-03-29", time = "02:30"),
            calendarUrl,
            AniWorldPageRole.FUTURE_CALENDAR,
            observedAt,
        )
        val overlap = parser.parse(
            calendarHtml(date = "2026-10-25", time = "02:30"),
            calendarUrl,
            AniWorldPageRole.FUTURE_CALENDAR,
            observedAt,
        )

        assertEquals(AniWorldFailureKind.INVALID_DATE_TIME, (gap as AniWorldParseResult.Failure).kind)
        assertEquals(AniWorldFailureKind.AMBIGUOUS_LOCAL_TIME, (overlap as AniWorldParseResult.Failure).kind)
    }

    @Test
    fun invalidDateAndConflictingDuplicateIdentityFailClosed() {
        val invalidDate = parser.parse(
            calendarHtml(date = "2026-13-40", time = "20:15"),
            calendarUrl,
            AniWorldPageRole.FUTURE_CALENDAR,
            observedAt,
        )
        val duplicate = parser.parse(
            html = """
                <html><body><main><h1>Animekalender</h1>
                <article data-source-key="same">
                  <img class="flag" title="Folge 4" alt="Deutsche Flagge, German Flag">
                  <time datetime="2026-09-16T20:15:00+02:00"></time>
                </article>
                <article data-source-key="same">
                  <img class="flag" title="Folge 4" alt="Deutsche Flagge, German Flag">
                  <time datetime="2026-09-17T20:15:00+02:00"></time>
                </article>
                </main></body></html>
            """.trimIndent(),
            sourceUrl = calendarUrl,
            role = AniWorldPageRole.FUTURE_CALENDAR,
            observedAt = observedAt,
        )

        assertEquals(AniWorldFailureKind.INVALID_DATE_TIME, (invalidDate as AniWorldParseResult.Failure).kind)
        assertEquals(
            AniWorldFailureKind.DUPLICATE_IDENTITY_CONFLICT,
            (duplicate as AniWorldParseResult.Failure).kind,
        )
    }

    @Test
    fun unknownDataTokensAreBoundedForLaterReparsing() {
        val longValue = "x".repeat(400)
        val result = parser.parse(
            html = """
                <html><body><main><h1>Neue Episoden</h1>
                <article data-source-key="series" data-unknown-a="$longValue" data-unknown-b="two">
                  <img class="flag" title="Folge 1" alt="Deutsche Flagge, German Flag">
                </article>
                </main></body></html>
            """.trimIndent(),
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )
        val observation = (result as AniWorldParseResult.Success).page.observations.single()
        assertEquals(2, observation.rawTokens.size)
        assertEquals(128, observation.rawTokens.first().value.length)
        assertTrue(observation.rawTokens.all { it.value.length <= 128 })
    }


    @Test
    fun knownNonGermanMarkersDoNotPoisonGermanRowsButUnknownExpectedMarkerFailsClosed() {
        val validWithUnrelatedMarker = """
            <html><body><main><h1>Neue Episoden</h1>
            <section class="language-picker">
              <img class="flag" title="English language flag" alt="English">
            </section>
            <article data-source-key="series" data-episode="3">
              <img class="flag" title="Folge 3" alt="Deutsche Flagge, German Flag">
              <img class="flag" title="English audio" alt="English language flag">
            </article>
            </main></body></html>
        """.trimIndent()

        val inspection = parser.inspect(validWithUnrelatedMarker, AniWorldPageRole.RECENT_CURRENT, recentUrl)
        assertTrue(inspection is AniWorldInspectionResult.Success)
        assertEquals(
            listOf(LanguageTrack.DE_DUB),
            (inspection as AniWorldInspectionResult.Success).inspection.flagTracksInDocumentOrder,
        )
        assertEquals(0, inspection.inspection.unknownFlagCount)

        val parsed = parser.parse(
            html = validWithUnrelatedMarker,
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )
        assertTrue(parsed is AniWorldParseResult.Success)
        assertEquals(1, (parsed as AniWorldParseResult.Success).page.snapshots.single().confirmations.size)

        val changedMarker = """
            <html><body><main><h1>Neue Episoden</h1>
            <article data-source-key="series" data-episode="3">
              <img class="flag" title="Folge 3" alt="Changed language marker">
            </article>
            </main></body></html>
        """.trimIndent()

        val changedInspection = parser.inspect(changedMarker, AniWorldPageRole.RECENT_CURRENT, recentUrl)
        assertEquals(
            AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
            (changedInspection as AniWorldInspectionResult.Failure).kind,
        )
        val changedParse = parser.parse(
            html = changedMarker,
            sourceUrl = recentUrl,
            role = AniWorldPageRole.RECENT_CURRENT,
            observedAt = observedAt,
        )
        assertEquals(
            AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
            (changedParse as AniWorldParseResult.Failure).kind,
        )
    }

    private fun calendarHtml(date: String, time: String): String = """
        <html><body><main><h1>Animekalender</h1>
        <article data-source-key="series" data-date="$date" data-time="$time" data-zone="Europe/Berlin">
          <img class="flag" title="Folge 1" alt="Deutsche Flagge, German Flag">
        </article>
        </main></body></html>
    """.trimIndent()

    private fun fixture(name: String): String {
        val relative = "private/evidence/wp00-r1/aniworld/fixtures/$name"
        val file = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
        ).firstOrNull { it.isFile }
            ?: error("fixture not found: $relative")
        return file.readText()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class FixtureCase(
        val name: String,
        val role: AniWorldPageRole,
        val hash: String,
    )
}

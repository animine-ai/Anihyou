package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed clock 2026-10-04T12:00:00Z. The inputs contradict each other on purpose: AniList would name episode 12 for
 * tomorrow, the accepted source confirms episode 10 and only plans episode 11 for today, the user watched 8.
 */
class ReleaseUiSelectionTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val stream = ReleaseStreamKey(
        ProviderId("source"), SourceSeriesKey("/series"), ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB,
    )

    private fun presentation(
        authority: ReleaseUiAuthority = ReleaseUiAuthority.VALID,
        confirmedThrough: Int? = 10,
        planned: Int? = 11,
        track: LanguageTrack = LanguageTrack.DE_SUB,
        storedPending: Int = 99,
    ): ReleaseUiPresentation {
        val key = stream.copy(languageTrack = track)
        val next = planned?.let { Installment.Episode(it) }
        return ReleaseUiPresentation(
            mediaId = 7,
            stream = key,
            authority = authority,
            confirmedThroughEpisode = confirmedThrough,
            confirmedInstallments = (1..(confirmedThrough ?: 0)).map { Installment.Episode(it) },
            confirmedPending = storedPending,
            nextExpectedInstallment = next,
            nextForecast = next?.let {
                Forecast(SourceIdentity(key, it), now, null, null, ZoneOffset.UTC, false, now)
            },
            freshness = ReleaseUiFreshness.FRESH,
            sourceRoot = null,
            revision = 1,
        )
    }

    @Test fun confirmedMinusProgressIsPendingAndAPlannedInstallmentNeverCounts() {
        val release = presentation()
        assertEquals(2, release.pendingFor(8))
        assertEquals(10, release.pendingFor(0))
        assertEquals(0, release.pendingFor(10))
    }

    @Test fun progressAboveConfirmedNeverGoesNegative() {
        assertEquals(0, presentation().pendingFor(14))
    }

    @Test fun aStoredCountIsNeverUsedSoAProgressChangeReprojectsLocally() {
        val release = presentation(storedPending = 99)
        assertEquals(2, release.pendingFor(8))
        assertEquals(1, release.pendingFor(9))
        assertEquals(0, release.pendingFor(10))
    }

    @Test fun onlyAPlanAndNoConfirmationIsNotBehind() {
        assertEquals(0, presentation(confirmedThrough = null, planned = 1).pendingFor(0))
    }

    @Test fun unknownProgressNeverClaimsPending() {
        assertEquals(0, presentation().pendingFor(null))
    }

    @Test fun aNonAuthoritativePresentationHasNothingPending() {
        ReleaseUiAuthority.entries.filter { it != ReleaseUiAuthority.VALID }.forEach { authority ->
            assertEquals(authority.name, 0, presentation(authority).pendingFor(0))
        }
    }

    @Test fun onlyValidPresentationsAreSelectedAndTheFallbackIsNeverHidden() {
        val stale = presentation(ReleaseUiAuthority.STALE)
        val dub = presentation(track = LanguageTrack.DE_DUB, confirmedThrough = 9)
        val sub = presentation(track = LanguageTrack.DE_SUB)
        assertEquals(listOf(sub), ReleaseUiSelection.authoritative(listOf(stale, dub, sub)))
        assertEquals(sub, ReleaseUiSelection.effective(listOf(stale, dub, sub)))
        assertNull(ReleaseUiSelection.effective(listOf(stale, dub)))
        listOf(ReleaseUiAuthority.AMBIGUOUS, ReleaseUiAuthority.UNMAPPED, ReleaseUiAuthority.STALE,
            ReleaseUiAuthority.ERROR, ReleaseUiAuthority.DISABLED).forEach { authority ->
            val rows = listOf(presentation(authority))
            assertTrue(authority.name, ReleaseUiSelection.authoritative(rows).isEmpty())
            assertNull(authority.name, ReleaseUiSelection.effective(rows))
        }
        assertTrue(ReleaseUiSelection.authoritative(emptyList()).isEmpty())
    }

    @Test fun dubNeverAddsPendingOrDecidesTheLatestEpisodeEvenWhenItIsAhead() {
        val sub = presentation(confirmedThrough = 10)
        val dub = presentation(track = LanguageTrack.DE_DUB, confirmedThrough = 12)
        assertEquals(0, dub.pendingFor(8))
        assertEquals(2, requireNotNull(ReleaseUiSelection.effective(listOf(dub, sub))).pendingFor(8))
        assertEquals(sub, ReleaseUiSelection.effective(listOf(sub, dub)))
        assertTrue(ReleaseUiSelection.authoritative(listOf(dub)).isEmpty())
        assertEquals(listOf(sub), ReleaseUiSelection.authoritative(listOf(sub, sub, dub)))
    }

    @Test fun multipleSubStreamsMergeCanonicalFactsRegardlessOfInputOrder() {
        val older = presentation(confirmedThrough = 8, planned = 9)
        val newer = presentation(confirmedThrough = 12, planned = 13)
            .copy(stream = stream.copy(stableSeriesKey = SourceSeriesKey("/other")))
        for (rows in listOf(listOf(older, newer), listOf(newer, older))) {
            val selected = requireNotNull(ReleaseUiSelection.effective(rows))
            assertEquals(12, selected.confirmedThroughEpisode)
            assertEquals(2, selected.pendingFor(10))
            assertEquals(Installment.Episode(13), selected.nextExpectedInstallment)
            assertEquals(12, selected.confirmedInstallments.size)
        }
        assertNull(ReleaseUiSelection.effective(listOf(older, newer.copy(mediaId = 8))))
    }

    @Test fun anOverduePlanIsOnlyOverduePastTheGrace() {
        assertFalse(ReleaseUiSelection.isOverdue(now.plusSeconds(60), now))
        assertFalse(ReleaseUiSelection.isOverdue(now, now))
        assertFalse(ReleaseUiSelection.isOverdue(now.minusSeconds(7_200), now))
        assertTrue(ReleaseUiSelection.isOverdue(now.minusSeconds(7_201), now))
        assertTrue(ReleaseUiSelection.isOverdue(now.minus(java.time.Duration.ofDays(2)), now))
    }
}

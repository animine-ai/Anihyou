package com.axiel7.anihyou.core.ui.composables.media

import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseBehindCountTest {
    private fun source(through: Int?, authority: ReleaseUiAuthority = ReleaseUiAuthority.VALID) = ReleaseUiPresentation(
        mediaId = 7,
        stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("example"), ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB),
        authority = authority, confirmedThroughEpisode = through,
        confirmedInstallments = emptyList(), confirmedPending = 99,
        nextExpectedInstallment = null, nextForecast = null,
        freshness = ReleaseUiFreshness.UNKNOWN, sourceRoot = null, revision = 1,
    )

    @Test fun confirmedSourceZeroSuppressesAniListBehindAndAnUnknownCountKeepsIt() {
        assertEquals(0, releaseBehindCount(listOf(source(8)), 8, 12))
        assertEquals(3, releaseBehindCount(listOf(source(null)), 8, 12))
        assertEquals(2, releaseBehindCount(listOf(source(10)), 8, 12))
    }

    @Test fun staleSourcesAndUnknownProgressNeverSupplyAnActionableCount() {
        assertEquals(3, releaseBehindCount(listOf(source(20, ReleaseUiAuthority.STALE)), 8, 12))
        assertEquals(0, releaseBehindCount(listOf(source(20)), null, 12))
        assertEquals(0, releaseBehindCount(emptyList(), 8, null))
    }
}

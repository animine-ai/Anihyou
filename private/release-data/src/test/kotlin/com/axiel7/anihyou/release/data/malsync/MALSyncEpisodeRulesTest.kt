package com.axiel7.anihyou.release.data.malsync

import org.junit.Assert.*
import org.junit.Test

class MALSyncEpisodeRulesTest {
    private fun payload(start: Int = 14, end: Int = 25, canonicalEnd: Int = 12, page: String = "mal") =
        """{"page":"$page","last_modified":"2026-08-03","rules":[{"from":{"id":42203,"start":$start,"end":$end},"to":{"id":42203,"start":1,"end":$canonicalEnd}}]}""".toByteArray()
    @Test fun upstreamInclusiveRangesBecomeBoundedOffsets() {
        assertEquals(listOf(EpisodeNumberingRule(42203, 42203, 14, 1, 12)), parseMALSyncEpisodeRules(payload()))
        assertEquals(listOf(EpisodeNumberingRule(42203, 42203, 39, 1, 12)), parseMALSyncEpisodeRules(payload(39, 50)))
        assertEquals(emptyList<EpisodeNumberingRule>(), parseMALSyncEpisodeRules("""{"page":"mal","rules":[]}""".toByteArray()))
    }
    @Test fun malformedDifferentServiceAndUnboundedRulesAreRejected() {
        assertNull(parseMALSyncEpisodeRules(payload(page = "anilist")))
        assertNull(parseMALSyncEpisodeRules(payload(canonicalEnd = 11)))
        assertNull(parseMALSyncEpisodeRules(payload(25, 14)))
        assertNull(parseMALSyncEpisodeRules(payload(0, 11)))
        assertNull(parseMALSyncEpisodeRules(payload(9995, 10006)))
        assertNull(parseMALSyncEpisodeRules(payload().toString(Charsets.UTF_8).replace("42203", "\"42203\"").toByteArray()))
        assertNull(parseMALSyncEpisodeRules("""{"page":"mal","page":"anilist","rules":[]}""".toByteArray()))
        assertNull(parseMALSyncEpisodeRules(ByteArray(65537)))
    }
}

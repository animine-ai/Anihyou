package com.axiel7.anihyou.release.core.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TitleNormalizerTest {
    @Test fun aRepeatedTitleGivesTheSameAnswerAsTheFirstTime() {
        val first = TitleNormalizer.normalize("The Apothecary Diaries Season 3")
        val again = TitleNormalizer.normalize("The Apothecary Diaries Season 3")
        assertEquals(first, again)
        assertEquals("the apothecary diaries", again.base)
        assertEquals(3, again.season)
    }

    @Test fun differentTitlesAreNeverMixedUp() {
        val a = TitleNormalizer.normalize("Kusuriya no Hitorigoto 2nd Season")
        val b = TitleNormalizer.normalize("Kusuriya no Hitorigoto 3rd Season")
        assertNotEquals(a, b)
        assertEquals(2, a.season)
        assertEquals(3, b.season)
    }

    @Test fun moreTitlesThanTheCacheHoldsStillNormalizeCorrectly() {
        repeat(20_000) { index -> assertEquals("show $index", TitleNormalizer.normalize("Show $index").base) }
        assertEquals("the apothecary diaries", TitleNormalizer.normalize("The Apothecary Diaries Season 3").base)
    }
}

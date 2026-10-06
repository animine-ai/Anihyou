package com.axiel7.anihyou.release.core.matching

import org.junit.Assert.*
import org.junit.Test

class SearchTitleFoldingTest {
    @Test fun everyWordMatchesAcrossTitlesWithAccentsApostrophesAndPunctuation() {
        assertTrue(SearchTitleFolding.matches("queens ete", "L’Été: King", "Queen's Return"))
        assertTrue(SearchTitleFolding.matches("return king", "L’Été: King", "Queen’s Return"))
        assertFalse(SearchTitleFolding.matches("ete missing", "L’Été: King", "Queen’s Return"))
        assertTrue(SearchTitleFolding.matches("  ", "Title"))
    }
}

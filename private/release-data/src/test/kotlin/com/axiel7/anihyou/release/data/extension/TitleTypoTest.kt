package com.axiel7.anihyou.release.data.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTypoTest {
    @Test fun oneWrongLetterInOneWordOfALongTitleIsATypo() {
        assertTrue(TitleTypo.sameButForOneLetter("ascendance os a bookworm", "ascendance of a bookworm"))
        assertTrue(TitleTypo.sameButForOneLetter("tomb raider kng", "tomb raider king"))
    }

    @Test fun differentNumbersShortTitlesAndSeveralTyposAreNotMatched() {
        assertFalse(TitleTypo.sameButForOneLetter("re zero season 2", "re zero season 3"))
        assertFalse(TitleTypo.sameButForOneLetter("one piece", "one pace"))
        assertFalse(TitleTypo.sameButForOneLetter("ascendance os a bookwurm", "ascendance of a bookworm"))
        assertFalse(TitleTypo.sameButForOneLetter("ascendance of a bookworm", "ascendance of a bookworm"))
    }
}

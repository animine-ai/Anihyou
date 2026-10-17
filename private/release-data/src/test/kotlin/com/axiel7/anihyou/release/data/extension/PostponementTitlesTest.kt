package com.axiel7.anihyou.release.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostponementTitlesTest {
    private val series = listOf(
        NamedSeries("ascendance-of-a-bookworm", listOf("ascendance of a bookworm", "honzuki no gekokujou")),
        NamedSeries("rezero-starting-life-in-another-world", listOf("re zero starting life in another world", "re zero kara hajimeru isekai seikatsu")),
        NamedSeries("one-piece", listOf("one piece")),
        NamedSeries("one-punch-man", listOf("one punch man")),
        NamedSeries("overgeared", listOf("overgeared")),
        NamedSeries("tokyo-revengers", listOf("tokyo revengers")),
        NamedSeries("mushoku-tensei", listOf("mushoku tensei jobless reincarnation", "mushoku tensei isekai ittara honki dasu")),
    )

    private fun resolve(title: String) = PostponementTitles.resolve(title, series)

    @Test fun theSameNameOrAnAliasFindsTheSeries() {
        assertEquals(listOf("ascendance-of-a-bookworm"), resolve("ascendance of a bookworm"))
        assertEquals(listOf("ascendance-of-a-bookworm"), resolve("honzuki no gekokujou"))
        assertEquals(listOf("rezero-starting-life-in-another-world"), resolve("re zero kara hajimeru isekai seikatsu"))
    }

    @Test fun aShortNameBeginningTheLongOneFindsTheSeries() {
        assertEquals(listOf("rezero-starting-life-in-another-world"), resolve("re zero"))
        assertEquals(listOf("mushoku-tensei"), resolve("mushoku tensei"))
    }

    @Test fun aWrongLetterAnotherWordOrderAndAMissingSmallWordStillFindTheSeries() {
        assertEquals(listOf("ascendance-of-a-bookworm"), resolve("ascendance os a bookworm"))
        assertEquals(listOf("ascendance-of-a-bookworm"), resolve("ascendence of a bookwurm"))
        assertEquals(listOf("ascendance-of-a-bookworm"), resolve("bookworm ascendance"))
        assertEquals(listOf("ascendance-of-a-bookworm"), resolve("ascendance bookworm"))
        assertEquals(listOf("tokyo-revengers"), resolve("revengers tokyo"))
        assertEquals(listOf("overgeared"), resolve("overgared"))
    }

    @Test fun aSwappedPairOfLettersIsOneEdit() {
        assertEquals(listOf("tokyo-revengers"), resolve("tokyo revnegers"))
    }

    @Test fun anotherNumberOrAnotherNameIsNotAMatch() {
        val numbered = listOf(NamedSeries("a", listOf("title season 2")), NamedSeries("b", listOf("title season 3")))
        assertEquals(listOf("a"), PostponementTitles.resolve("title season 2", numbered))
        assertTrue(PostponementTitles.resolve("title season 4", numbered).isEmpty())
        assertTrue(resolve("one punch").let { it == listOf("one-punch-man") })
        assertTrue(resolve("bleach thousand year blood war").isEmpty())
        assertTrue(resolve("a").isEmpty())
        assertTrue(resolve("").isEmpty())
    }

    @Test fun aNameTwoSeriesShareIsAmbiguousNotGuessed() {
        assertEquals(setOf("one-piece", "one-punch-man"), resolve("one").toSet())
        val twins = listOf(NamedSeries("x", listOf("same words here")), NamedSeries("y", listOf("here same words")))
        assertEquals(setOf("x", "y"), PostponementTitles.resolve("words same here", twins).toSet())
    }

    @Test fun oneShortWordIsNotFuzzy() {
        assertTrue(PostponementTitles.resolve("bleech", listOf(NamedSeries("bleach", listOf("bleach")))).isEmpty())
    }
}

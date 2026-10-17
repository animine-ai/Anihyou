package com.axiel7.anihyou.release.data.extension

import kotlin.math.max
import kotlin.math.min

/** A source series by the folded names it is known under: its title and every alias the source listed for it. */
internal data class NamedSeries(val key: String, val names: List<String>)

/**
 * Which series a postponement notice means. The notices of the source are typed by hand and name a series the way the
 * writer remembers it: a short form ("Re:Zero"), another language, a word in another order, a wrong letter. The series
 * page of the same source knows several names for one series. So a notice is compared with every name, in three tiers,
 * and the first tier that finds a series decides:
 *
 * 1. the same folded name;
 * 2. a name that begins with the other one (or the reverse) on a whole word;
 * 3. the same words, in any order, with small words ("of", "no", "wa") left out and a word of five letters or more
 *    allowed to be a letter or two off. A number is never off: "2" is not "3".
 *
 * The result is every series of that tier with the best score; more than one means the notice is ambiguous and the
 * caller must not guess.
 */
internal object PostponementTitles {
    fun resolve(folded: String, series: List<NamedSeries>): List<String> {
        if (folded.isBlank()) return emptyList()
        series.filter { s -> s.names.any { it == folded } }.map { it.key }.distinct().takeIf { it.isNotEmpty() }?.let { return it }
        series.filter { s -> s.names.any { startsOnWord(folded, it) || startsOnWord(it, folded) } }
            .map { it.key }.distinct().takeIf { it.isNotEmpty() }?.let { return it }
        val scored = series.mapNotNull { s ->
            val best = s.names.maxOfOrNull { similarity(folded, it) } ?: return@mapNotNull null
            if (best >= MIN_SCORE) s.key to best else null
        }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second >= top - EPSILON }.map { it.first }.distinct()
    }

    /** 1.0: the same words; 0.0: nothing in common. Below [MIN_SCORE] a pair is not a match. */
    fun similarity(a: String, b: String): Double {
        val left = content(a)
        val right = content(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        // One long word may be the whole name ("Overgeared"); anything shorter needs two words to carry a match.
        if (left.size == 1 && right.size == 1) {
            val word = left.single()
            return if (word.length >= SINGLE_WORD_MIN && sameWord(word, right.single())) 1.0 else 0.0
        }
        val (small, large) = if (left.size <= right.size) left to right else right to left
        val used = BooleanArray(large.size)
        var matched = 0
        for (word in small) {
            val at = large.indices.firstOrNull { !used[it] && sameWord(word, large[it]) } ?: continue
            used[at] = true
            matched++
        }
        if (matched < 2) return 0.0
        return matched.toDouble() / max(left.size, right.size)
    }

    private fun startsOnWord(whole: String, start: String): Boolean =
        start.isNotEmpty() && whole.length > start.length && whole.startsWith(start) && whole[start.length] == ' '

    /** The words that carry the name: no one- or two-letter words and no articles, but every number. */
    private fun content(folded: String): List<String> = folded.split(' ').filter { word ->
        word.isNotEmpty() && (word.any(Char::isDigit) || (word.length > 2 && word !in FILLER))
    }

    private fun sameWord(x: String, y: String): Boolean {
        if (x == y) return true
        if (x.any(Char::isDigit) || y.any(Char::isDigit)) return false
        val shorter = min(x.length, y.length)
        val allowed = when {
            shorter >= 9 -> 2
            shorter >= 5 -> 1
            else -> 0
        }
        return allowed > 0 && distance(x, y, allowed) <= allowed
    }

    /** Optimal string alignment distance (a swapped pair of letters is one edit), cut off beyond [limit]. */
    private fun distance(x: String, y: String, limit: Int): Int {
        if (kotlin.math.abs(x.length - y.length) > limit) return limit + 1
        val rows = Array(x.length + 1) { IntArray(y.length + 1) }
        for (i in 0..x.length) rows[i][0] = i
        for (j in 0..y.length) rows[0][j] = j
        for (i in 1..x.length) for (j in 1..y.length) {
            val cost = if (x[i - 1] == y[j - 1]) 0 else 1
            var best = min(min(rows[i - 1][j] + 1, rows[i][j - 1] + 1), rows[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && x[i - 1] == y[j - 2] && x[i - 2] == y[j - 1]) best = min(best, rows[i - 2][j - 2] + 1)
            rows[i][j] = best
        }
        return rows[x.length][y.length]
    }

    private const val MIN_SCORE = 0.75
    private const val EPSILON = 1e-9
    private const val SINGLE_WORD_MIN = 8
    private val FILLER = setOf("the", "and", "for", "with", "from", "that", "this", "your", "his", "her", "its", "are", "was")
}

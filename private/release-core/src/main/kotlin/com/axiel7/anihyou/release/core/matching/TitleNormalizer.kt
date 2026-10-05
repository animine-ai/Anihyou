package com.axiel7.anihyou.release.core.matching

import java.text.Normalizer
import java.util.Locale

data class NormalizedTitle(
    val base: String,
    val season: Int?,
    val part: Int?,
    val tokens: List<String>,
) {
    val canonical: String = buildString {
        append(base)
        season?.let { append("|season:").append(it) }
        part?.let { append("|part:").append(it) }
    }
}

object TitleNormalizer {
    private val whitespace = Regex("\\s+")
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val positiveNumber = Regex("[1-9][0-9]{0,2}")
    private val compactSeason = Regex("s([1-9][0-9]{0,2})")
    private val compactPart = Regex("p([1-9][0-9]{0,2})")
    private val ordinalSeason = Regex("([1-9][0-9]{0,2})(?:st|nd|rd|th)")

    /**
     * The same title is looked at once per series and per candidate of a pool: tens of thousands of times in one matcher
     * run. The result only depends on the text, so it is kept (bounded, least recently used).
     */
    private const val CACHE_LIMIT = 16_384
    private val cache = object : LinkedHashMap<String, NormalizedTitle>(1_024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NormalizedTitle>): Boolean = size > CACHE_LIMIT
    }

    fun normalize(raw: String): NormalizedTitle {
        synchronized(cache) { cache[raw] }?.let { return it }
        return compute(raw).also { value -> synchronized(cache) { cache[raw] = value } }
    }

    private fun compute(raw: String): NormalizedTitle {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(separators, " ")
            .trim()
        val sourceTokens = if (folded.isEmpty()) emptyList() else folded.split(whitespace)
        val removed = BooleanArray(sourceTokens.size)
        var season: Int? = null
        var part: Int? = null

        fun readNumber(index: Int): Int? =
            sourceTokens.getOrNull(index)?.takeIf { positiveNumber.matches(it) }?.toIntOrNull()

        sourceTokens.forEachIndexed { index, token ->
            val compactSeasonValue = compactSeason.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()
            if (season == null && compactSeasonValue != null) {
                season = compactSeasonValue
                removed[index] = true
                return@forEachIndexed
            }
            val compactPartValue = compactPart.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()
            if (part == null && compactPartValue != null) {
                part = compactPartValue
                removed[index] = true
                return@forEachIndexed
            }
            if (season == null && token == "season") {
                readNumber(index + 1)?.let {
                    season = it
                    removed[index] = true
                    removed[index + 1] = true
                }
                return@forEachIndexed
            }
            if (part == null && (token == "part" || token == "cour")) {
                readNumber(index + 1)?.let {
                    part = it
                    removed[index] = true
                    removed[index + 1] = true
                }
                return@forEachIndexed
            }
            if (season == null && ordinalSeason.matches(token) && sourceTokens.getOrNull(index + 1) == "season") {
                season = ordinalSeason.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()
                removed[index] = true
                removed[index + 1] = true
            }
        }

        val baseTokens = sourceTokens.filterIndexed { index, _ -> !removed[index] }
        return NormalizedTitle(
            base = baseTokens.joinToString(" "),
            season = season,
            part = part,
            tokens = baseTokens,
        )
    }
}

package com.axiel7.anihyou.release.core.matching

import java.text.Normalizer
import java.util.Locale

/**
 * The one folding used for the persisted source title index and for the search text of the matching list:
 * Unicode-decomposed, diacritics removed, lower case, every run of non letters and digits one space. Season and
 * part tokens are deliberately kept, so "Season 2" stays searchable. Matching decisions use [TitleNormalizer].
 */
object SearchTitleFolding {
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun fold(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKD)
        .replace(combiningMarks, "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(separators, " ")
        .trim()
}

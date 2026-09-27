package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.security.MessageDigest
import org.jsoup.Jsoup

/** Text only. No series ID, ReleaseEvidence or authority can be obtained from this model. */
internal data class UnboundPostponementRow(
    val text: String,
    val season: Int?,
    val episode: Int?,
    val tracks: Set<LanguageTrack>,
    val sourceHash: String,
)

data class PostponementParseDiagnostics(
    val rowCount: Int,
    val reasonCounts: Map<String, Int>,
    val sourceHash: String?,
    val parserVersion: String,
    val structureValid: Boolean,
    val overflow: Boolean,
)

internal data class PostponementParseResult(
    val rows: List<UnboundPostponementRow>?,
    val diagnostics: PostponementParseDiagnostics,
)

internal object AniWorldPostponementSupportList {
    const val PARSER_VERSION = "aniworld-postponement-support-v1"

    fun parse(html: String, sourceUrl: String): List<UnboundPostponementRow>? =
        parseWithDiagnostics(html, sourceUrl).rows

    fun parseWithDiagnostics(html: String, sourceUrl: String): PostponementParseResult {
        if (html.isBlank() || html.toByteArray(Charsets.UTF_8).size > 2_000_000) {
            return invalid("STRUCTURE_INVALID", null)
        }
        val document = Jsoup.parse(html, sourceUrl)
        val main = document.selectFirst("main") ?: return invalid("MAIN_MISSING", hash(html))
        if (!main.text().contains("Verschieb", ignoreCase = true) &&
            !main.text().contains("verschob", ignoreCase = true)) return invalid("SEMANTIC_ANCHOR_MISSING", hash(html))
        val rows = main.select("li")
        val snapshotHash = hash(html)
        if (rows.size > 512) return PostponementParseResult(null, PostponementParseDiagnostics(
            rowCount = 512, reasonCounts = mapOf("ROW_LIMIT_EXCEEDED" to 512),
            sourceHash = snapshotHash, parserVersion = PARSER_VERSION,
            structureValid = false, overflow = true,
        ))
        val parsed = rows.map { element ->
            val text = element.text().take(512)
            val match = Regex("(?i)\\bS([0-9]{1,3})\\s*[/ -]?\\s*E([0-9]{1,4})\\b").find(text)
            val tracks = buildSet {
                if (Regex("(?i)\\b(?:sub|untertitel)\\b").containsMatchIn(text)) add(LanguageTrack.DE_SUB)
                if (Regex("(?i)\\b(?:dub|synchron)\\b").containsMatchIn(text)) add(LanguageTrack.DE_DUB)
            }
            UnboundPostponementRow(
                text = text,
                season = match?.groupValues?.get(1)?.toIntOrNull(),
                episode = match?.groupValues?.get(2)?.toIntOrNull(),
                tracks = tracks,
                sourceHash = snapshotHash,
            )
        }
        val reasonCounts = if (parsed.isEmpty()) emptyMap() else mapOf("UNBOUND_IDENTITY" to parsed.size)
        return PostponementParseResult(parsed, PostponementParseDiagnostics(
            rowCount = parsed.size, reasonCounts = reasonCounts, sourceHash = snapshotHash,
            parserVersion = PARSER_VERSION, structureValid = true, overflow = false,
        ))
    }

    private fun invalid(reason: String, sourceHash: String?) = PostponementParseResult(
        rows = null,
        diagnostics = PostponementParseDiagnostics(0, mapOf(reason to 1), sourceHash,
            PARSER_VERSION, structureValid = false, overflow = false),
    )

    private fun hash(html: String): String = MessageDigest.getInstance("SHA-256")
        .digest(html.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

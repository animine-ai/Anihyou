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

internal object AniWorldPostponementSupportList {
    fun parse(html: String, sourceUrl: String): List<UnboundPostponementRow>? {
        if (html.isBlank() || html.toByteArray(Charsets.UTF_8).size > 2_000_000) return null
        val document = Jsoup.parse(html, sourceUrl)
        val main = document.selectFirst("main") ?: return null
        if (!main.text().contains("Verschieb", ignoreCase = true) &&
            !main.text().contains("verschob", ignoreCase = true)) return null
        val rows = main.select("li")
        if (rows.size > 512) return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(html.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return rows.map { element ->
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
                sourceHash = hash,
            )
        }
    }
}

package com.axiel7.anihyou.release.core.navigation

import java.time.Duration
import java.time.Instant

/** Account progress stays in AniList coordinates; planned totals are not released totals. */
data class AniListReleaseBasis(
    val status: String?,
    val totalEpisodes: Int?,
    val nextEpisode: Int?,
    val nextAiringAt: Long?,
) {
    fun releasedThrough(): Int? = when (status) {
        "FINISHED" -> totalEpisodes?.takeIf { it in 1..9999 }
        "NOT_YET_RELEASED" -> 0
        "RELEASING" -> nextEpisode?.takeIf { it in 1..9999 && (nextAiringAt ?: 0) > 0 }?.minus(1)
        else -> null
    }
}

/** Already reconciled, unambiguous facts mapped to this media's whole canonical episode. */
data class BacklogEpisodeEvidence(
    val episode: Int,
    val released: Boolean,
    val forecastAt: Instant? = null,
    val observedAt: Instant? = null,
)

data class WatchBacklog(val releasedThrough: Int?, val count: Int?) {
    companion object {
        fun resolve(progress: Int, basis: AniListReleaseBasis?, evidence: List<BacklogEpisodeEvidence>,
            now: Instant = Instant.now()): WatchBacklog {
            if (progress < 0) return WatchBacklog(null, null)
            val facts = evidence.filter { it.episode in 1..9999 }
            val confirmed = facts.filter { it.released }.maxOfOrNull { it.episode }
            // A sparse Recent list is a lower bound, never proof that missing episodes are unavailable.
            val through = listOfNotNull(basis?.releasedThrough(), confirmed).maxOrNull()
            // Only an explicit, recently observed future episode can lower the AniList estimate.
            // A conflicting confirmation wins. Missing rows, mappings and failed requests cannot lower it.
            val ceiling = facts.filter { !it.released && it.forecastAt?.isAfter(now) == true &&
                it.observedAt?.let { at -> !at.isAfter(now) && Duration.between(at, now) <= Duration.ofHours(6) } == true &&
                (confirmed == null || it.episode > confirmed) }.minOfOrNull { it.episode - 1 }
            val effective = through?.let { if (ceiling == null) it else minOf(it, ceiling) }
            return WatchBacklog(effective, effective?.let { (it - progress).coerceAtLeast(0) })
        }
    }
}

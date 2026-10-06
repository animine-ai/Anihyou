package com.axiel7.anihyou.release.core.sync

import java.security.MessageDigest
import java.time.Instant

data class DirectTargetCandidate(
    val canonicalUrl: String,
    val exactTargetKey: String,
    val tracks: Set<String>,
    val priority: Int,
    val firstEligibleAt: Instant,
    val lastAttemptAt: Instant?,
    val nextEligibleAt: Instant?,
    val exactTargetKeys: Set<String> = setOf(exactTargetKey),
    val providerSeriesKey: String? = null,
    val navigationSeason: Int? = null,
) {
    init {
        require(canonicalUrl.length in 1..2048 && exactTargetKey.length in 1..2048)
        require(tracks.isNotEmpty() && tracks.all { it in setOf("DE_SUB", "DE_DUB") })
        require(priority in 0..2)
        require(exactTargetKeys.isNotEmpty() && exactTargetKeys.size <= 2 && exactTargetKey in exactTargetKeys)
        require(providerSeriesKey == null || providerSeriesKey.length in 1..128)
        require(navigationSeason == null || navigationSeason in 1..9999)
    }
}

object DirectTargetSelectionPolicy {
    const val MAX_URLS = 4
    /** Shared coordinate key for selection history, independent of website routes and tracks. */
    fun mappedCoordinateKey(providerSeriesKey: String, navigationSeason: Int, episode: Int): String? {
        if (providerSeriesKey.length !in 1..128 || navigationSeason !in 1..9999 || episode !in 1..9999) return null
        val coordinates = listOf(providerSeriesKey, navigationSeason, episode).joinToString("\n")
        return "mapped-direct-v1:" + MessageDigest.getInstance("SHA-256")
            .digest(coordinates.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val order = compareBy<DirectTargetCandidate>({ it.priority },
        { it.lastAttemptAt ?: Instant.MIN }, { it.firstEligibleAt }, { it.canonicalUrl })

    fun select(candidates: List<DirectTargetCandidate>, now: Instant, limit: Int = MAX_URLS,
        exactTargetLimit: Int = MAX_URLS * 2): List<DirectTargetCandidate> {
        require(limit in 0..MAX_URLS)
        require(exactTargetLimit in 0..MAX_URLS * 2)
        require(candidates.flatMap { it.exactTargetKeys }.distinct().size == candidates.sumOf { it.exactTargetKeys.size })
        val eligible = candidates.filter { it.nextEligibleAt?.isAfter(now) != true }
            .groupBy { it.canonicalUrl }.map { (_, group) ->
                val selected = group.minWith(order)
                val allKeys = group.flatMap { it.exactTargetKeys }.toSet()
                require(allKeys.size <= 2) { "one Direct URL cannot fan out to more than two exact targets" }
                require(group.map { it.providerSeriesKey }.distinct().size == 1 &&
                    group.map { it.navigationSeason }.distinct().size == 1) {
                    "one Direct URL cannot fan out to conflicting provider coordinates"
                }
                selected.copy(tracks = group.flatMap { it.tracks }.toSet(), exactTargetKeys = allKeys,
                    priority = group.minOf { it.priority }, firstEligibleAt = group.minOf { it.firstEligibleAt },
                    lastAttemptAt = group.mapNotNull { it.lastAttemptAt }.minOrNull(),
                    nextEligibleAt = group.mapNotNull { it.nextEligibleAt }.maxOrNull())
            }
        val feasible = eligible.filter { it.exactTargetKeys.size <= exactTargetLimit }
        if (feasible.isEmpty() || limit == 0) return emptyList()
        val ordered = feasible.sortedWith(order)
        val fairness = feasible.minWith(compareBy<DirectTargetCandidate>(
            { it.lastAttemptAt ?: Instant.MIN }, { it.firstEligibleAt }, { it.canonicalUrl }))
        val selected = ordered.take(limit - 1).toMutableList()
        if (selected.none { it.canonicalUrl == fairness.canonicalUrl }) selected += fairness
        if (selected.size < limit) ordered.filterNot { c -> selected.any { it.canonicalUrl == c.canonicalUrl } }
            .take(limit - selected.size).forEach(selected::add)
        if (selected.sumOf { it.exactTargetKeys.size } <= exactTargetLimit) return selected.take(limit)
        // Reserve the oldest candidate before filling the logical request budget.
        // Keep both tracks together, so trimming never discards the fairness slot.
        val bounded = mutableListOf(fairness)
        var remaining = exactTargetLimit - fairness.exactTargetKeys.size
        ordered.filterNot { it.canonicalUrl == fairness.canonicalUrl }.forEach { candidate ->
            if (bounded.size < limit && candidate.exactTargetKeys.size <= remaining) {
                bounded += candidate
                remaining -= candidate.exactTargetKeys.size
            }
        }
        return ordered.filter { candidate -> bounded.any { it.canonicalUrl == candidate.canonicalUrl } }
    }

    fun snapshotDigest(candidates: List<DirectTargetCandidate>): String {
        require(candidates.flatMap { it.exactTargetKeys }.distinct().size == candidates.sumOf { it.exactTargetKeys.size })
        val value = candidates.sortedBy { it.exactTargetKey }.joinToString("\n") { c ->
            listOf(c.exactTargetKeys.sorted().joinToString(","), c.canonicalUrl,
                c.providerSeriesKey ?: "-", c.navigationSeason?.toString() ?: "-",
                c.tracks.sorted().joinToString(","), c.priority.toString(),
                c.firstEligibleAt.toString(), c.lastAttemptAt?.toString() ?: "-", c.nextEligibleAt?.toString() ?: "-")
                .joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

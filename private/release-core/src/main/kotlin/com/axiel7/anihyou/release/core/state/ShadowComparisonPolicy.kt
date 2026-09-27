package com.axiel7.anihyou.release.core.state

import java.time.Duration
import java.time.Instant

data class ShadowFact(val exactKey: String, val released: Boolean, val observedAt: Instant?)
data class ShadowComparison(
    val comparable: Int, val same: Int, val disagreements: Int, val r2Only: Int, val v3Only: Int,
    val uncomparable: Int, val stale: Int,
)

object ShadowComparisonPolicy {
    fun compare(r2: List<ShadowFact>, v3: List<ShadowFact>, now: Instant): ShadowComparison {
        val r2Groups = r2.filter { it.exactKey.isNotBlank() }.groupBy { it.exactKey }
        val v3Groups = v3.filter { it.exactKey.isNotBlank() }.groupBy { it.exactKey }
        val r = r2Groups.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val v = v3Groups.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val staleKeys = r.filterValues {
            val observedAt = it.observedAt ?: return@filterValues true
            val age = Duration.between(observedAt, now)
            age.isNegative || age > Duration.ofHours(24)
        }.keys
        val common = (r.keys intersect v.keys) - staleKeys
        val same = common.count { r.getValue(it).released == v.getValue(it).released }
        return ShadowComparison(common.size, same, common.size - same, ((r.keys - v.keys) - staleKeys).size,
            (v.keys - r.keys).size,
            r2.count { it.exactKey.isBlank() } + v3.count { it.exactKey.isBlank() } +
                r2Groups.count { it.value.size > 1 } + v3Groups.count { it.value.size > 1 }, staleKeys.size)
    }
}

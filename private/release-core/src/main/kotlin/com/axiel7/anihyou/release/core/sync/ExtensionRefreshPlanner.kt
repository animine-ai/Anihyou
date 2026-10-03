package com.axiel7.anihyou.release.core.sync

import com.axiel7.anihyou.release.core.api.ExtensionFreshnessPolicy
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.time.Instant

/** What the soft-freshness stage decided. Hard network limits are enforced later, by the transport ledger. */
sealed interface ExtensionRefreshPlan {
    /** Request exactly [roles]. DIRECT is present only with a non-empty [targetKeys]. Other roles are not asked and not judged. */
    data class Run(
        val roles: Set<SourceRole>,
        val targetKeys: Set<String>,
        /** True when this run is an automatic one that has to be remembered for the hourly dedupe. */
        val countsAsAutomaticAttempt: Boolean,
    ) : ExtensionRefreshPlan {
        init { require(roles.isNotEmpty() && (SourceRole.DIRECT in roles) == targetKeys.isNotEmpty()) }
    }

    /** Nothing is due. [nextEligibleAt] is when something becomes due again, if that is known. */
    data class Skip(val reason: String, val nextEligibleAt: Instant?) : ExtensionRefreshPlan
}

/** Last real network success per resource, as the shared ledger recorded it. Cache hits, skips and errors never count. */
fun interface ExtensionSuccessLookup {
    fun lastSuccess(resource: ExtensionFreshResource): Instant?
}

sealed interface ExtensionFreshResource {
    data class Role(val role: SourceRole) : ExtensionFreshResource
    data class DirectTarget(val canonicalKey: String) : ExtensionFreshResource
}

object ExtensionRefreshPlanner {
    const val REASON_FRESH = "extension-data-fresh"
    const val REASON_AUTOMATIC_WINDOW = "extension-auto-trigger-window"

    /**
     * @param granted the roles the pinned extension is allowed to ingest
     * @param directTargets host-owned canonical keys of the DIRECT targets that exist for this source right now
     * @param hasCommittedData whether this exact source ever committed a cycle; without it nothing may be called fresh
     * @param lastAutomaticAttempt when an automatic trigger last started a refresh for this source
     */
    fun plan(
        trigger: ExtensionRefreshTrigger,
        granted: Set<SourceRole>,
        directTargets: Set<String>,
        hasCommittedData: Boolean,
        lastAutomaticAttempt: Instant?,
        lastSuccess: ExtensionSuccessLookup,
        now: Instant,
        policy: ExtensionFreshnessPolicy = ExtensionFreshnessPolicy(),
    ): ExtensionRefreshPlan {
        val scope = if (trigger.automatic) granted.intersect(ExtensionFreshnessPolicy.AUTOMATIC_ROLES) else granted
        if (scope.isEmpty()) return ExtensionRefreshPlan.Skip("extension-role-scope-empty", null)

        // At most one automatic run per window, except the very first fill of a source that has no data yet.
        if (trigger.automatic && hasCommittedData && lastAutomaticAttempt != null) {
            val reopen = lastAutomaticAttempt.plus(policy.automaticTrigger)
            if (now.isBefore(reopen)) return ExtensionRefreshPlan.Skip(REASON_AUTOMATIC_WINDOW, reopen)
        }

        val bypass = trigger.bypassesSoftFreshness || !hasCommittedData
        val dueRoles = linkedSetOf<SourceRole>()
        val dueTargets = linkedSetOf<String>()
        var earliest: Instant? = null
        fun consider(role: SourceRole, resource: ExtensionFreshResource): Boolean {
            val last = lastSuccess.lastSuccess(resource)
            val fresh = !bypass && last != null && !last.isAfter(now) && now.isBefore(last.plus(policy.forRole(role)))
            if (fresh) {
                val next = last!!.plus(policy.forRole(role))
                if (earliest == null || next.isBefore(earliest)) earliest = next
            }
            return !fresh
        }
        scope.filter { it != SourceRole.DIRECT }.sortedBy { it.ordinal }.forEach { role ->
            if (consider(role, ExtensionFreshResource.Role(role))) dueRoles += role
        }
        if (SourceRole.DIRECT in scope) {
            directTargets.sorted().forEach { key ->
                if (consider(SourceRole.DIRECT, ExtensionFreshResource.DirectTarget(key))) dueTargets += key
            }
            if (dueTargets.isNotEmpty()) dueRoles += SourceRole.DIRECT
        }
        if (dueRoles.isEmpty()) return ExtensionRefreshPlan.Skip(REASON_FRESH, earliest)
        return ExtensionRefreshPlan.Run(dueRoles, dueTargets, countsAsAutomaticAttempt = trigger.automatic)
    }
}

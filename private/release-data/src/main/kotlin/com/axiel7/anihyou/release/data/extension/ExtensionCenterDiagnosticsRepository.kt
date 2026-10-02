package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.*
import java.time.Duration
import java.time.Instant

class ExtensionCenterDiagnosticsRepository(
    private val sources: ExtensionSourceRepository,
    private val policy: ExtensionProductPolicyRepository,
    private val store: FileProviderNavigationStateStore,
) : ExtensionDiagnosticsRepository {
    override suspend fun inspect(key: ExtensionSelectionKey): Map<String, String> {
        val selection = policy.policy.value
        val metadata = sources.diagnostics(key)
        val extension = sources.sources.value.usableExtension(key) ?: return metadata
        val recorded = store.state.value
        val current = selection.activeReleaseSource == key && recorded.source == key &&
            recorded.releaseGeneration == selection.releaseGeneration && recorded.packageDigest == extension.installedDigest &&
            recorded.packageGeneration == extension.packageGeneration
        val values = metadata.toMutableMap()
        if (current) {
            values.putAll(recorded.syncStatistics)
            values["Release count"] = recorded.installments.map { it.projectionKey }.distinct().size.toString()
            values["Tracks"] = recorded.installments.groupingBy { it.track }.eachCount()
                .entries.sortedBy { it.key }.joinToString { it.key + ": " + it.value }
            values["Last navigation status"] = recorded.navigationStatus.orEmpty()
            val last = recorded.syncStatistics["Last successful sync"]?.let {
                runCatching { Instant.parse(it) }.getOrNull()
            }
            if (last != null) values["Freshness"] = Duration.between(last, Instant.now()).seconds.coerceAtLeast(0).toString() + " s"
        }
        // Actual enforced bounds, not a measurement of resources used by the last invocation.
        values["Fuel limit"] = ExtensionHostCoordinator.PLAN_LIMITS.fuel.toString() + " plan / " +
            (if (key.extensionId == "de.aniworld") 25_000_000L else ExtensionHostCoordinator.PARSE_LIMITS.fuel).toString() + " parse"
        values["Memory limit"] = ExtensionHostCoordinator.PARSE_LIMITS.memoryBytes.toString() + " B"
        values["Deadline limit"] = ExtensionHostCoordinator.PARSE_LIMITS.deadlineMillis.toString() + " ms"
        return if (policy.policy.value == selection && sources.sources.value.usableExtension(key)?.let {
                it.installedDigest == extension.installedDigest && it.packageGeneration == extension.packageGeneration
            } == true)
            values else emptyMap()
    }
}

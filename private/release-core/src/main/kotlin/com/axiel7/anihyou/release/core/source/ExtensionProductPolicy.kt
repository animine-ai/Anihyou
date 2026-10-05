package com.axiel7.anihyou.release.core.source

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Includes authenticated publisher/provider and repository lifecycle identity; labels are never keys. */
data class ExtensionSelectionKey(
    val sourceId: String,
    val extensionId: String,
    val publisherId: String,
    val providerId: String,
) {
    init {
        require(listOf(sourceId, extensionId, publisherId, providerId).all {
            it.length in 1..128 && it.none { c -> c.isISOControl() }
        })
    }
}

data class ExtensionPreferences(
    val enabledTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
    val preferredTrackOrder: List<String> = listOf("DE_SUB", "DE_DUB"),
    val languageOrder: List<String> = listOf("de"),
    val visibleInProviderField: Boolean = true,
) {
    init {
        require(enabledTracks.size <= 32 && preferredTrackOrder.size <= 32 && languageOrder.size <= 32)
        require(preferredTrackOrder.distinct() == preferredTrackOrder && languageOrder.distinct() == languageOrder)
        require((enabledTracks + preferredTrackOrder + languageOrder).all {
            it.length in 1..64 && it.matches(Regex("[A-Za-z0-9_-]+")) && it != "UNKNOWN"
        })
    }

    fun orderedTracks(supported: Set<String>): List<String> = preferredTrackOrder.filter {
        it in enabledTracks && it in supported && it != "UNKNOWN"
    }.sortedBy { track ->
        languageOrder.indexOf(track.substringBeforeLast('_').lowercase(Locale.ROOT)).let { if (it < 0) Int.MAX_VALUE else it }
    }

    companion object {
        /** Fresh preferences follow signed extension tracks. Saved exclusions always win. */
        fun forTracks(supported: Set<String>): ExtensionPreferences {
            val tracks = supported.filter {
                it.length in 1..64 && it.matches(Regex("[A-Za-z0-9_-]+")) &&
                    (it.endsWith("_SUB") || it.endsWith("_DUB")) && it.substringBeforeLast('_').isNotBlank()
            }.sortedWith(compareBy<String> { if (it.endsWith("_SUB")) 0 else 1 }.thenBy { it })
            return ExtensionPreferences(tracks.toSet(), tracks,
                tracks.map { it.substringBeforeLast('_').lowercase(Locale.ROOT) }.distinct().sorted())
        }
    }
}

data class ExtensionProductPolicy(
    val generation: Long = 0,
    val activeReleaseSource: ExtensionSelectionKey? = null,
    val preferredNavigationProvider: ExtensionSelectionKey? = null,
    val preferences: Map<ExtensionSelectionKey, ExtensionPreferences> = emptyMap(),
    val releaseGeneration: Long = 0,
    val navigationProviderOrder: List<ExtensionSelectionKey> = emptyList(),
) {
    fun preferencesFor(key: ExtensionSelectionKey) = preferences[key] ?: ExtensionPreferences()
    fun preferencesFor(key: ExtensionSelectionKey, supportedTracks: Set<String>) =
        preferences[key] ?: ExtensionPreferences.forTracks(supportedTracks)
}

/** Scalar active selection makes multiple active sources unrepresentable. Commit shares the switch lock. */
interface ExtensionProductPolicyRepository {
    val policy: StateFlow<ExtensionProductPolicy>
    suspend fun selectActiveSource(key: ExtensionSelectionKey?)
    suspend fun selectNavigationProvider(key: ExtensionSelectionKey?)
    suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences)
    suspend fun invalidateSource(sourceId: String)
    suspend fun invalidateExtension(key: ExtensionSelectionKey) { invalidateSource(key.sourceId) }
    suspend fun setNavigationProviderOrder(keys: List<ExtensionSelectionKey>) {}
    /** Display-only edits. Production repositories commit visibility and preferred provider together. */
    suspend fun setProviderVisibility(key: ExtensionSelectionKey, visible: Boolean, defaults: ExtensionPreferences) {
        setPreferences(key, (policy.value.preferences[key] ?: defaults).copy(visibleInProviderField = visible))
        if (!visible && policy.value.preferredNavigationProvider == key) selectNavigationProvider(null)
    }
    suspend fun resetProviderDisplay(keys: List<ExtensionSelectionKey>) {
        keys.forEach { setPreferences(it, policy.value.preferencesFor(it).copy(visibleInProviderField = true)) }
        setNavigationProviderOrder(emptyList())
        selectNavigationProvider(null)
    }
    suspend fun <T> withCurrentSelection(snapshot: ExtensionProductPolicy, block: suspend () -> T): T?
    suspend fun <T> withCurrentPolicy(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
        if (policy.value == snapshot) block() else null
}

fun ExtensionSource.selectionKey(extension: SourceExtension): ExtensionSelectionKey? =
    if (extension.publisherId.isBlank() || extension.providerId.isBlank()) null else
        ExtensionSelectionKey(id, extension.extensionId, extension.publisherId, extension.providerId)

fun List<ExtensionSource>.usableExtension(key: ExtensionSelectionKey): SourceExtension? =
    singleOrNull { it.id == key.sourceId && it.enabled }?.extensions?.singleOrNull {
        it.extensionId == key.extensionId && it.publisherId == key.publisherId && it.providerId == key.providerId &&
            it.installedDigest != null && it.installedUsable
    }

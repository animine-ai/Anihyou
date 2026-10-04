package com.axiel7.anihyou.release.core.source

import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

enum class ExtensionSourceStatus { ADDED, DISABLED, TRUST_UNAVAILABLE, CURRENT, UPDATE_AVAILABLE, ERROR, REVOKED }
enum class ExtensionSourceFailure { AUTHENTICATION_UNAVAILABLE, NETWORK, INVALID_METADATA, INVALID_PACKAGE, UNSUPPORTED_RUNTIME, STORAGE }

data class SourceExtension(
    val extensionId: String,
    val displayName: String,
    val version: String,
    val digest: String,
    val releaseSequence: Long,
    val capabilities: List<String>,
    val installedVersion: String? = null,
    val installedDigest: String? = null,
    val updateAvailable: Boolean = false,
    val revoked: Boolean = false,
    val activationAllowed: Boolean = false,
    val providerId: String = "",
    val publisherId: String = "",
    // ABI v1 currently knows these tracks. A later ABI can extend this set without changing preferences.
    val supportedTracks: Set<String> = setOf("DE_SUB", "DE_DUB"),
    val installedUsable: Boolean = installedDigest != null && activationAllowed && !revoked,
    val installedStatus: InstalledPackageStatus = if (installedDigest == null) InstalledPackageStatus.NOT_INSTALLED
        else if (installedUsable) InstalledPackageStatus.USABLE else InstalledPackageStatus.UNUSABLE,
    val updateState: ExtensionUpdateState = if (updateAvailable) ExtensionUpdateState.UPDATE_AVAILABLE
        else if (installedDigest == null) ExtensionUpdateState.NOT_INSTALLED else ExtensionUpdateState.INSTALLED_CURRENT,
    val latestAvailableVersion: String? = version,
    val metadataFresh: Boolean = true,
    val candidateYanked: Boolean = false,
    val packageGeneration: Long = 0,
    val rollbackTarget: ExtensionRollbackTarget? = null,
    val lastUpdateAt: Instant? = null,
    val lastUpdateResult: String? = null,
    val lastUpdateFailure: ExtensionUpdateFailure? = null,
    val lastUpdateTechnicalCode: String? = null,
    val installedReleaseSequence: Long? = null,
)

data class ExtensionSource(
    val id: String,
    val url: String,
    val origin: String,
    val enabled: Boolean,
    val status: ExtensionSourceStatus,
    val rootVersion: Long? = null,
    val rootDigest: String? = null,
    val indexSequence: Long? = null,
    val indexDigest: String? = null,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastFailure: ExtensionSourceFailure? = null,
    val extensions: List<SourceExtension> = emptyList(),
    /** The user accepted this exact source without an independent identity check (explicit first trust, not verification). */
    val manuallyTrusted: Boolean = false,
)

/**
 * What a source shows before the user decides on it, derived from the root bytes that were actually received. The
 * fingerprint is the SHA-256 of the canonical signed root. The publisher names are self-declared, not verified.
 */
data class UnverifiedSourcePreview(
    val url: String,
    val repositoryId: String,
    val rootFingerprint: String,
    val publisherIds: List<String>,
    val capabilities: List<String>,
    val hosts: List<String>,
)

sealed interface AddExtensionSourceResult {
    data class Added(val sourceId: String) : AddExtensionSourceResult
    data class Duplicate(val sourceId: String) : AddExtensionSourceResult
    data object InvalidUrl : AddExtensionSourceResult
    data object LimitReached : AddExtensionSourceResult
    /** No independently verified identity exists for this source: nothing is stored until the user accepts exactly this. */
    data class NeedsTrustConfirmation(val preview: UnverifiedSourcePreview) : AddExtensionSourceResult
    /** The metadata of the source could not be loaded or did not parse; nothing was stored. */
    data object PreviewFailed : AddExtensionSourceResult
}

/** Provider-neutral surface. A displayed URL never authenticates a repository. */
interface ExtensionSourceRepository {
    val sources: StateFlow<List<ExtensionSource>>
    val productPolicy: ExtensionProductPolicyRepository? get() = null
    /**
     * False only when this build has no independently authenticated source identity at all. Adding, installing,
     * updating and rolling back cannot succeed then, so the UI must not offer them.
     */
    val trustAvailable: Boolean get() = true
    suspend fun add(url: String): AddExtensionSourceResult
    /**
     * Stores the explicit acceptance of exactly [preview] (the root is fetched again and must have the same fingerprint),
     * then adds the source. Not a global switch: the acceptance belongs to this URL and this root.
     */
    suspend fun confirmUnverifiedSource(preview: UnverifiedSourcePreview): AddExtensionSourceResult =
        AddExtensionSourceResult.InvalidUrl
    suspend fun setEnabled(sourceId: String, enabled: Boolean)
    suspend fun remove(sourceId: String)
    suspend fun refresh(sourceId: String)
    /** True requests a bounded retry for transient failures only. */
    suspend fun refreshEnabled(): Boolean
    suspend fun activate(sourceId: String, extensionId: String)
    suspend fun removeExtension(sourceId: String, extensionId: String) { error("Removal unavailable") }
    suspend fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) { error("Rollback unavailable") }
    /** Reconstruct only independently authenticated local journals, without fetching packages. */
    suspend fun restoreInstalled() {}
    suspend fun diagnostics(key: ExtensionSelectionKey): Map<String, String> = emptyMap()
}

interface ExtensionSourceScheduler {
    fun scheduleRefresh()
}

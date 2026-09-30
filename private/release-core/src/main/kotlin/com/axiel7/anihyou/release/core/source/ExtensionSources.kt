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
)

sealed interface AddExtensionSourceResult {
    data class Added(val sourceId: String) : AddExtensionSourceResult
    data class Duplicate(val sourceId: String) : AddExtensionSourceResult
    data object InvalidUrl : AddExtensionSourceResult
    data object LimitReached : AddExtensionSourceResult
}

/** Provider-neutral surface. A displayed URL never authenticates a repository. */
interface ExtensionSourceRepository {
    val sources: StateFlow<List<ExtensionSource>>
    suspend fun add(url: String): AddExtensionSourceResult
    suspend fun setEnabled(sourceId: String, enabled: Boolean)
    suspend fun remove(sourceId: String)
    suspend fun refresh(sourceId: String)
    /** True requests a bounded retry for transient failures only. */
    suspend fun refreshEnabled(): Boolean
    suspend fun activate(sourceId: String, extensionId: String)
}

interface ExtensionSourceScheduler {
    fun scheduleRefresh()
}

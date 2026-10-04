package com.axiel7.anihyou.feature.settings.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.UnverifiedSourcePreview
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionUpdateState
import com.axiel7.anihyou.release.core.source.selectionKey
import com.axiel7.anihyou.release.core.source.usableExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExtensionSourcesUiState(
    val sources: List<ExtensionSource> = emptyList(),
    val productPolicy: ExtensionProductPolicy = ExtensionProductPolicy(),
    val canEditProductPolicy: Boolean = false,
    /** False when this build cannot authenticate any source; mutating repository actions are then not offered. */
    val trustAvailable: Boolean = true,
    val url: String = "",
    val isAdding: Boolean = false,
    val addResult: AddExtensionSourceResult? = null,
    /** A source with no independently verified identity, waiting for the user's explicit decision. */
    val trustPrompt: UnverifiedSourcePreview? = null,
    val actionFailed: Boolean = false,
    val busySourceIds: Set<String> = emptySet(),
    val diagnostics: Map<ExtensionSelectionKey, Map<String, String>> = emptyMap(),
)

interface ExtensionSourcesEvent {
    fun onUrlChanged(value: String)
    fun addSource()
    /** The user accepted exactly the source that the dialog showed. */
    fun confirmTrust() {}
    fun cancelTrust() {}
    fun setEnabled(sourceId: String, enabled: Boolean)
    fun removeSource(sourceId: String)
    fun refreshSource(sourceId: String)
    fun activate(sourceId: String, extensionId: String)
    fun selectActiveSource(key: ExtensionSelectionKey?)
    fun selectNavigationProvider(key: ExtensionSelectionKey?)
    fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences)
    fun removeExtension(sourceId: String, extensionId: String) {}
    fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) {}
    fun setProviderOrder(keys: List<ExtensionSelectionKey>) {}
    fun refreshDiagnostics() {}
    fun clearActionFailure()
}

class ExtensionSourcesViewModel(
    private val repository: ExtensionSourceRepository,
    private val productPolicyRepository: ExtensionProductPolicyRepository? = null,
    private val diagnosticsRepository: com.axiel7.anihyou.release.core.source.ExtensionDiagnosticsRepository? = null,
) : ViewModel(), ExtensionSourcesEvent {

    private val _uiState = MutableStateFlow(
        ExtensionSourcesUiState(canEditProductPolicy = productPolicyRepository != null, trustAvailable = repository.trustAvailable),
    )
    val uiState = _uiState.asStateFlow()
    private val sourceActionLock = Any()

    init {
        viewModelScope.launch {
            repository.sources.collect { sources ->
                _uiState.update { it.copy(sources = sources,
                    diagnostics = if (it.sources == sources) it.diagnostics else emptyMap()) }
            }
        }
        productPolicyRepository?.let { policyRepository ->
            viewModelScope.launch {
                policyRepository.policy.collect { policy ->
                    _uiState.update { it.copy(productPolicy = policy,
                        diagnostics = if (it.productPolicy == policy) it.diagnostics else emptyMap()) }
                }
            }
        }
        performAction { repository.restoreInstalled() }
    }

    override fun onUrlChanged(value: String) {
        _uiState.update { it.copy(url = value, addResult = null) }
    }

    override fun addSource() {
        val url = uiState.value.url.trim()
        if (url.isEmpty() || uiState.value.isAdding) return

        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true, addResult = null, actionFailed = false) }
            try {
                val result = repository.add(url)
                _uiState.update {
                    it.copy(
                        url = if (result is AddExtensionSourceResult.Added) "" else it.url,
                        isAdding = false,
                        // Nothing is stored yet: the dialog asks first, with what was actually received.
                        trustPrompt = (result as? AddExtensionSourceResult.NeedsTrustConfirmation)?.preview,
                        addResult = result.takeUnless { r -> r is AddExtensionSourceResult.NeedsTrustConfirmation },
                    )
                }
                // Foreground onboarding must not wait for constrained background work.
                // Refresh authenticates metadata only; package installation stays explicit.
                if (result is AddExtensionSourceResult.Added) {
                    performSourceAction(result.sourceId) { repository.refresh(result.sourceId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isAdding = false, actionFailed = true) }
            }
        }
    }

    override fun confirmTrust() {
        val preview = uiState.value.trustPrompt ?: return
        if (uiState.value.isAdding) return
        viewModelScope.launch {
            _uiState.update { it.copy(isAdding = true, addResult = null, actionFailed = false) }
            try {
                val result = repository.confirmUnverifiedSource(preview)
                _uiState.update {
                    it.copy(
                        url = if (result is AddExtensionSourceResult.Added) "" else it.url,
                        isAdding = false,
                        trustPrompt = null,
                        addResult = result,
                    )
                }
                if (result is AddExtensionSourceResult.Added) {
                    performSourceAction(result.sourceId) { repository.refresh(result.sourceId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isAdding = false, trustPrompt = null, actionFailed = true) }
            }
        }
    }

    override fun cancelTrust() {
        _uiState.update { it.copy(trustPrompt = null) }
    }

    override fun setEnabled(sourceId: String, enabled: Boolean) = performSourceAction(sourceId) {
        repository.setEnabled(sourceId, enabled)
    }

    override fun removeSource(sourceId: String) = performSourceAction(sourceId) {
        repository.remove(sourceId)
    }

    override fun refreshSource(sourceId: String) = performSourceAction(sourceId) {
        repository.refresh(sourceId)
    }

    override fun activate(sourceId: String, extensionId: String) = performSourceAction(sourceId) {
        repository.activate(sourceId, extensionId)
    }

    override fun selectActiveSource(key: ExtensionSelectionKey?) = performPolicyAction {
        if (key == null || isUsableEnabledExtension(key)) {
            productPolicyRepository?.selectActiveSource(key)
        } else {
            throw IllegalArgumentException("The selected active source is no longer usable")
        }
    }

    override fun selectNavigationProvider(key: ExtensionSelectionKey?) = performPolicyAction {
        if (key == null || isUsableNavigationProvider(key)) {
            productPolicyRepository?.selectNavigationProvider(key)
        } else {
            throw IllegalArgumentException("The selected navigation provider is no longer usable")
        }
    }

    override fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) =
        performPolicyAction {
            if (!isUsableEnabledExtension(key)) {
                throw IllegalArgumentException("Preferences require a usable installed extension")
            }
            productPolicyRepository?.setPreferences(key, preferences)
        }

    override fun removeExtension(sourceId: String, extensionId: String) = performSourceAction(sourceId) {
        repository.removeExtension(sourceId, extensionId)
    }

    override fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) =
        performSourceAction(sourceId) {
            repository.rollback(sourceId, extensionId, expectedGeneration, targetDigest)
        }

    override fun setProviderOrder(keys: List<ExtensionSelectionKey>) = performPolicyAction {
        productPolicyRepository?.setNavigationProviderOrder(keys)
    }

    override fun refreshDiagnostics() = performAction {
        val snapshot = uiState.value
        val keys = snapshot.sources.flatMap { source -> source.extensions.mapNotNull { source.selectionKey(it) } }
        val details = keys.associateWith { diagnosticsRepository?.inspect(it) ?: repository.diagnostics(it) }
        _uiState.update { if (it.sources == snapshot.sources && it.productPolicy == snapshot.productPolicy)
            it.copy(diagnostics = details) else it }
    }

    override fun clearActionFailure() {
        _uiState.update { it.copy(actionFailed = false) }
    }

    private fun performAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(actionFailed = false) }
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(actionFailed = true) }
            }
        }
    }

    /** Claim the source before launching so sibling extension taps cannot race the coroutine. */
    private fun performSourceAction(sourceId: String, action: suspend () -> Unit) {
        val claimed = synchronized(sourceActionLock) {
            val current = _uiState.value
            val sourceOperationActive = current.sources.any { source ->
                source.id == sourceId && source.extensions.any { it.updateState.isInFlight() }
            }
            if (sourceId in current.busySourceIds || sourceOperationActive) {
                false
            } else {
                _uiState.update { it.copy(busySourceIds = it.busySourceIds + sourceId) }
                true
            }
        }
        if (!claimed) return

        viewModelScope.launch {
            _uiState.update { it.copy(actionFailed = false) }
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(actionFailed = true) }
            } finally {
                synchronized(sourceActionLock) {
                    _uiState.update { it.copy(busySourceIds = it.busySourceIds - sourceId) }
                }
            }
        }
    }

    private fun performPolicyAction(action: suspend () -> Unit) {
        if (productPolicyRepository == null) return
        val state = uiState.value
        if (state.busySourceIds.isNotEmpty() || state.sources.any { source ->
                source.extensions.any { it.updateState.isInFlight() }
            }) return
        performAction(action)
    }

    private fun isUsableEnabledExtension(key: ExtensionSelectionKey): Boolean =
        uiState.value.sources.usableExtension(key) != null

    private fun isUsableNavigationProvider(key: ExtensionSelectionKey): Boolean =
        uiState.value.sources.any { source ->
            source.id == key.sourceId && source.enabled && source.extensions.any { extension ->
                    source.selectionKey(extension) == key &&
                    extension.capabilities.any { it == "OVERVIEW_NAVIGATION" || it == "EPISODE_NAVIGATION" } &&
                    extension.installedDigest != null && extension.installedUsable
            }
        }

    private fun ExtensionUpdateState.isInFlight(): Boolean = when (this) {
        ExtensionUpdateState.CHECKING,
        ExtensionUpdateState.DOWNLOADING,
        ExtensionUpdateState.VERIFYING,
        ExtensionUpdateState.STAGING,
        ExtensionUpdateState.ACTIVATING,
        ExtensionUpdateState.ROLLING_BACK -> true
        else -> false
    }
}

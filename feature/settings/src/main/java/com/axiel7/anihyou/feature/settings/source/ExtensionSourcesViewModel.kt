package com.axiel7.anihyou.feature.settings.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
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
    val url: String = "",
    val isAdding: Boolean = false,
    val addResult: AddExtensionSourceResult? = null,
    val actionFailed: Boolean = false,
)

interface ExtensionSourcesEvent {
    fun onUrlChanged(value: String)
    fun addSource()
    fun setEnabled(sourceId: String, enabled: Boolean)
    fun removeSource(sourceId: String)
    fun refreshSource(sourceId: String)
    fun activate(sourceId: String, extensionId: String)
    fun selectActiveSource(key: ExtensionSelectionKey?)
    fun selectNavigationProvider(key: ExtensionSelectionKey?)
    fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences)
    fun clearActionFailure()
}

class ExtensionSourcesViewModel(
    private val repository: ExtensionSourceRepository,
    private val productPolicyRepository: ExtensionProductPolicyRepository? = null,
) : ViewModel(), ExtensionSourcesEvent {

    private val _uiState = MutableStateFlow(
        ExtensionSourcesUiState(canEditProductPolicy = productPolicyRepository != null),
    )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.sources.collect { sources ->
                _uiState.update { it.copy(sources = sources) }
            }
        }
        productPolicyRepository?.let { policyRepository ->
            viewModelScope.launch {
                policyRepository.policy.collect { policy ->
                    _uiState.update { it.copy(productPolicy = policy) }
                }
            }
        }
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
                        addResult = result,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isAdding = false, actionFailed = true) }
            }
        }
    }

    override fun setEnabled(sourceId: String, enabled: Boolean) = performAction {
        repository.setEnabled(sourceId, enabled)
    }

    override fun removeSource(sourceId: String) = performAction {
        repository.remove(sourceId)
    }

    override fun refreshSource(sourceId: String) = performAction {
        repository.refresh(sourceId)
    }

    override fun activate(sourceId: String, extensionId: String) = performAction {
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

    private fun performPolicyAction(action: suspend () -> Unit) {
        if (productPolicyRepository == null) return
        performAction(action)
    }

    private fun isUsableEnabledExtension(key: ExtensionSelectionKey): Boolean =
        uiState.value.sources.usableExtension(key) != null

    private fun isUsableNavigationProvider(key: ExtensionSelectionKey): Boolean =
        uiState.value.sources.any { source ->
            source.id == key.sourceId && source.enabled && source.extensions.any { extension ->
                source.selectionKey(extension) == key &&
                    extension.capabilities.any { it == "OVERVIEW_NAVIGATION" || it == "EPISODE_NAVIGATION" } &&
                    extension.installedDigest != null && extension.activationAllowed && !extension.revoked
            }
        }
}

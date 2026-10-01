package com.axiel7.anihyou

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.feature.settings.source.ExtensionSourcesEvent
import com.axiel7.anihyou.feature.settings.source.ExtensionDataSourcePreferences
import com.axiel7.anihyou.feature.settings.source.ExtensionProviderDisplay
import com.axiel7.anihyou.feature.settings.source.ExtensionSourcesUiState
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import com.axiel7.anihyou.release.data.extension.FileExtensionProductPolicyRepository
import com.axiel7.anihyou.ui.screens.main.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtensionProductSettingsComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsKeepActiveSourceNavigationAndPerExtensionPreferencesSeparate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyA = selectionKey("source-a", "provider.alpha", "publisher.alpha", "provider-a")
        val keyB = selectionKey("source-b", "provider.beta", "publisher.beta", "provider-b")
        val eligibleKeys = setOf(keyA, keyB)
        val directory = context.cacheDir.resolve("extension-product-settings-${UUID.randomUUID()}")
        val policyRepository = FileExtensionProductPolicyRepository(directory) { it in eligibleKeys }
        val sources = mutableStateOf(listOf(source(keyA, "Signed Provider Alpha"), source(keyB, "Signed Provider Beta")))
        val event = PolicyBackedSettingsEvent(policyRepository, sources)

        try {
            composeRule.setContent {
                val policy by policyRepository.policy.collectAsState()
                MaterialTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        val state = ExtensionSourcesUiState(sources = sources.value, productPolicy = policy, canEditProductPolicy = true)
                        ExtensionDataSourcePreferences(state, event)
                        ExtensionProviderDisplay(state, event)
                    }
                }
            }

            val keyATag = keyA.testTagPart()
            val keyBTag = keyB.testTagPart()
            val activeNoneTag = "extension-product-active-none"
            val activeATag = "extension-product-active-$keyATag"
            val activeBTag = "extension-product-active-$keyBTag"
            val navigationBTag = "extension-product-navigation-$keyBTag"

            composeRule.onNodeWithTag(activeNoneTag).performScrollTo().assertIsSelected()
            composeRule.onNodeWithTag(activeATag).performScrollTo().performClick()
            awaitPolicy(policyRepository) { it.activeReleaseSource == keyA }
            composeRule.onNodeWithTag(activeATag).assertIsSelected()
            composeRule.onNodeWithTag(activeBTag).assertIsNotSelected()

            composeRule.onNodeWithTag(navigationBTag).performScrollTo().performClick()
            awaitPolicy(policyRepository) { it.preferredNavigationProvider == keyB }
            assertEquals(keyA, policyRepository.policy.value.activeReleaseSource)
            composeRule.onNodeWithTag(activeATag).assertIsSelected()
            composeRule.onNodeWithTag(navigationBTag).assertIsSelected()

            composeRule.onNodeWithTag(activeBTag).performScrollTo().performClick()
            awaitPolicy(policyRepository) { it.activeReleaseSource == keyB }
            composeRule.onNodeWithTag(activeATag).assertIsNotSelected()
            composeRule.onNodeWithTag(activeBTag).assertIsSelected()

            val dubOnlyTag = "extension-preference-priority-dub-only-$keyATag"
            composeRule.onNodeWithTag(dubOnlyTag).performScrollTo().performClick()
            awaitPolicy(policyRepository) {
                it.preferences[keyA]?.enabledTracks == setOf("DE_DUB", "EN_DUB")
            }
            assertEquals(
                setOf("DE_DUB", "EN_DUB"),
                policyRepository.policy.value.preferences.getValue(keyA).enabledTracks,
            )

            val englishPriorityTag = "extension-preference-language-en-$keyATag"
            composeRule.onNodeWithTag(englishPriorityTag).performScrollTo().performClick()
            awaitPolicy(policyRepository) { it.preferences[keyA]?.languageOrder?.firstOrNull() == "en" }

            val visibleA = "extension-preference-provider-visible-$keyATag"
            composeRule.onNodeWithTag(visibleA).performScrollTo().assertIsOn().performClick()
            awaitPolicy(policyRepository) { it.preferences[keyA]?.visibleInProviderField == false }
            composeRule.onNodeWithTag(visibleA).assertIsOff()

            val subB = "extension-preference-sub-$keyBTag"
            val dubB = "extension-preference-dub-$keyBTag"
            composeRule.onNodeWithTag(subB).performScrollTo().assertIsOn().performClick()
            awaitPolicy(policyRepository) { it.preferences[keyB]?.enabledTracks == setOf("DE_DUB", "EN_DUB") }
            composeRule.onNodeWithTag(dubB).performScrollTo().assertIsOn().performClick()
            awaitPolicy(policyRepository) { it.preferences[keyB]?.enabledTracks?.isEmpty() == true }
            composeRule.onNodeWithTag(subB).assertIsOff()
            composeRule.onNodeWithTag(dubB).assertIsOff()
            composeRule.onNodeWithTag("extension-preference-provider-visible-$keyBTag").performScrollTo().assertIsOn()

            val recreatedPolicy = FileExtensionProductPolicyRepository(directory) { it in eligibleKeys }
            val persistedA = recreatedPolicy.policy.value.preferences.getValue(keyA)
            val persistedB = recreatedPolicy.policy.value.preferences.getValue(keyB)
            assertEquals(setOf("DE_DUB", "EN_DUB"), persistedA.enabledTracks)
            assertEquals("en", persistedA.languageOrder.first())
            assertFalse(persistedA.visibleInProviderField)
            assertTrue(persistedB.enabledTracks.isEmpty())
            assertEquals(listOf("DE_SUB", "EN_SUB", "DE_DUB", "EN_DUB"), persistedB.preferredTrackOrder)

            runBlocking { policyRepository.invalidateSource(keyB.sourceId) }
            composeRule.runOnIdle {
                sources.value = sources.value.map { if (it.id == keyB.sourceId) it.copy(enabled = false) else it }
            }
            awaitPolicy(policyRepository) {
                it.activeReleaseSource == null && it.preferredNavigationProvider == null
            }
            composeRule.onNodeWithTag(activeNoneTag).assertIsSelected()

            composeRule.onNodeWithTag(activeATag).performScrollTo().performClick()
            awaitPolicy(policyRepository) { it.activeReleaseSource == keyA }
            runBlocking { policyRepository.invalidateSource(keyA.sourceId) }
            composeRule.runOnIdle {
                sources.value = sources.value.map { source ->
                    if (source.id != keyA.sourceId) source else source.copy(
                        extensions = source.extensions.map { it.copy(revoked = true) },
                    )
                }
            }
            awaitPolicy(policyRepository) { it.activeReleaseSource == null }
            composeRule.onNodeWithTag(activeNoneTag).performScrollTo().assertIsSelected()

            val finalReload = FileExtensionProductPolicyRepository(directory) { it in eligibleKeys }
            assertEquals(persistedA, finalReload.policy.value.preferences.getValue(keyA))
            assertEquals(persistedB, finalReload.policy.value.preferences.getValue(keyB))
        } finally {
            event.close()
            directory.deleteRecursively()
        }
    }

    private fun awaitPolicy(
        repository: FileExtensionProductPolicyRepository,
        predicate: (com.axiel7.anihyou.release.core.source.ExtensionProductPolicy) -> Boolean,
    ) {
        composeRule.waitUntil(timeoutMillis = 10_000) { predicate(repository.policy.value) }
        composeRule.waitForIdle()
    }

    private fun selectionKey(
        sourceId: String,
        extensionId: String,
        publisherId: String,
        providerId: String,
    ) = ExtensionSelectionKey(sourceId, extensionId, publisherId, providerId)

    private fun source(key: ExtensionSelectionKey, displayName: String) = ExtensionSource(
        id = key.sourceId,
        url = "https://${key.sourceId}.example.test/repository.json",
        origin = "signed-test-fixture",
        enabled = true,
        status = ExtensionSourceStatus.CURRENT,
        extensions = listOf(
            SourceExtension(
                extensionId = key.extensionId,
                displayName = displayName,
                version = "1.0.0",
                digest = "signed-digest-${key.extensionId}",
                releaseSequence = 1,
                capabilities = listOf("CALENDAR", "OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION"),
                installedVersion = "1.0.0",
                installedDigest = "installed-digest-${key.extensionId}",
                activationAllowed = true,
                providerId = key.providerId,
                publisherId = key.publisherId,
                supportedTracks = setOf("DE_SUB", "DE_DUB", "EN_SUB", "EN_DUB"),
            ),
        ),
    )

    private fun ExtensionSelectionKey.testTagPart(): String =
        listOf(sourceId, extensionId, publisherId, providerId).joinToString("-")

    private class PolicyBackedSettingsEvent(
        private val repository: ExtensionProductPolicyRepository,
        private val sources: MutableState<List<ExtensionSource>>,
    ) : ExtensionSourcesEvent {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        override fun onUrlChanged(value: String) = Unit
        override fun addSource() = Unit

        override fun setEnabled(sourceId: String, enabled: Boolean) {
            scope.launch {
                if (!enabled) repository.invalidateSource(sourceId)
                sources.value = sources.value.map {
                    if (it.id == sourceId) it.copy(enabled = enabled) else it
                }
            }
        }

        override fun removeSource(sourceId: String) {
            scope.launch {
                repository.invalidateSource(sourceId)
                sources.value = sources.value.filterNot { it.id == sourceId }
            }
        }

        override fun refreshSource(sourceId: String) = Unit
        override fun activate(sourceId: String, extensionId: String) = Unit

        override fun selectActiveSource(key: ExtensionSelectionKey?) {
            scope.launch { repository.selectActiveSource(key) }
        }

        override fun selectNavigationProvider(key: ExtensionSelectionKey?) {
            scope.launch { repository.selectNavigationProvider(key) }
        }

        override fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) {
            scope.launch { repository.setPreferences(key, preferences) }
        }

        override fun clearActionFailure() = Unit

        fun close() = scope.cancel()
    }
}

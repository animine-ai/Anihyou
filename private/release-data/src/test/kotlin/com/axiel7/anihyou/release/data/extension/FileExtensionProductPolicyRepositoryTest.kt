package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileExtensionProductPolicyRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `provider order survives restart without changing release selection or generation`() = runBlocking {
        val directory = temporaryFolder.newFolder("provider-order")
        val repository = repository(directory)
        repository.selectActiveSource(KEY_A)
        val before = repository.policy.value
        repository.setNavigationProviderOrder(listOf(KEY_B, KEY_A))
        assertEquals(KEY_A, repository.policy.value.activeReleaseSource)
        assertEquals(before.releaseGeneration, repository.policy.value.releaseGeneration)
        assertEquals("allowed", repository.withCurrentSelection(before) { "allowed" })
        assertNull(repository.withCurrentPolicy(before) { "stale" })
        assertEquals(listOf(KEY_B, KEY_A), repository(directory).policy.value.navigationProviderOrder)
    }
    @Test fun `hiding preferred provider and display reset each commit once and preserve release policy`() = runBlocking {
        val directory = temporaryFolder.newFolder("display-reset")
        val repo = repository(directory)
        val tracks = ExtensionPreferences(setOf("DE_DUB"), listOf("DE_DUB"), listOf("de"))
        repo.setPreferences(KEY_A, tracks); repo.selectActiveSource(KEY_A)
        repo.selectNavigationProvider(KEY_B); repo.setNavigationProviderOrder(listOf(KEY_B, KEY_A))
        val beforeHide = repo.policy.value
        repo.setProviderVisibility(KEY_B, false, ExtensionPreferences())
        val hidden = repo.policy.value
        assertEquals(beforeHide.generation + 1, hidden.generation)
        assertFalse(hidden.preferencesFor(KEY_B).visibleInProviderField)
        assertNull(hidden.preferredNavigationProvider)
        assertEquals(KEY_A, hidden.activeReleaseSource); assertEquals(beforeHide.releaseGeneration, hidden.releaseGeneration)
        repo.setProviderVisibility(KEY_A, false, tracks)
        val beforeReset = repo.policy.value
        repo.resetProviderDisplay(listOf(KEY_A, KEY_B))
        val reset = repo.policy.value
        assertEquals(beforeReset.generation + 1, reset.generation)
        assertTrue(reset.preferencesFor(KEY_A).visibleInProviderField); assertTrue(reset.preferencesFor(KEY_B).visibleInProviderField)
        assertEquals(tracks, reset.preferencesFor(KEY_A))
        assertNull(reset.preferredNavigationProvider); assertTrue(reset.navigationProviderOrder.isEmpty())
        assertEquals(KEY_A, reset.activeReleaseSource); assertEquals(beforeReset.releaseGeneration, reset.releaseGeneration)
        assertEquals(reset, repository(directory).policy.value)
    }

    @Test
    fun `old policy migrates provider order and removal preserves preferences for reinstall`() = runBlocking {
        val directory = temporaryFolder.newFolder("migration-order")
        val repository = repository(directory)
        val preferences = ExtensionPreferences(setOf("DE_DUB"), listOf("DE_DUB"))
        repository.setPreferences(KEY_A, preferences)
        repository.selectActiveSource(KEY_A)
        repository.selectNavigationProvider(KEY_B)
        val file = File(directory, "product-policy.json")
        val original = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        file.writeText(kotlinx.serialization.json.JsonObject(original - "navigationProviderOrder").toString())
        val restored = repository(directory)
        assertTrue(restored.policy.value.navigationProviderOrder.isEmpty())
        restored.invalidateExtension(KEY_A)
        assertNull(restored.policy.value.activeReleaseSource)
        assertEquals(KEY_B, restored.policy.value.preferredNavigationProvider)
        restored.selectActiveSource(KEY_A)
        assertEquals(preferences, restored.policy.value.preferencesFor(KEY_A))
    }

    @Test
    fun `new repository has no active source and cannot commit through an empty selection`() = runBlocking {
        val repository = repository(temporaryFolder.newFolder("no-active-source"))
        val snapshot = repository.policy.value
        var committed = false

        val result = repository.withCurrentSelection(snapshot) {
            committed = true
            "committed"
        }

        assertNull(snapshot.activeReleaseSource)
        assertNull(result)
        assertFalse(committed)
        assertEquals(0L, snapshot.releaseGeneration)
    }

    @Test
    fun `source switch navigation selection and per-source tracks survive repository restart`() = runBlocking {
        val directory = temporaryFolder.newFolder("restart")
        val repository = repository(directory)
        val activePreferences = ExtensionPreferences(
            enabledTracks = setOf("DE_DUB"),
            preferredTrackOrder = listOf("DE_DUB", "DE_SUB"),
            languageOrder = listOf("de"),
        )
        val navigationPreferences = ExtensionPreferences(
            enabledTracks = setOf("DE_SUB"),
            preferredTrackOrder = listOf("DE_SUB"),
            languageOrder = listOf("de"),
            visibleInProviderField = false,
        )

        repository.selectActiveSource(KEY_A)
        repository.selectNavigationProvider(KEY_B)
        assertEquals(KEY_A, repository.policy.value.activeReleaseSource)
        assertEquals(1L, repository.policy.value.releaseGeneration)
        repository.setPreferences(KEY_A, activePreferences)
        repository.setPreferences(KEY_B, navigationPreferences)
        repository.selectActiveSource(KEY_B)

        val beforeRestart = repository.policy.value
        val restarted = repository(directory)
        val restored = restarted.policy.value
        assertEquals(KEY_B, restored.activeReleaseSource)
        assertEquals(KEY_B, restored.preferredNavigationProvider)
        assertEquals(activePreferences, restored.preferencesFor(KEY_A))
        assertEquals(navigationPreferences, restored.preferencesFor(KEY_B))
        assertEquals(beforeRestart.generation, restored.generation)
        assertEquals(beforeRestart.releaseGeneration, restored.releaseGeneration)
        assertEquals(3L, restored.releaseGeneration)
    }

    @Test
    fun `preferences remain scoped to the complete source identity`() = runBlocking {
        val repository = repository(temporaryFolder.newFolder("preference-scope"))
        val sourceA = ExtensionPreferences(enabledTracks = setOf("DE_DUB"), preferredTrackOrder = listOf("DE_DUB"))
        val sourceB = ExtensionPreferences(enabledTracks = setOf("DE_SUB"), preferredTrackOrder = listOf("DE_SUB"))

        repository.setPreferences(KEY_A, sourceA)
        repository.setPreferences(KEY_SAME_EXTENSION_DIFFERENT_SOURCE, sourceB)

        assertEquals(sourceA, repository.policy.value.preferencesFor(KEY_A))
        assertEquals(sourceB, repository.policy.value.preferencesFor(KEY_SAME_EXTENSION_DIFFERENT_SOURCE))
        assertEquals(ExtensionPreferences(), repository.policy.value.preferencesFor(KEY_C))
    }

    @Test
    fun `navigation preference changes preserve the active release snapshot and source`() = runBlocking {
        val repository = repository(temporaryFolder.newFolder("navigation-independent"))
        repository.selectActiveSource(KEY_A)
        val snapshot = repository.policy.value
        val releaseGeneration = snapshot.releaseGeneration

        repository.selectNavigationProvider(KEY_B)
        val productPolicyResult = repository.withCurrentPolicy(snapshot) { "launch-must-be-stale" }
        var committed = false
        val result = repository.withCurrentSelection(snapshot) {
            committed = true
            "still-current"
        }

        assertEquals("still-current", result)
        assertNull(productPolicyResult)
        assertTrue(committed)
        assertEquals(KEY_A, repository.policy.value.activeReleaseSource)
        assertEquals(KEY_B, repository.policy.value.preferredNavigationProvider)
        assertEquals(releaseGeneration, repository.policy.value.releaseGeneration)
    }

    @Test
    fun `selection snapshot is stale after switching from A to B and back to A`() = runBlocking {
        val repository = repository(temporaryFolder.newFolder("a-b-a"))
        repository.selectActiveSource(KEY_A)
        val snapshot = repository.policy.value
        var committed = false

        repository.selectActiveSource(KEY_B)
        repository.selectActiveSource(KEY_A)
        val result = repository.withCurrentSelection(snapshot) {
            committed = true
            "must-not-commit"
        }

        assertEquals(KEY_A, repository.policy.value.activeReleaseSource)
        assertTrue(repository.policy.value.releaseGeneration > snapshot.releaseGeneration)
        assertNull(result)
        assertFalse(committed)
    }

    @Test
    fun `active source track preference change invalidates its release snapshot`() = runBlocking {
        val repository = repository(temporaryFolder.newFolder("active-track-change"))
        repository.selectActiveSource(KEY_A)
        val snapshot = repository.policy.value

        repository.setPreferences(KEY_A, ExtensionPreferences(
            enabledTracks = setOf("DE_DUB"), preferredTrackOrder = listOf("DE_DUB"),
        ))
        var committed = false
        val result = repository.withCurrentSelection(snapshot) {
            committed = true
            "must-not-commit"
        }

        assertTrue(repository.policy.value.releaseGeneration > snapshot.releaseGeneration)
        assertNull(result)
        assertFalse(committed)
    }

    @Test
    fun `navigation preference switch waits until an in progress launch leaves current policy guard`() = runBlocking {
        val repository = repository(temporaryFolder.newFolder("launch-policy-lock"))
        repository.selectActiveSource(KEY_A)
        val snapshot = repository.policy.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val launched = async(Dispatchers.Default) {
            repository.withCurrentPolicy(snapshot) {
                entered.complete(Unit)
                release.await()
                "launch-finished"
            }
        }
        entered.await()

        val switchStarted = CompletableDeferred<Unit>()
        val switchFinished = CompletableDeferred<Unit>()
        val switching = async(Dispatchers.IO) {
            switchStarted.complete(Unit)
            repository.selectNavigationProvider(KEY_B)
            switchFinished.complete(Unit)
        }
        switchStarted.await()
        val switchedBeforeLaunchFinished = withTimeoutOrNull(200) { switchFinished.await() }

        assertNull(switchedBeforeLaunchFinished)
        release.complete(Unit)
        assertEquals("launch-finished", launched.await())
        switching.await()
        assertEquals(KEY_B, repository.policy.value.preferredNavigationProvider)
    }

    @Test
    fun `concurrent active and navigation switches preserve both durable selections`() = runBlocking {
        val directory = temporaryFolder.newFolder("concurrent-switch")
        val repository = repository(directory)
        val start = CompletableDeferred<Unit>()
        val active = async(Dispatchers.Default) {
            start.await()
            repository.selectActiveSource(KEY_A)
        }
        val navigation = async(Dispatchers.Default) {
            start.await()
            repository.selectNavigationProvider(KEY_B)
        }

        start.complete(Unit)
        awaitAll(active, navigation)

        val current = repository.policy.value
        assertEquals(KEY_A, current.activeReleaseSource)
        assertEquals(KEY_B, current.preferredNavigationProvider)
        assertEquals(2L, current.generation)
        assertEquals(1L, current.releaseGeneration)
        val restarted = repository(directory).policy.value
        assertEquals(KEY_A, restarted.activeReleaseSource)
        assertEquals(KEY_B, restarted.preferredNavigationProvider)
        assertEquals(current.generation, restarted.generation)
        assertEquals(current.releaseGeneration, restarted.releaseGeneration)
    }

    private fun repository(directory: File) = FileExtensionProductPolicyRepository(directory) {
        it in ELIGIBLE_KEYS
    }

    companion object {
        private val KEY_A = ExtensionSelectionKey("source-a", "de.fixture", "fixture-publisher", "fixture-provider")
        private val KEY_B = ExtensionSelectionKey("source-b", "de.watch", "watch-publisher", "watch-provider")
        private val KEY_C = ExtensionSelectionKey("source-c", "de.other", "other-publisher", "other-provider")
        private val KEY_SAME_EXTENSION_DIFFERENT_SOURCE = ExtensionSelectionKey(
            "source-a-copy", "de.fixture", "fixture-publisher", "fixture-provider",
        )
        private val ELIGIBLE_KEYS = setOf(KEY_A, KEY_B, KEY_C, KEY_SAME_EXTENSION_DIFFERENT_SOURCE)
    }
}

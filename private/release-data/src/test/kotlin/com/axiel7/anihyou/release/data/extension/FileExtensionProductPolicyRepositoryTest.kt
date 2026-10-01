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
        var committed = false
        val result = repository.withCurrentSelection(snapshot) {
            committed = true
            "still-current"
        }

        assertEquals("still-current", result)
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

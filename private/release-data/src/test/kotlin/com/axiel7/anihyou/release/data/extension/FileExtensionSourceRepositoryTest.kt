package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionUpdateFailure
import com.axiel7.anihyou.release.core.source.ExtensionUpdateState
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Hermetic lifecycle and install tests backed by the signed EP03 test-only chain. */
class FileExtensionSourceRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `extension removal clears selection retains trust and reinstall preference`() = runBlocking {
        val rig = SourceRig()
        val directory = temporaryFolder.newFolder("remove-extension")
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.activate(id, EXTENSION_ID)
        val source = repository.source(id)
        val key = requireNotNull(source.extensions.single().let {
            com.axiel7.anihyou.release.core.source.ExtensionSelectionKey(source.id, it.extensionId, it.publisherId, it.providerId)
        })
        val preferences = com.axiel7.anihyou.release.core.source.ExtensionPreferences(setOf("DE_DUB"), listOf("DE_DUB"))
        repository.productPolicy.setPreferences(key, preferences)
        repository.productPolicy.selectActiveSource(key)
        val root = repository.source(id).rootDigest
        repository.removeExtension(id, EXTENSION_ID)
        assertNull(repository.productPolicy.policy.value.activeReleaseSource)
        assertNull(repository.loadInstalled(key))
        assertNull(repository.source(id).extensions.single().installedDigest)
        assertEquals(root, repository.source(id).rootDigest)
        repository.activate(id, EXTENSION_ID)
        assertNull(repository.productPolicy.policy.value.activeReleaseSource)
        assertEquals(preferences, repository.productPolicy.policy.value.preferencesFor(key))
        assertTrue(repository.loadInstalled(key) != null)
    }

    @Test
    fun `zero sources perform zero authentication transport storage or scheduling`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("empty"))

        assertFalse(repository.refreshEnabled())
        assertTrue(repository.sources.value.isEmpty())
        assertEquals(0, rig.bootstrap.calls.get())
        assertTrue(rig.transport.urls.isEmpty())
        assertEquals(0, rig.stores.size)
        assertEquals(0, rig.scheduler.calls.get())
    }

    @Test
    fun `trust availability follows the actual bootstrap not a build name or an empty field`() = runBlocking {
        val rig = SourceRig()
        // A bootstrap that can authenticate sources is available even before any source exists.
        assertTrue(rig.repository(temporaryFolder.newFolder("provisioned")).trustAvailable)
        // The production bootstrap authenticates nothing, so the product must say so up front.
        val production = FileExtensionSourceRepository(
            directory = temporaryFolder.newFolder("unprovisioned"),
            bootstrap = UnavailableExtensionSourceTrustBootstrap,
            transport = rig.transport,
            storeFactory = ExtensionSourceStoreFactory { _, _ -> error("no store without trust") },
            scheduler = rig.scheduler,
            clock = FIXED_CLOCK,
            runtimeSupported = true,
        )
        assertFalse(production.trustAvailable)
        assertTrue(rig.transport.urls.isEmpty())
        assertEquals(0, rig.stores.size)
    }

    @Test
    fun `unavailable independent authentication persists without any HTTP or store construction`() = runBlocking {
        val directory = temporaryFolder.newFolder("auth-unavailable")
        val rig = SourceRig(anchor = null)
        val repository = rig.repository(directory)
        val id = repository.addSource()

        repository.refresh(id)
        assertEquals(ExtensionSourceStatus.TRUST_UNAVAILABLE, repository.source(id).status)
        assertEquals(ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE, repository.source(id).lastFailure)
        repository.activate(id, EXTENSION_ID)
        assertTrue(rig.transport.urls.isEmpty())
        assertEquals(0, rig.stores.size)

        val restarted = rig.repository(directory)
        assertEquals(id, restarted.sources.value.single().id)
        assertEquals(ExtensionSourceStatus.TRUST_UNAVAILABLE, restarted.sources.value.single().status)
        restarted.activate(id, EXTENSION_ID)
        assertEquals(3, rig.bootstrap.calls.get())
        assertTrue(rig.transport.urls.isEmpty())
        assertEquals(0, rig.stores.size)
    }

    @Test
    fun `trusted add and refresh expose signed catalog metadata without downloading an archive`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("metadata-only"))
        val id = repository.addSource()

        assertEquals(0, rig.bootstrap.calls.get())
        assertTrue(rig.transport.urls.isEmpty())
        assertEquals(ExtensionSourceStatus.ADDED, repository.source(id).status)

        repository.refresh(id)

        val source = repository.source(id)
        assertEquals(ExtensionSourceStatus.CURRENT, source.status)
        assertEquals(1L, source.rootVersion)
        assertEquals(1L, source.indexSequence)
        assertEquals("fixture.release", source.extensions.single().extensionId)
        assertEquals("0.1.0", source.extensions.single().version)
        assertNull(source.extensions.single().installedVersion)
        assertEquals(listOf("https://packages.example.org/repository/root.json",
            "https://packages.example.org/repository/index.json"), rig.transport.urls)
        assertEquals(0, rig.transport.archiveRequests)
        assertEquals(0, rig.smokeCalls.get())
    }

    @Test
    fun `first activation downloads once and unchanged refresh plus activation reuse verified generation`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("install-once"))
        val id = repository.addSource()
        repository.refresh(id)
        assertEquals(0, rig.transport.archiveRequests)

        repository.activate(id, EXTENSION_ID)
        assertEquals(1, rig.transport.archiveRequests)
        assertEquals(1, rig.smokeCalls.get())
        val installedStore = rig.stores.last()
        assertEquals(1L, installedStore.snapshot().releaseHigh[EXTENSION_ID])
        assertEquals(OLD_DIGEST, installedStore.snapshot().generations[EXTENSION_ID]?.active?.digest)

        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)

        assertEquals(1, rig.transport.archiveRequests)
        assertEquals(1, rig.smokeCalls.get())
        assertEquals(ExtensionSourceStatus.CURRENT, repository.source(id).status)
        assertEquals("0.1.0", repository.source(id).extensions.single().installedVersion)
    }

    @Test
    fun `new signed index requires explicit activation and promotes only after archive smoke succeeds`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("upgrade"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        assertEquals(1, rig.transport.archiveRequests)

        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val offered = repository.source(id).extensions.single()
        assertEquals(2L, repository.source(id).indexSequence)
        assertTrue(offered.updateAvailable)
        assertEquals("0.1.0", offered.installedVersion)
        assertEquals(1, rig.transport.archiveRequests)

        repository.activate(id, EXTENSION_ID)

        assertEquals(2, rig.transport.archiveRequests)
        assertEquals(2, rig.smokeCalls.get())
        val generation = rig.stores.last().snapshot().generations.getValue(EXTENSION_ID)
        assertEquals("0.2.0", generation.active?.version)
        assertEquals(NEW_DIGEST, generation.active?.digest)
        assertEquals(NEW_DIGEST, generation.knownGood?.digest)
        assertEquals(OLD_DIGEST, generation.previousGood?.digest)
        assertFalse(repository.source(id).extensions.single().updateAvailable)
    }

    @Test
    fun `failed update smoke keeps last known good active and does not advance release high water`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("smoke-failure"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        rig.failedSmokeSequences += 2L

        repository.activate(id, EXTENSION_ID)

        val snapshot = rig.stores.last().snapshot()
        val generation = snapshot.generations.getValue(EXTENSION_ID)
        assertEquals(OLD_DIGEST, generation.active?.digest)
        assertEquals(OLD_DIGEST, generation.knownGood?.digest)
        assertEquals(1L, generation.packageGeneration)
        assertEquals(1L, snapshot.releaseHigh[EXTENSION_ID])
        assertEquals(ExtensionSourceFailure.INVALID_PACKAGE, repository.source(id).lastFailure)
        assertEquals(ExtensionUpdateFailure.SMOKE, repository.source(id).extensions.single().lastUpdateFailure)
        assertEquals(OLD_DIGEST, repository.source(id).extensions.single().installedDigest)
        assertTrue(repository.source(id).extensions.single().installedUsable)
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `corrupt update download is rejected before activation and preserves published LKG`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("corrupt-update-download"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val corrupt = decodeArchive("fixture-v2.arex.b64").also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rig.transport.archiveOverrides[NEW_PACKAGE_URL] = corrupt

        repository.activate(id, EXTENSION_ID)

        val visible = repository.source(id).extensions.single()
        val generation = rig.stores.last().snapshot().generations.getValue(EXTENSION_ID)
        assertEquals(OLD_DIGEST, generation.active?.digest)
        assertEquals(OLD_DIGEST, generation.knownGood?.digest)
        assertEquals(1L, generation.packageGeneration)
        assertEquals(1L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
        assertEquals(OLD_DIGEST, visible.installedDigest)
        assertTrue(visible.installedUsable)
        assertEquals(ExtensionUpdateFailure.DIGEST, visible.lastUpdateFailure)
        assertEquals(ExtensionSourceFailure.INVALID_PACKAGE, repository.source(id).lastFailure)
        assertTrue(rig.stores.last().snapshot().quarantinedDigests.isEmpty())
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `runtime rejection is classified before package download`() = runBlocking {
        val rig = SourceRig()
        val directory = temporaryFolder.newFolder("unsupported-runtime")
        var repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        rig.runtimeSupported = false
        repository = rig.repository(directory)
        repository.restoreInstalled()

        repository.activate(id, EXTENSION_ID)

        assertEquals(1, rig.transport.archiveRequests)
        assertEquals(ExtensionSourceFailure.UNSUPPORTED_RUNTIME, repository.source(id).lastFailure)
        assertEquals(ExtensionUpdateFailure.RUNTIME, repository.source(id).extensions.single().lastUpdateFailure)
        assertEquals(OLD_DIGEST, rig.stores.last().snapshot().generations.getValue(EXTENSION_ID).knownGood?.digest)
        assertEquals(1L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
        assertTrue(rig.stores.last().snapshot().quarantinedDigests.isEmpty())
    }

    @Test
    fun `pre-activation failure is classified without replacing the installed package`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("activation-failure"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        rig.failBeforeActive = true
        repository.activate(id, EXTENSION_ID)

        val generation = rig.stores.last().snapshot().generations.getValue(EXTENSION_ID)
        assertEquals(OLD_DIGEST, generation.active?.digest)
        assertEquals(OLD_DIGEST, generation.knownGood?.digest)
        assertEquals(1L, generation.packageGeneration)
        assertEquals(1L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
        assertEquals(ExtensionSourceFailure.INVALID_PACKAGE, repository.source(id).lastFailure)
        assertEquals(ExtensionUpdateFailure.ACTIVATION, repository.source(id).extensions.single().lastUpdateFailure)
        assertTrue(generation.active?.digest != NEW_DIGEST)
        assertTrue(rig.stores.last().snapshot().quarantinedDigests.isEmpty())
    }

    @Test
    fun `second update tap while first update is in smoke is dropped without an implicit retry`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("deduplicate-update"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val gate = BlockingSmokeGate(sequence = 2L)
        rig.blockingSmokeGate = gate

        val first = async(Dispatchers.IO) { repository.activate(id, EXTENSION_ID) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val duplicate = async(Dispatchers.IO) { repository.activate(id, EXTENSION_ID) }
        withTimeout(5_000) { duplicate.await() }
        assertEquals(2, rig.transport.archiveRequests)

        gate.open()
        first.await()
        val generation = rig.stores.last().snapshot().generations.getValue(EXTENSION_ID)
        assertEquals(NEW_DIGEST, generation.knownGood?.digest)
        assertEquals(2L, generation.packageGeneration)
        assertEquals(ExtensionUpdateState.UPDATED, rig.stores.last().snapshot().operations.getValue(EXTENSION_ID).state)
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `explicit rollback preserves preferences and published generation across restart`() = runBlocking {
        val directory = temporaryFolder.newFolder("rollback-restart-preferences")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val firstExtension = repository.source(id).extensions.single()
        val key = ExtensionSelectionKey(id, firstExtension.extensionId, firstExtension.publisherId, firstExtension.providerId)
        val preferences = ExtensionPreferences(setOf("DE_DUB"), listOf("DE_DUB"), listOf("de"))
        repository.productPolicy.setPreferences(key, preferences)
        repository.productPolicy.selectActiveSource(key)

        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val offered = repository.source(id).extensions.single()
        assertEquals(2L, offered.packageGeneration)
        assertEquals(OLD_DIGEST, offered.rollbackTarget?.digest)
        assertEquals("0.1.0", offered.rollbackTarget?.version)
        repository.rollback(id, EXTENSION_ID, offered.packageGeneration, OLD_DIGEST)

        val rolledBack = repository.source(id).extensions.single()
        assertEquals(OLD_DIGEST, rolledBack.installedDigest)
        assertTrue(rolledBack.installedUsable)
        assertEquals(3L, rolledBack.packageGeneration)
        assertEquals(ExtensionUpdateState.ROLLED_BACK, rolledBack.updateState)
        assertNull(rolledBack.lastUpdateFailure)
        assertEquals(2L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
        assertTrue(rig.stores.last().snapshot().quarantinedDigests.isEmpty())
        assertEquals(key, repository.productPolicy.policy.value.activeReleaseSource)
        assertEquals(preferences, repository.productPolicy.policy.value.preferencesFor(key))
        assertEquals(2, rig.transport.archiveRequests)

        val restarted = rig.repository(directory)
        restarted.restoreInstalled()
        val restored = restarted.source(id).extensions.single()
        assertEquals(OLD_DIGEST, restored.installedDigest)
        assertEquals(3L, restored.packageGeneration)
        assertEquals(ExtensionUpdateState.ROLLED_BACK, restored.updateState)
        assertEquals(key, restarted.productPolicy.policy.value.activeReleaseSource)
        assertEquals(preferences, restarted.productPolicy.policy.value.preferencesFor(key))
        assertEquals(OLD_DIGEST, restarted.loadInstalled(key)?.packageDigest)
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `restore recovers interrupted candidate to published LKG without downloading`() = runBlocking {
        val directory = temporaryFolder.newFolder("restore-interrupted-update")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val extension = repository.source(id).extensions.single()
        val key = ExtensionSelectionKey(id, EXTENSION_ID, extension.publisherId, extension.providerId)
        val preferences = ExtensionPreferences(setOf("DE_SUB"), listOf("DE_SUB"), listOf("de"))
        repository.productPolicy.setPreferences(key, preferences)
        repository.productPolicy.selectActiveSource(key)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)

        val store = rig.stores.last()
        store.beginOperation(EXTENSION_ID, ExtensionUpdateState.CHECKING, NEW_DIGEST, FIXED_NOW)
        val candidate = File(directory, "interrupted-v2.arex").apply {
            writeBytes(decodeArchive("fixture-v2.arex.b64"))
        }
        store.install(candidate, EXTENSION_ID, FIXED_NOW)
        candidate.delete()
        val pending = store.snapshot().generations.getValue(EXTENSION_ID)
        assertEquals(NEW_DIGEST, pending.active?.digest)
        assertEquals(OLD_DIGEST, pending.knownGood?.digest)
        assertEquals(1L, pending.packageGeneration)
        assertEquals(2L, pending.journalRevision)
        assertEquals(OLD_DIGEST, repository.loadInstalled(key)?.packageDigest)

        val restarted = rig.repository(directory)
        restarted.restoreInstalled()
        val recovered = rig.stores.last().snapshot()
        val generation = recovered.generations.getValue(EXTENSION_ID)
        assertEquals(OLD_DIGEST, generation.active?.digest)
        assertEquals(OLD_DIGEST, generation.knownGood?.digest)
        assertEquals(3L, generation.packageGeneration)
        assertEquals(3L, generation.journalRevision)
        assertEquals(2L, recovered.releaseHigh[EXTENSION_ID])
        assertTrue(NEW_DIGEST in recovered.quarantinedDigests)
        assertEquals(ExtensionUpdateState.ROLLED_BACK, recovered.operations.getValue(EXTENSION_ID).state)
        assertEquals("AUTOMATIC_SAFE_RECOVERY", recovered.operations.getValue(EXTENSION_ID).technicalCode)
        assertEquals(OLD_DIGEST, restarted.loadInstalled(key)?.packageDigest)
        assertEquals(preferences, restarted.productPolicy.policy.value.preferencesFor(key))
        assertEquals(1, rig.transport.archiveRequests)
    }

    @Test
    fun `offline refresh retains trusted installed selection and preferences`() = runBlocking {
        val directory = temporaryFolder.newFolder("offline-known-good")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val extension = repository.source(id).extensions.single()
        val key = ExtensionSelectionKey(id, extension.extensionId, extension.publisherId, extension.providerId)
        val preferences = ExtensionPreferences(setOf("DE_SUB"), listOf("DE_SUB"), listOf("de"))
        repository.productPolicy.setPreferences(key, preferences)
        repository.productPolicy.selectActiveSource(key)
        rig.transport.offline = true

        repository.refresh(id)

        val offline = repository.source(id)
        assertEquals(ExtensionSourceStatus.ERROR, offline.status)
        assertEquals(ExtensionSourceFailure.NETWORK, offline.lastFailure)
        assertEquals(OLD_DIGEST, offline.extensions.single().installedDigest)
        assertTrue(offline.extensions.single().installedUsable)
        assertFalse(offline.extensions.single().activationAllowed)
        assertEquals(key, repository.productPolicy.policy.value.activeReleaseSource)
        assertEquals(preferences, repository.productPolicy.policy.value.preferencesFor(key))
        assertEquals(OLD_DIGEST, repository.loadInstalled(key)?.packageDigest)

        val restarted = rig.repository(directory)
        restarted.restoreInstalled()
        assertEquals(key, restarted.productPolicy.policy.value.activeReleaseSource)
        assertEquals(preferences, restarted.productPolicy.policy.value.preferencesFor(key))
        assertEquals(OLD_DIGEST, restarted.loadInstalled(key)?.packageDigest)
        assertEquals(1, rig.transport.archiveRequests)
    }

    @Test
    fun `package removal fences an update blocked at verified smoke`() = runBlocking {
        val directory = temporaryFolder.newFolder("package-remove-during-update")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val extension = repository.source(id).extensions.single()
        val key = ExtensionSelectionKey(id, extension.extensionId, extension.publisherId, extension.providerId)
        val preferences = ExtensionPreferences(setOf("DE_SUB"), listOf("DE_SUB"), listOf("de"))
        repository.productPolicy.setPreferences(key, preferences)
        repository.productPolicy.selectActiveSource(key)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val gate = SmokeCancellationGate(sequence = 2L)
        rig.smokeGate = gate

        val update = async(Dispatchers.IO) { repository.activate(id, EXTENSION_ID) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val removal = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repository.removeExtension(id, EXTENSION_ID)
        }
        try {
            update.await()
            throw AssertionError("the package-removal intent must cancel the pending activation")
        } catch (_: CancellationException) { }
        removal.await()

        val snapshot = rig.stores.last().snapshot()
        assertFalse(EXTENSION_ID in snapshot.generations)
        assertEquals(1L, snapshot.releaseHigh[EXTENSION_ID])
        assertTrue(snapshot.quarantinedDigests.isEmpty())
        assertNull(repository.loadInstalled(key))
        assertNull(repository.productPolicy.policy.value.activeReleaseSource)
        assertEquals(preferences, repository.productPolicy.policy.value.preferencesFor(key))
        assertNull(repository.source(id).extensions.single().installedDigest)
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `source removal fences a racing update while retaining the prior installed journal`() = runBlocking {
        val directory = temporaryFolder.newFolder("source-remove-during-update")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val gate = SmokeCancellationGate(sequence = 2L)
        rig.smokeGate = gate

        val update = async(Dispatchers.IO) { repository.activate(id, EXTENSION_ID) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val removal = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repository.remove(id) }
        try {
            update.await()
            throw AssertionError("the source-removal intent must cancel the pending activation")
        } catch (_: CancellationException) { }
        removal.await()

        val snapshot = rig.stores.last().snapshot()
        val generation = snapshot.generations.getValue(EXTENSION_ID)
        assertEquals(OLD_DIGEST, generation.active?.digest)
        assertEquals(OLD_DIGEST, generation.knownGood?.digest)
        assertEquals(1L, generation.packageGeneration)
        assertEquals(1L, snapshot.releaseHigh[EXTENSION_ID])
        assertTrue(repository.sources.value.none { it.id == id })
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `rollback cancellation during smoke preserves current generation without quarantining target`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("cancel-repo-rollback"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val current = repository.source(id).extensions.single()
        assertEquals(NEW_DIGEST, current.installedDigest)
        val generationBefore = current.packageGeneration
        val gate = SmokeCancellationGate(sequence = 1L)
        rig.smokeGate = gate

        val rollback = launch(Dispatchers.IO) {
            repository.rollback(id, EXTENSION_ID, generationBefore, OLD_DIGEST)
        }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        rollback.cancel()
        rollback.join()

        val snapshot = rig.stores.last().snapshot()
        val generation = snapshot.generations.getValue(EXTENSION_ID)
        assertEquals(NEW_DIGEST, generation.active?.digest)
        assertEquals(NEW_DIGEST, generation.knownGood?.digest)
        assertEquals(generationBefore, generation.packageGeneration)
        assertEquals(2L, snapshot.releaseHigh[EXTENSION_ID])
        assertTrue(OLD_DIGEST !in snapshot.quarantinedDigests)
        assertEquals(ExtensionUpdateState.UPDATE_FAILED, snapshot.operations.getValue(EXTENSION_ID).state)
        assertEquals(ExtensionUpdateFailure.CANCELLED, snapshot.operations.getValue(EXTENSION_ID).failure)
        assertEquals(NEW_DIGEST, repository.loadInstalled(ExtensionSelectionKey(id, EXTENSION_ID,
            "fixture.publisher", "fixture"))?.packageDigest)
    }

    @Test
    fun `switching selected release source during update preserves identity and current selection`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("switch-during-update"))
        val firstId = repository.addSource()
        repository.refresh(firstId)
        repository.activate(firstId, EXTENSION_ID)
        val firstExtension = repository.source(firstId).extensions.single()
        val firstKey = ExtensionSelectionKey(firstId, EXTENSION_ID,
            firstExtension.publisherId, firstExtension.providerId)

        val secondId = (repository.add(SECOND_SOURCE_URL) as AddExtensionSourceResult.Added).sourceId
        repository.refresh(secondId)
        repository.activate(secondId, EXTENSION_ID)
        val secondExtension = repository.source(secondId).extensions.single()
        val secondKey = ExtensionSelectionKey(secondId, EXTENSION_ID,
            secondExtension.publisherId, secondExtension.providerId)
        repository.productPolicy.setPreferences(firstKey,
            ExtensionPreferences(setOf("DE_DUB"), listOf("DE_DUB"), listOf("de")))
        repository.productPolicy.selectActiveSource(firstKey)

        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(firstId)
        val gate = BlockingSmokeGate(sequence = 2L)
        rig.blockingSmokeGate = gate
        val update = async(Dispatchers.IO) { repository.activate(firstId, EXTENSION_ID) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))

        repository.productPolicy.selectActiveSource(secondKey)
        assertEquals(3, rig.transport.archiveRequests)

        gate.open()
        update.await()

        assertEquals(secondKey, repository.productPolicy.policy.value.activeReleaseSource)
        assertEquals(NEW_DIGEST, repository.source(firstId).extensions.single().installedDigest)
        assertEquals(OLD_DIGEST, repository.source(secondId).extensions.single().installedDigest)
        assertEquals(ExtensionUpdateState.UPDATED, rig.stores.first().snapshot().operations.getValue(EXTENSION_ID).state)
        assertNull(rig.stores.first().snapshot().operations.getValue(EXTENSION_ID).failure)
        assertEquals(3, rig.transport.archiveRequests)
    }

    @Test
    fun `rollback tap during update is rejected as busy and never retries after promotion`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("rollback-tap-during-update"))
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val gate = BlockingSmokeGate(sequence = 2L)
        rig.blockingSmokeGate = gate

        val update = async(Dispatchers.IO) { repository.activate(id, EXTENSION_ID) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val rollback = async(Dispatchers.IO) {
            // The later generation is the one an overlapping confirmation would name.
            repository.rollback(id, EXTENSION_ID, expectedGeneration = 2L, targetDigest = OLD_DIGEST)
        }
        try {
            withTimeout(5_000) { rollback.await() }
        } finally {
            gate.open()
        }
        update.await()

        val generation = rig.stores.last().snapshot().generations.getValue(EXTENSION_ID)
        assertEquals(NEW_DIGEST, generation.active?.digest)
        assertEquals(NEW_DIGEST, generation.knownGood?.digest)
        assertEquals(2L, generation.packageGeneration)
        assertEquals(ExtensionUpdateState.UPDATED, rig.stores.last().snapshot().operations.getValue(EXTENSION_ID).state)
        assertEquals(2, rig.transport.archiveRequests)
    }

    @Test
    fun `package lifecycle intent fences load that finishes verification after removal starts`() = runBlocking {
        val directory = temporaryFolder.newFolder("load-fenced-by-package-removal")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val extension = repository.source(id).extensions.single()
        val key = ExtensionSelectionKey(id, EXTENSION_ID, extension.publisherId, extension.providerId)
        repository.productPolicy.selectActiveSource(key)
        val gate = VerificationGate()
        rig.verificationGate = gate

        val loading = async(Dispatchers.IO) { repository.loadInstalled(key) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val removal = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repository.removeExtension(id, EXTENSION_ID)
        }
        withTimeout(5_000) {
            while (repository.productPolicy.policy.value.activeReleaseSource != null) yield()
        }
        gate.open()

        assertNull(loading.await())
        removal.await()
        assertNull(repository.productPolicy.policy.value.activeReleaseSource)
        assertFalse(EXTENSION_ID in rig.stores.last().snapshot().generations)
        assertEquals(1L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
    }

    @Test
    fun `invalid replayed equivocal and expired metadata leave accepted index unchanged`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("metadata-rejection"))
        val id = repository.addSource()
        repository.refresh(id)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val acceptedDigest = repository.source(id).indexDigest
        assertEquals(2L, repository.source(id).indexSequence)

        listOf(
            resource("index-invalid-signature.json"),
            resource("index.json"),
            resource("index-equivocation.json"),
            resource("index-expired.json"),
        ).forEach { rejected ->
            rig.transport.indexBytes = rejected
            repository.refresh(id)
            val state = repository.source(id)
            assertEquals(2L, state.indexSequence)
            assertEquals(acceptedDigest, state.indexDigest)
            assertEquals(ExtensionSourceFailure.INVALID_METADATA, state.lastFailure)
        }
        assertEquals(0, rig.transport.archiveRequests)
    }

    @Test
    fun `revoked generation stays unusable after restart and source tombstone re-add`() = runBlocking {
        val directory = temporaryFolder.newFolder("revoked-readd")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        assertEquals(1, rig.transport.archiveRequests)

        val extension = repository.source(id).extensions.single()
        val key = com.axiel7.anihyou.release.core.source.ExtensionSelectionKey(id, extension.extensionId,
            extension.publisherId, extension.providerId)
        val beforeRevocation = repository.diagnostics(key)
        assertEquals(extension.displayName, beforeRevocation["Signed displayName"])
        assertEquals(extension.installedDigest, beforeRevocation["Package SHA"])
        assertTrue(beforeRevocation["Key ID"].orEmpty().isNotBlank())

        rig.transport.indexBytes = resource("index-revoked.json")
        repository.refresh(id)
        assertTrue(repository.source(id).extensions.single().revoked)
        assertNull(repository.loadInstalled(key))
        val afterRevocation = repository.diagnostics(key)
        assertEquals(beforeRevocation["Signed displayName"], afterRevocation["Signed displayName"])
        assertEquals(beforeRevocation["Key ID"], afterRevocation["Key ID"])
        assertEquals(beforeRevocation["Package SHA"], afterRevocation["Package SHA"])
        assertEquals(ExtensionSourceStatus.REVOKED.name, afterRevocation["Trust status"])
        repository.remove(id)

        val restarted = rig.repository(directory)
        val readded = restarted.add(SOURCE_URL) as AddExtensionSourceResult.Added
        assertEquals(id, readded.sourceId)
        restarted.refresh(id)
        restarted.activate(id, EXTENSION_ID)

        assertEquals(1, rig.transport.archiveRequests)
        assertEquals(1L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
        assertNull(rig.stores.last().loadUsableExtension(EXTENSION_ID))
        assertTrue(restarted.source(id).extensions.single().revoked)
        assertEquals(ExtensionSourceFailure.INVALID_PACKAGE, restarted.source(id).lastFailure)
    }

    @Test
    fun `disabled source ignores refresh and activation and re-add preserves id generation and high water`() = runBlocking {
        val directory = temporaryFolder.newFolder("lifecycle-reopen")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        val requestsBeforeDisable = rig.transport.urls.size

        repository.setEnabled(id, false)
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        assertEquals(requestsBeforeDisable, rig.transport.urls.size)
        assertEquals(ExtensionSourceStatus.DISABLED, repository.source(id).status)

        val reopened = rig.repository(directory)
        assertEquals(id, reopened.sources.value.single().id)
        assertFalse(reopened.sources.value.single().enabled)
        assertEquals(AddExtensionSourceResult.Duplicate(id), reopened.add(SOURCE_URL))
        reopened.remove(id)

        val afterRemoval = rig.repository(directory)
        val readded = afterRemoval.add(SOURCE_URL) as AddExtensionSourceResult.Added
        assertEquals(id, readded.sourceId)
        afterRemoval.refresh(id)
        afterRemoval.activate(id, EXTENSION_ID)

        assertEquals(1, rig.transport.archiveRequests)
        assertEquals(1L, rig.stores.last().snapshot().releaseHigh[EXTENSION_ID])
        assertEquals(OLD_DIGEST, rig.stores.last().snapshot().generations[EXTENSION_ID]?.active?.digest)
        assertEquals(ExtensionSourceStatus.CURRENT, afterRemoval.source(id).status)
    }

    @Test
    fun `concurrent refreshes for one source share one metadata operation`() = runBlocking {
        val rig = SourceRig()
        val repository = rig.repository(temporaryFolder.newFolder("deduplicate"))
        val id = repository.addSource()
        val gate = IndexGate()
        rig.transport.indexGate = gate

        val first = async(Dispatchers.IO) { repository.refresh(id) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { repository.refresh(id) }
        second.await()
        assertEquals(2, rig.transport.urls.size)
        gate.release.complete(Unit)
        first.await()

        assertEquals(2, rig.transport.urls.size)
        assertEquals(ExtensionSourceStatus.CURRENT, repository.source(id).status)
    }

    @Test
    fun `disable cancellation fences an index response that arrives late`() = runBlocking {
        val directory = temporaryFolder.newFolder("cancel-late-index")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        val gate = IndexGate(ignoreCancellation = true)
        rig.transport.indexGate = gate

        val refresh = launch(Dispatchers.IO) { repository.refresh(id) }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        val disable = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repository.setEnabled(id, false)
        }
        withTimeout(5_000) {
            while (gate.operationJob?.isCancelled != true) yield()
        }
        gate.release.complete(Unit)
        refresh.join()
        disable.await()

        assertEquals(ExtensionSourceStatus.DISABLED, repository.source(id).status)
        assertNull(rig.stores.single().snapshot().index)
        assertNull(repository.source(id).lastSuccessAt)
        assertEquals(2, rig.transport.urls.size)
    }

    @Test
    fun `disable during synchronous smoke prevents the candidate generation from becoming active`() = runBlocking {
        val directory = temporaryFolder.newFolder("cancel-before-active")
        val rig = SourceRig()
        val repository = rig.repository(directory)
        val id = repository.addSource()
        repository.refresh(id)
        repository.activate(id, EXTENSION_ID)
        rig.transport.indexBytes = resource("index-v2.json")
        repository.refresh(id)
        val smokeGate = SmokeCancellationGate(sequence = 2L)
        rig.smokeGate = smokeGate

        val activation = async(Dispatchers.IO) { repository.activate(id, EXTENSION_ID) }
        assertTrue(smokeGate.entered.await(5, TimeUnit.SECONDS))
        val disable = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repository.setEnabled(id, false)
        }
        try {
            activation.await()
            throw AssertionError("activation should observe the cancelled source lifecycle")
        } catch (_: CancellationException) {
            // Expected: beforeActivation fences the synchronous smoke/install boundary.
        }
        disable.await()

        val snapshot = rig.stores.last().snapshot()
        val generation = snapshot.generations.getValue(EXTENSION_ID)
        assertEquals(OLD_DIGEST, generation.active?.digest)
        assertEquals(OLD_DIGEST, generation.knownGood?.digest)
        assertEquals(1L, snapshot.releaseHigh[EXTENSION_ID])
        assertEquals(ExtensionSourceStatus.DISABLED, repository.source(id).status)
    }

    private suspend fun FileExtensionSourceRepository.addSource(): String =
        (add(SOURCE_URL) as AddExtensionSourceResult.Added).sourceId

    private fun FileExtensionSourceRepository.source(id: String): ExtensionSource =
        sources.value.single { it.id == id }

    private class SourceRig(
        anchor: AuthenticatedExtensionSourceAnchor? = TEST_ANCHOR,
        initialRuntimeSupported: Boolean = true,
    ) {
        val transport = FixtureTransport()
        val bootstrap = CountingBootstrap(anchor)
        val scheduler = CountingScheduler()
        val stores = CopyOnWriteArrayList<ExtensionInstallStore>()
        val smokeCalls = AtomicInteger()
        val failedSmokeSequences = ConcurrentHashMap.newKeySet<Long>()
        @Volatile var smokeGate: SmokeCancellationGate? = null
        @Volatile var blockingSmokeGate: BlockingSmokeGate? = null
        @Volatile var verificationGate: VerificationGate? = null
        @Volatile var failBeforeActive = false
        @Volatile var runtimeSupported = initialRuntimeSupported

        fun repository(directory: File): FileExtensionSourceRepository = FileExtensionSourceRepository(
            directory = directory,
            bootstrap = bootstrap,
            transport = transport,
            storeFactory = ExtensionSourceStoreFactory { storeDirectory, authenticated ->
                val store = ExtensionInstallStore(
                    directory = storeDirectory,
                    pin = authenticated.pin,
                    verifier = ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { module, navigation ->
                        verificationGate?.await()
                        StrictWasmModuleProfileVerifier().verify(module, navigation)
                    }),
                    hostRoles = setOf(SourceRole.CALENDAR),
                    hostHosts = authenticated.allowedHosts,
                    policyVersion = 1,
                    runtimeVersion = "test-wasmtime-profile",
                    failure = InstallFailureHook { boundary ->
                        if (failBeforeActive && boundary == InstallBoundary.BEFORE_ACTIVE) {
                            error("injected activation boundary failure")
                        }
                    },
                    smoke = { extension ->
                        smokeCalls.incrementAndGet()
                        smokeGate?.takeIf { it.sequence == extension.releaseSequence }?.awaitCancellation()
                        blockingSmokeGate?.takeIf { it.sequence == extension.releaseSequence }?.await()
                        if (extension.releaseSequence in failedSmokeSequences) {
                            error("injected smoke failure for release ${extension.releaseSequence}")
                        }
                    },
                )
                stores += store
                store
            },
            scheduler = scheduler,
            clock = FIXED_CLOCK,
            runtimeSupported = runtimeSupported,
        )
    }

    private class CountingBootstrap(initial: AuthenticatedExtensionSourceAnchor?) : ExtensionSourceTrustBootstrap {
        private val current = java.util.concurrent.atomic.AtomicReference(initial)
        val calls = AtomicInteger()
        override suspend fun authenticate(source: NormalizedExtensionSource): AuthenticatedExtensionSourceAnchor? {
            calls.incrementAndGet()
            return current.get()
        }
    }

    private class CountingScheduler : com.axiel7.anihyou.release.core.source.ExtensionSourceScheduler {
        val calls = AtomicInteger()
        override fun scheduleRefresh() { calls.incrementAndGet() }
    }

    private class FixtureTransport : ExtensionRepositoryTransport {
        private val rootBytes = resource("root.json")
        @Volatile var indexBytes: ByteArray = resource("index.json")
        @Volatile var indexGate: IndexGate? = null
        @Volatile var offline: Boolean = false
        val archiveOverrides = ConcurrentHashMap<String, ByteArray>()
        val urls = CopyOnWriteArrayList<String>()
        val archiveRequests: Int get() = urls.count { it.endsWith(".arex") }

        override suspend fun fetch(url: String, allowedOrigins: Set<String>, maxBytes: Int): ByteArray {
            urls += url
            if (offline) throw IOException("fixture transport offline")
            val content = when {
                url.endsWith("/root.json") -> rootBytes
                url.endsWith("/index.json") -> {
                    val gate = indexGate
                    if (gate != null) {
                        indexGate = null
                        gate.awaitBeforeResponse()
                    }
                    indexBytes
                }
                url == OLD_PACKAGE_URL -> archiveOverrides[url] ?: decodeArchive("fixture.arex.b64")
                url == NEW_PACKAGE_URL -> archiveOverrides[url] ?: decodeArchive("fixture-v2.arex.b64")
                else -> throw AssertionError("unexpected repository fetch: $url")
            }
            assertTrue("response exceeds declared request cap", content.size <= maxBytes)
            assertTrue("request has no authenticated origin", allowedOrigins.isNotEmpty())
            return content.copyOf()
        }
    }

    private class IndexGate(private val ignoreCancellation: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        @Volatile var operationJob: Job? = null

        suspend fun awaitBeforeResponse() {
            operationJob = currentCoroutineContext()[Job]
            entered.countDown()
            if (ignoreCancellation) withContext(NonCancellable) { release.await() }
            else release.await()
        }
    }

    private class SmokeCancellationGate(val sequence: Long) {
        val entered = CountDownLatch(1)

        fun awaitCancellation() {
            val operation = ExtensionSourceRuntimeCancellation.job.get()
                ?: throw AssertionError("source operation job was not propagated into the installer")
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (operation.isActive && System.nanoTime() < deadline) Thread.sleep(1)
            check(operation.isCancelled) { "source disable did not cancel the in-flight smoke operation" }
        }
    }

    private class BlockingSmokeGate(val sequence: Long) {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun await() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release blocked smoke" }
        }

        fun open() { release.countDown() }
    }

    private class VerificationGate {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun await() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release package verification" }
        }

        fun open() { release.countDown() }
    }

    private companion object {
        const val SOURCE_URL = "https://packages.example.org/repository"
        const val SECOND_SOURCE_URL = "https://packages.example.org/repository-alt"
        const val EXTENSION_ID = "fixture.release"
        const val OLD_DIGEST = "86a42b6f6445e3c00240b3d0ede218d25746565c2b04403ff28dc3f783c21afa"
        const val NEW_DIGEST = "8484aeb49082742ff6d9aaf32dcdcbc856dd8d311308400902afea2d357813bf"
        const val OLD_PACKAGE_URL = "https://packages.example.org/dist/fixture.release/0.1.0/$OLD_DIGEST.arex"
        const val NEW_PACKAGE_URL = "https://packages.example.org/dist/fixture.release/0.2.0/$NEW_DIGEST.arex"
        val FIXED_NOW: Instant = Instant.parse("2026-09-29T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        val TEST_PIN: AppTrustPin by lazy {
            val pin = Json.parseToJsonElement(resource("test-pin.json").toString(StandardCharsets.UTF_8)).jsonObject
            AppTrustPin(
                repositoryId = pin.getValue("repositoryId").jsonPrimitive.content,
                initialRootSha256 = pin.getValue("initialRootSha256").jsonPrimitive.content,
                distributionOrigins = pin.getValue("distributionOrigins").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )
        }
        val TEST_ANCHOR = AuthenticatedExtensionSourceAnchor(TEST_PIN, setOf("example.org"))

        fun resource(name: String): ByteArray = requireNotNull(
            FileExtensionSourceRepositoryTest::class.java.getResourceAsStream("/ep03/$name"),
        ) { "missing test fixture $name" }.use { it.readBytes() }

        fun decodeArchive(name: String): ByteArray = Base64.getDecoder().decode(
            resource(name).toString(StandardCharsets.US_ASCII).trim(),
        )
    }
}

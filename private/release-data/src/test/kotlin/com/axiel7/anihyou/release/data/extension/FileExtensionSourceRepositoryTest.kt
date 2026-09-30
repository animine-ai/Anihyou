package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import java.io.File
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
        assertEquals(1L, snapshot.releaseHigh[EXTENSION_ID])
        assertEquals(ExtensionSourceFailure.INVALID_PACKAGE, repository.source(id).lastFailure)
        assertEquals(2, rig.transport.archiveRequests)
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

        rig.transport.indexBytes = resource("index-revoked.json")
        repository.refresh(id)
        assertTrue(repository.source(id).extensions.single().revoked)
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

    private class SourceRig(anchor: AuthenticatedExtensionSourceAnchor? = TEST_ANCHOR) {
        val transport = FixtureTransport()
        val bootstrap = CountingBootstrap(anchor)
        val scheduler = CountingScheduler()
        val stores = CopyOnWriteArrayList<ExtensionInstallStore>()
        val smokeCalls = AtomicInteger()
        val failedSmokeSequences = ConcurrentHashMap.newKeySet<Long>()
        @Volatile var smokeGate: SmokeCancellationGate? = null

        fun repository(directory: File): FileExtensionSourceRepository = FileExtensionSourceRepository(
            directory = directory,
            bootstrap = bootstrap,
            transport = transport,
            storeFactory = ExtensionSourceStoreFactory { storeDirectory, authenticated ->
                val store = ExtensionInstallStore(
                    directory = storeDirectory,
                    pin = authenticated.pin,
                    verifier = ExtensionPackageVerifier(StrictWasmModuleProfileVerifier()),
                    hostRoles = setOf(SourceRole.CALENDAR),
                    hostHosts = authenticated.allowedHosts,
                    policyVersion = 1,
                    runtimeVersion = "test-wasmtime-profile",
                    smoke = { extension ->
                        smokeCalls.incrementAndGet()
                        smokeGate?.takeIf { it.sequence == extension.releaseSequence }?.awaitCancellation()
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
            runtimeSupported = true,
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
        val urls = CopyOnWriteArrayList<String>()
        val archiveRequests: Int get() = urls.count { it.endsWith(".arex") }

        override suspend fun fetch(url: String, allowedOrigins: Set<String>, maxBytes: Int): ByteArray {
            urls += url
            val content = when (url) {
                "$SOURCE_URL/root.json" -> rootBytes
                "$SOURCE_URL/index.json" -> {
                    val gate = indexGate
                    if (gate != null) {
                        indexGate = null
                        gate.awaitBeforeResponse()
                    }
                    indexBytes
                }
                OLD_PACKAGE_URL -> decodeArchive("fixture.arex.b64")
                NEW_PACKAGE_URL -> decodeArchive("fixture-v2.arex.b64")
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

    private companion object {
        const val SOURCE_URL = "https://packages.example.org/repository"
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

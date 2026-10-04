package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceScheduler
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.UnverifiedSourcePreview
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Explicit first trust (the temporary private-test workaround) on the signed EP03 test chain. The warning dialog is a pure
 * function of what these tests exercise: nothing is stored, installed or authorized before the user accepts exactly the root
 * that was shown, and the acceptance never moves to another source.
 */
class ManualSourceTrustTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class Rig(clock: Clock = CLOCK, rootBytes: ByteArray = resource("root.json")) {
        val transport = Transport(rootBytes)
        val scheduler = Scheduler()
        val stores = CopyOnWriteArrayList<ExtensionInstallStore>()
        val clock = clock

        fun repository(directory: File, manual: ManualExtensionTrustStore = ManualExtensionTrustStore(directory)) =
            FileExtensionSourceRepository(
                directory = directory,
                bootstrap = ManualTrustBootstrap(UnavailableExtensionSourceTrustBootstrap, manual),
                transport = transport,
                storeFactory = ExtensionSourceStoreFactory { storeDirectory, anchor ->
                    ExtensionInstallStore(storeDirectory, anchor.pin,
                        ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { module, navigation ->
                            StrictWasmModuleProfileVerifier().verify(module, navigation)
                        }), setOf(SourceRole.CALENDAR), anchor.allowedHosts, 1, "test-wasmtime-profile", smoke = { }
                    ).also { stores += it }
                },
                scheduler = scheduler,
                clock = clock,
                runtimeSupported = true,
                manualTrust = manual,
            )
    }

    private class Scheduler : ExtensionSourceScheduler {
        val calls = AtomicInteger()
        override fun scheduleRefresh() { calls.incrementAndGet() }
    }

    private class Transport(@Volatile var rootBytes: ByteArray) : ExtensionRepositoryTransport {
        val urls = CopyOnWriteArrayList<String>()
        @Volatile var offline = false
        override suspend fun fetch(url: String, allowedOrigins: Set<String>, maxBytes: Int): ByteArray {
            urls += url
            if (offline) throw IOException("offline")
            assertTrue(allowedOrigins.isNotEmpty())
            return when {
                url.endsWith("/root.json") -> rootBytes
                url.endsWith("/index.json") -> resource("index.json")
                url.endsWith(".arex") -> java.util.Base64.getDecoder().decode(resource("fixture.arex.b64").toString(StandardCharsets.US_ASCII).trim())
                else -> throw AssertionError("unexpected fetch $url")
            }.also { assertTrue(it.size <= maxBytes) }
        }
    }

    @Test
    fun `an unverified source is only previewed and nothing is stored installed or scheduled`() = runBlocking {
        val rig = Rig()
        val manual = ManualExtensionTrustStore(temporaryFolder.newFolder("preview"))
        val repository = rig.repository(temporaryFolder.newFolder("preview-repo"), manual)

        val result = repository.add(URL) as AddExtensionSourceResult.NeedsTrustConfirmation
        val preview = result.preview
        assertEquals(URL, preview.url)
        assertEquals("fixture.repository", preview.repositoryId)
        assertEquals("the fingerprint is the digest of the canonical signed root that was received", PIN.initialRootSha256, preview.rootFingerprint)
        assertTrue("CALENDAR" in preview.capabilities)
        assertEquals(listOf("example.org"), preview.hosts)
        assertEquals(listOf("fixture.publisher"), preview.publisherIds)
        // Only the root metadata was read: no index, no package, no store, no scheduled work, no stored acceptance.
        assertEquals(listOf("$URL/root.json"), rig.transport.urls.toList())
        assertTrue(repository.sources.value.isEmpty())
        assertEquals(0, rig.stores.size)
        assertEquals(0, rig.scheduler.calls.get())
        assertTrue(manual.all().isEmpty())
        assertTrue(manual.approvedAuthority().isEmpty())
        // Cancelling is simply not confirming; the next add asks again.
        assertTrue(repository.add(URL) is AddExtensionSourceResult.NeedsTrustConfirmation)
        assertTrue(repository.sources.value.isEmpty())
    }

    @Test
    fun `confirming stores the exact acceptance, marks the source and the chain verifies against it`() = runBlocking {
        val rig = Rig()
        val manualDirectory = temporaryFolder.newFolder("confirm")
        val manual = ManualExtensionTrustStore(manualDirectory)
        val repository = rig.repository(temporaryFolder.newFolder("confirm-repo"), manual)
        val preview = (repository.add(URL) as AddExtensionSourceResult.NeedsTrustConfirmation).preview

        val added = repository.confirmUnverifiedSource(preview) as AddExtensionSourceResult.Added
        val record = requireNotNull(manual.find(URL))
        assertEquals("MANUALLY_ACCEPTED_UNVERIFIED", record.trustClass)
        assertEquals(PIN.initialRootSha256, record.rootSha256)
        assertEquals("fixture.repository", record.repositoryId)
        assertEquals(setOf("example.org"), record.hosts)
        assertTrue(repository.sources.value.single().manuallyTrusted)
        assertEquals(1, rig.scheduler.calls.get())

        // The existing strict chain runs unchanged against the accepted anchor.
        repository.refresh(added.sourceId)
        assertEquals(ExtensionSourceStatus.CURRENT, repository.sources.value.single().status)
        repository.activate(added.sourceId, EXTENSION_ID)
        val extension = repository.sources.value.single().extensions.single()
        assertNotNull(extension.installedDigest)
        assertTrue(extension.installedUsable)
        // The fixture's coordinate is not the one the host supports, so the acceptance yields no release Authority.
        assertTrue(manual.approvedAuthority().isEmpty())
    }

    @Test
    fun `the acceptance survives a restart and is not asked again`() = runBlocking {
        val rig = Rig()
        val manualDirectory = temporaryFolder.newFolder("restart")
        val repoDirectory = temporaryFolder.newFolder("restart-repo")
        val first = rig.repository(repoDirectory, ManualExtensionTrustStore(manualDirectory))
        val preview = (first.add(URL) as AddExtensionSourceResult.NeedsTrustConfirmation).preview
        val id = (first.confirmUnverifiedSource(preview) as AddExtensionSourceResult.Added).sourceId
        first.refresh(id)

        val restartedStore = ManualExtensionTrustStore(manualDirectory)
        assertEquals(PIN.initialRootSha256, restartedStore.find(URL)?.rootSha256)
        val restarted = rig.repository(repoDirectory, restartedStore)
        assertTrue(restarted.sources.value.single().manuallyTrusted)
        assertEquals(AddExtensionSourceResult.Duplicate(id), restarted.add(URL))
        val before = rig.transport.urls.count { it.endsWith("/root.json") }
        restarted.refresh(id)
        assertEquals(ExtensionSourceStatus.CURRENT, restarted.sources.value.single().status)
        assertTrue("a refresh fetches metadata only, it never previews again", rig.transport.urls.count { it.endsWith("/root.json") } == before + 1)
    }

    @Test
    fun `two adds and one confirmation never transfer the acceptance to the other source`() = runBlocking {
        val rig = Rig()
        val manual = ManualExtensionTrustStore(temporaryFolder.newFolder("parallel"))
        val repository = rig.repository(temporaryFolder.newFolder("parallel-repo"), manual)
        val previews = listOf(URL, SECOND_URL).map { url -> async { (repository.add(url) as AddExtensionSourceResult.NeedsTrustConfirmation).preview } }.awaitAll()
        assertTrue(repository.sources.value.isEmpty())

        assertTrue(repository.confirmUnverifiedSource(previews[0]) is AddExtensionSourceResult.Added)
        assertNotNull(manual.find(URL))
        assertNull("the other source inherited nothing", manual.find(SECOND_URL))
        assertEquals(1, repository.sources.value.size)
        assertTrue(repository.add(SECOND_URL) is AddExtensionSourceResult.NeedsTrustConfirmation)
        // A preview of one source cannot confirm another URL either.
        assertTrue(repository.confirmUnverifiedSource(previews[1].copy(rootFingerprint = "0".repeat(64))) is AddExtensionSourceResult.PreviewFailed)
        assertNull(manual.find(SECOND_URL))
    }

    @Test
    fun `a root that changed between the dialog and the tap is not accepted`() = runBlocking {
        val rig = Rig()
        val manual = ManualExtensionTrustStore(temporaryFolder.newFolder("changed"))
        val repository = rig.repository(temporaryFolder.newFolder("changed-repo"), manual)
        val preview = (repository.add(URL) as AddExtensionSourceResult.NeedsTrustConfirmation).preview

        assertTrue(repository.confirmUnverifiedSource(preview.copy(rootFingerprint = "1".repeat(64))) is AddExtensionSourceResult.PreviewFailed)
        assertTrue(repository.confirmUnverifiedSource(preview.copy(repositoryId = "other.repository")) is AddExtensionSourceResult.PreviewFailed)
        assertTrue(manual.all().isEmpty())
        assertTrue(repository.sources.value.isEmpty())
    }

    @Test
    fun `an accepted root that later differs blocks the source and needs a new explicit acceptance`() = runBlocking {
        val rig = Rig()
        val manual = ManualExtensionTrustStore(temporaryFolder.newFolder("rotated"))
        val repository = rig.repository(temporaryFolder.newFolder("rotated-repo"), manual)
        val preview = (repository.add(URL) as AddExtensionSourceResult.NeedsTrustConfirmation).preview
        val id = (repository.confirmUnverifiedSource(preview) as AddExtensionSourceResult.Added).sourceId
        // The user accepted a different root earlier (simulated): what the server serves now does not match it.
        manual.accept(requireNotNull(manual.find(URL)).copy(rootSha256 = "2".repeat(64)))

        repository.refresh(id)
        assertEquals(ExtensionSourceFailure.INVALID_METADATA, repository.sources.value.single().lastFailure)
        assertTrue(repository.sources.value.single().extensions.isEmpty())
        // Removing the source removes the acceptance, and adding it again asks again.
        repository.remove(id)
        assertNull(manual.find(URL))
        assertTrue(repository.add(URL) is AddExtensionSourceResult.NeedsTrustConfirmation)
    }

    @Test
    fun `a forged, expired or unreachable root is never offered for acceptance`() = runBlocking {
        val forged = Json.parseToJsonElement(resource("root.json").toString(StandardCharsets.UTF_8)).jsonObject
        val signatures = forged.getValue("signatures").jsonArray
        val first = signatures.first().jsonObject
        val broken = first.getValue("signature").jsonPrimitive.content.let { sig -> (if (sig[0] == 'A') "B" else "A") + sig.drop(1) }
        val tampered = JsonObject(forged + ("signatures" to JsonArray(listOf(
            JsonObject(first + ("signature" to JsonPrimitive(broken)))) + signatures.drop(1))))
            .toString().toByteArray(StandardCharsets.UTF_8)
        for ((name, rig) in listOf(
            "bad signature" to Rig(rootBytes = tampered),
            "expired" to Rig(clock = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC)),
            "garbage" to Rig(rootBytes = "not json".toByteArray()),
            "offline" to Rig().also { it.transport.offline = true },
        )) {
            val manual = ManualExtensionTrustStore(temporaryFolder.newFolder("bad-$name"))
            val repository = rig.repository(temporaryFolder.newFolder("bad-repo-$name"), manual)
            assertEquals(name, AddExtensionSourceResult.PreviewFailed, repository.add(URL))
            assertTrue(name, manual.all().isEmpty())
            assertTrue(name, repository.sources.value.isEmpty())
            assertEquals(name, 0, rig.stores.size)
        }
    }

    @Test
    fun `the release Authority follows only the supported coordinate of an accepted root`() {
        val manual = ManualExtensionTrustStore(temporaryFolder.newFolder("authority"))
        fun record(url: String, extension: String, provider: String, roles: Set<String>) = ManualTrustRecord(
            url, "https://packages.example.org", "repo", "3".repeat(64), setOf("aniworld.to"),
            listOf(ManualTrustAuthority("publisher.a", "key-a", extension, provider, roles)), Instant.parse("2026-10-04T12:00:00Z"))
        assertTrue(manual.accept(record("https://packages.example.org/a", "de.aniworld", "aniworld", setOf("CALENDAR", "RECENT", "NOT_A_ROLE"))))
        assertTrue(manual.accept(record("https://packages.example.org/b", "other.ext", "aniworld", setOf("CALENDAR"))))
        assertTrue(manual.accept(record("https://packages.example.org/c", "de.aniworld", "other", setOf("CALENDAR"))))
        val tuples = manual.approvedAuthority()
        assertEquals(1, tuples.size)
        val tuple = tuples.single()
        assertEquals("publisher.a", tuple.publisherId)
        assertEquals("key-a", tuple.signingKeyId)
        assertEquals(setOf(SourceRole.CALENDAR, SourceRole.RECENT), tuple.roles)
        // Removing the source removes its Authority.
        manual.remove("https://packages.example.org/a")
        assertTrue(manual.approvedAuthority().isEmpty())
        assertFalse(manual.all().any { it.url.endsWith("/a") })
    }

    private companion object {
        const val URL = "https://packages.example.org/repository"
        const val SECOND_URL = "https://packages.example.org/repository-alt"
        const val EXTENSION_ID = "fixture.release"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC)
        val PIN: AppTrustPin by lazy {
            val pin = Json.parseToJsonElement(resource("test-pin.json").toString(StandardCharsets.UTF_8)).jsonObject
            AppTrustPin(pin.getValue("repositoryId").jsonPrimitive.content, pin.getValue("initialRootSha256").jsonPrimitive.content,
                pin.getValue("distributionOrigins").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }

        fun resource(name: String): ByteArray = requireNotNull(
            ManualSourceTrustTest::class.java.getResourceAsStream("/ep03/$name"),
        ) { "missing test fixture $name" }.use { it.readBytes() }
    }
}

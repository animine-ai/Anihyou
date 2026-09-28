package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.erdtman.jcs.JsonCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Focused persistence and recovery coverage for the package install state machine. */
class ExtensionInstallStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pre-activation fault boundaries survive restart without activating partial install`() {
        for (boundary in listOf(
            InstallBoundary.STAGED,
            InstallBoundary.VERIFIED,
            InstallBoundary.CONTENT_PLACED,
            InstallBoundary.BEFORE_ACTIVE,
        )) {
            val directory = temporaryFolder.newFolder("before-${boundary.name.lowercase()}")
            val fixture = StoreFixture(directory)
            val release = fixture.release(1)
            val firstIndex = fixture.index(sequence = 1, packages = listOf(release))
            val store = fixture.store()
            fixture.initialize(store, firstIndex)

            val interrupted = fixture.store { observed ->
                if (observed == boundary) throw SimulatedCrash(boundary)
            }
            assertCrash(boundary) { interrupted.install(release.archive, EXTENSION, NOW) }

            // A process restart reloads the last committed state and discards stale staging files.
            File(directory, "staging/abandoned.staging").writeBytes(byteArrayOf(1, 2, 3))
            val restarted = fixture.store()
            assertNull(load(restarted))
            assertEquals(0, File(directory, "staging").listFiles()!!.size)

            val receipt = restarted.install(release.archive, EXTENSION, NOW)
            assertEquals(1L, receipt.sequence)
            assertNotNull(load(restarted))
            if (boundary == InstallBoundary.CONTENT_PLACED) {
                assertTrue(File(directory, "content/${receipt.digest}.arex").isFile)
            }
        }
    }

    @Test
    fun `active commit and release high water survive failure after activation`() {
        val directory = temporaryFolder.newFolder("after-active")
        val fixture = StoreFixture(directory)
        val release = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release)))

        val interrupted = fixture.store { boundary ->
            if (boundary == InstallBoundary.AFTER_ACTIVE) throw SimulatedCrash(boundary)
        }
        assertCrash(InstallBoundary.AFTER_ACTIVE) { interrupted.install(release.archive, EXTENSION, NOW) }

        val restarted = fixture.store()
        assertEquals(release.binding.archiveSha256, load(restarted)!!.packageDigest)
        assertRejected { restarted.install(release.archive, EXTENSION, NOW) }
    }

    @Test
    fun `same sequence different content and index downgrade remain rejected after restart`() {
        val directory = temporaryFolder.newFolder("index-high-water")
        val fixture = StoreFixture(directory)
        val release = fixture.release(1)
        val accepted = fixture.index(7, listOf(release))
        val store = fixture.store()
        fixture.initialize(store, accepted)

        val restarted = fixture.store()
        assertRejected {
            restarted.acceptIndex(
                fixture.index(7, listOf(release), issuedAt = NOW.minusSeconds(30)).envelope,
                NOW,
            )
        }
        assertRejected { restarted.acceptIndex(fixture.index(6, listOf(release)).envelope, NOW) }

        // The failed candidates did not move the persisted high-water mark or poison the accepted index.
        val receipt = restarted.install(release.archive, EXTENSION, NOW)
        assertEquals(7L, receipt.indexSequence)
        assertEquals(release.binding.archiveSha256, load(fixture.store())!!.packageDigest)
    }

    @Test
    fun `rollback after promotion fault quarantines new generation and preserves release high water`() {
        val directory = temporaryFolder.newFolder("rollback-restart")
        val fixture = StoreFixture(directory)
        val release1 = fixture.release(1)
        var store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release1)))
        store.install(release1.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        val release2 = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(release1, release2)).envelope, NOW)
        store.install(release2.archive, EXTENSION, NOW)

        val promotionInterrupted = fixture.store { boundary ->
            if (boundary == InstallBoundary.AFTER_PROMOTION) throw SimulatedCrash(boundary)
        }
        assertCrash(InstallBoundary.AFTER_PROMOTION) { promotionInterrupted.promoteHealthy(NOW) }

        store = fixture.store()
        val fallback = store.quarantineAndRollback(NOW)
        assertEquals(release1.binding.archiveSha256, fallback!!.digest)
        assertNotNull(load(store))

        // Rollback restores the known-good pointer only; it never rewinds the release high-water.
        assertRejected { store.install(release2.archive, EXTENSION, NOW) }
        val afterRestart = fixture.store()
        assertEquals(release1.binding.archiveSha256, load(afterRestart)!!.packageDigest)
    }

    @Test
    fun `revoked known good is not selected or executable during rollback`() {
        val directory = temporaryFolder.newFolder("revoked-known-good")
        val fixture = StoreFixture(directory)
        val release1 = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release1)))
        store.install(release1.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        val release2 = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(release1.copy(revoked = true), release2)).envelope, NOW)
        store.install(release2.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        assertNull(store.quarantineAndRollback(NOW))
        assertNull(load(store))
        assertNull(load(fixture.store()))
    }

    @Test
    fun `corrupt active content falls back once to eligible prior known good after restart`() {
        val directory = temporaryFolder.newFolder("corrupt-active")
        val fixture = StoreFixture(directory)
        val release1 = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release1)))
        store.install(release1.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        val release2 = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(release1, release2)).envelope, NOW)
        val updated = store.install(release2.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)
        File(directory, "content/${updated.digest}.arex").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(release1.binding.archiveSha256, load(fixture.store())!!.packageDigest)
        assertEquals(release1.binding.archiveSha256, load(fixture.store())!!.packageDigest)
        assertRejected { fixture.store().install(release2.archive, EXTENSION, NOW) }
    }

    private fun load(store: ExtensionInstallStore) = runBlocking {
        store.loadUsable(ProviderId.parse(PROVIDER))
    }

    private fun assertCrash(expected: InstallBoundary, block: () -> Unit) {
        try {
            block()
        } catch (failure: SimulatedCrash) {
            assertEquals(expected, failure.boundary)
            return
        }
        throw AssertionError("expected injected fault at $expected")
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected install-store policy rejection")
    }

    private class SimulatedCrash(val boundary: InstallBoundary) : RuntimeException("fault at $boundary")

    private class StoreFixture(private val directory: File) {
        private val rootKeys = listOf(key(1), key(2), key(3))
        private val indexKey = key(4)
        private val publisherKey = key(5)
        private val rootDoc = rootDocument()
        private val pin = AppTrustPin(REPOSITORY, rootDoc.digest, setOf(CDN_ORIGIN))

        fun store(hook: (InstallBoundary) -> Unit = {}): ExtensionInstallStore = ExtensionInstallStore(
            directory = directory,
            pin = pin,
            verifier = ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { _, _ -> }),
            hostRoles = setOf(SourceRole.CALENDAR),
            hostHosts = setOf(HOST),
            policyVersion = 1,
            runtimeVersion = "wasmtime-48.0.3",
            smoke = {},
            failure = InstallFailureHook(hook),
        )

        fun initialize(store: ExtensionInstallStore, index: IndexDocument) {
            store.acceptRoot(rootDoc.envelope(rootKeys.take(2)), NOW)
            store.acceptIndex(index.envelope, NOW)
        }

        fun release(sequence: Long, revoked: Boolean = false): PackageDocument {
            val version = "1.0.$sequence"
            val displayName = "Demo $sequence"
            val archive = packageArchive(sequence, version, displayName)
            val manifest = manifest(sequence, version, displayName)
            val binding = VerifiedCatalogPackageBinding(
                extensionId = EXTENSION,
                providerId = PROVIDER,
                displayName = displayName,
                navigationCapabilities = emptySet(),
                publisherId = PUBLISHER,
                keyId = sha256(publisherKey.public),
                version = version,
                releaseSequence = sequence,
                archiveSha256 = sha256(archive.readBytes()),
                archiveBytes = archive.length(),
                canonicalManifestSha256 = sha256(canonical(manifest)),
                yanked = false,
            )
            return PackageDocument(archive, binding, revoked)
        }

        fun index(
            sequence: Long,
            packages: List<PackageDocument>,
            issuedAt: Instant = NOW.minusSeconds(60),
        ): IndexDocument {
            val entries = packages.map { pkg ->
                val binding = pkg.binding
                jsonObject(
                    "extensionId" to string(binding.extensionId),
                    "providerId" to string(binding.providerId),
                    "displayName" to string(binding.displayName),
                    "navigationCapabilities" to JsonArray(emptyList()),
                    "publisherId" to string(binding.publisherId),
                    "keyId" to string(binding.keyId),
                    "version" to string(binding.version),
                    "releaseSequence" to number(binding.releaseSequence),
                    "hostApiMin" to number(1),
                    "hostApiMax" to number(1),
                    "packageUrl" to string("$CDN_ORIGIN/packages/demo-${binding.releaseSequence}.arex"),
                    "archiveSha256" to string(binding.archiveSha256),
                    "archiveBytes" to number(binding.archiveBytes),
                    "manifestSha256" to string(binding.canonicalManifestSha256),
                    "yanked" to JsonPrimitive(false),
                    "revoked" to JsonPrimitive(pkg.revoked),
                )
            }
            val signed = jsonObject(
                "schemaVersion" to number(1),
                "repositoryId" to string(REPOSITORY),
                "rootVersion" to number(1),
                "sequence" to number(sequence),
                "issuedAt" to string(issuedAt.toString()),
                "expiresAt" to string(NOW.plusSeconds(3600).toString()),
                "entries" to JsonArray(entries),
            )
            return IndexDocument(signed, indexKey)
        }

        private fun rootDocument(): RootDocument {
            val allKeys = rootKeys + indexKey + publisherKey
            val signed = jsonObject(
                "schemaVersion" to number(1),
                "repositoryId" to string(REPOSITORY),
                "version" to number(1),
                "expiresAt" to string(NOW.plusSeconds(86400).toString()),
                "keys" to JsonArray(allKeys.map { key ->
                    jsonObject("keyId" to string(sha256(key.public)), "publicKey" to string(Base64.getEncoder().encodeToString(key.public)))
                }),
                "roles" to jsonObject(
                    "root" to jsonObject("threshold" to number(2), "keyIds" to JsonArray(rootKeys.map { string(sha256(it.public)) })),
                    "index" to jsonObject("threshold" to number(1), "keyIds" to JsonArray(listOf(string(sha256(indexKey.public))))),
                ),
                "publishers" to JsonArray(listOf(jsonObject(
                    "publisherId" to string(PUBLISHER),
                    "extensionId" to string(EXTENSION),
                    "providerId" to string(PROVIDER),
                    "keyId" to string(sha256(publisherKey.public)),
                    "roles" to JsonArray(listOf(string(SourceRole.CALENDAR.name))),
                    "navigation" to JsonArray(emptyList()),
                    "hosts" to JsonArray(listOf(string(HOST))),
                    "notBefore" to string(NOW.minusSeconds(3600).toString()),
                    "expiresAt" to string(NOW.plusSeconds(86400).toString()),
                ))),
                "revokedKeys" to JsonArray(emptyList()),
                "revokedDigests" to JsonArray(emptyList()),
            )
            return RootDocument(signed)
        }

        private fun packageArchive(sequence: Long, version: String, displayName: String): File {
            val module = WASM_MODULE
            val provenance = provenance(module)
            val notice = "SPDX-License-Identifier: MIT\n".toByteArray(StandardCharsets.UTF_8)
            val manifest = manifest(sequence, version, displayName, module, provenance, notice)
            val canonicalManifest = canonical(manifest)
            val signer = Ed25519Signer().apply { init(true, publisherKey.privateKey) }
            val message = PACKAGE_DOMAIN + canonicalManifest
            signer.update(message, 0, message.size)
            val signature = JsonObject(mapOf(
                "algorithm" to string("Ed25519"),
                "keyId" to string(sha256(publisherKey.public)),
                "signature" to string(Base64.getEncoder().encodeToString(signer.generateSignature())),
            )).toString().toByteArray(StandardCharsets.UTF_8)
            val entries = linkedMapOf(
                "manifest.json" to manifest.toByteArray(StandardCharsets.UTF_8),
                "module.wasm" to module,
                "provenance.json" to provenance,
                "NOTICE" to notice,
                "package.sig" to signature,
            )
            val archive = File(directory, "fixture-$sequence.arex")
            ZipArchiveOutputStream(archive).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putArchiveEntry(ZipArchiveEntry(name).apply {
                        time = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
                    })
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
                output.finish()
            }
            return archive
        }

        private fun manifest(
            sequence: Long,
            version: String,
            displayName: String,
            module: ByteArray = WASM_MODULE,
            provenance: ByteArray = provenance(module),
            notice: ByteArray = "SPDX-License-Identifier: MIT\n".toByteArray(StandardCharsets.UTF_8),
        ): String = """{"schemaVersion":1,"extensionId":"$EXTENSION","providerId":"$PROVIDER","displayName":"$displayName","version":"$version","releaseSequence":$sequence,"hostApiMin":1,"hostApiMax":1,"capabilities":["CALENDAR"],"navigationCapabilities":[],"allowedHosts":["$HOST"],"digests":{"module":{"sha256":"${sha256(module)}","bytes":${module.size}},"provenance":{"sha256":"${sha256(provenance)}","bytes":${provenance.size}},"notice":{"sha256":"${sha256(notice)}","bytes":${notice.size}}},"publisherId":"$PUBLISHER","keyId":"${sha256(publisherKey.public)}","sourceRepository":"https://github.com/example/extension","sourceCommit":"${"a".repeat(40)}","build":{"toolchainVersion":"rustc-1.88.0","target":"wasm32-wasip1","lockfileDigest":"${"b".repeat(64)}","workflowIdentity":".github/workflows/extension-build.yml"}}"""

        private fun provenance(module: ByteArray): ByteArray =
            """{"schemaVersion":1,"sourceRepository":"https://github.com/example/extension","sourceCommit":"${"a".repeat(40)}","licenseSpdx":["MIT"],"components":[],"localModifications":[],"compilerVersion":"rustc-1.88.0","sdkVersion":"wasm32-wasip1","dependencyLockDigest":"${"b".repeat(64)}","reproducibleBuildCommand":"cargo build --locked --release --target wasm32-wasip1","workflowIdentity":".github/workflows/extension-build.yml","moduleDigest":"${sha256(module)}"}"""
                .toByteArray(StandardCharsets.UTF_8)
    }

    private data class PackageDocument(
        val archive: File,
        val binding: VerifiedCatalogPackageBinding,
        val revoked: Boolean,
    )

    private data class RootDocument(val signed: JsonObject) {
        val digest: String get() = sha256(canonical(signed))
        fun envelope(signers: List<SigningKey>) = envelopeBytes("AREX-ROOT-V1\n", signed, signers)
    }

    private data class IndexDocument(val signed: JsonObject, val signer: SigningKey) {
        val envelope: ByteArray get() = envelopeBytes("AREX-INDEX-V1\n", signed, listOf(signer))
    }

    private data class SigningKey(val privateKey: Ed25519PrivateKeyParameters) {
        val public: ByteArray = privateKey.generatePublicKey().encoded
    }

    companion object {
        private const val REPOSITORY = "install-store-test"
        private const val CDN_ORIGIN = "https://cdn.example"
        private const val PUBLISHER = "test-publisher"
        private const val EXTENSION = "demo.extension"
        private const val PROVIDER = "demo"
        private const val HOST = "aniworld.to"
        private val NOW = Instant.parse("2026-09-28T12:00:00Z")
        private val WASM_MODULE = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        private val PACKAGE_DOMAIN = "AREX-PACKAGE-V1\n".toByteArray(StandardCharsets.UTF_8)

        private fun key(seed: Int) = SigningKey(Ed25519PrivateKeyParameters(ByteArray(32) { (seed + it).toByte() }, 0))
        private fun canonical(value: String) = JsonCanonicalizer(value).encodedString.toByteArray(StandardCharsets.UTF_8)
        private fun canonical(value: JsonObject) = canonical(value.toString())
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun string(value: String) = JsonPrimitive(value)
        private fun number(value: Long) = JsonPrimitive(value)
        private fun number(value: Int) = JsonPrimitive(value)
        private fun jsonObject(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(fields.toMap())

        private fun envelopeBytes(domain: String, signed: JsonObject, signers: List<SigningKey>): ByteArray {
            val message = domain.toByteArray(StandardCharsets.UTF_8) + canonical(signed)
            val signatures = signers.map { key ->
                val signer = Ed25519Signer()
                signer.init(true, key.privateKey)
                signer.update(message, 0, message.size)
                jsonObject(
                    "algorithm" to string("Ed25519"),
                    "keyId" to string(sha256(key.public)),
                    "signature" to string(Base64.getEncoder().encodeToString(signer.generateSignature())),
                )
            }
            return jsonObject(
                "signed" to signed,
                "signatures" to JsonArray(signatures),
            ).toString().toByteArray(StandardCharsets.UTF_8)
        }
    }
}

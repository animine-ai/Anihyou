package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.erdtman.jcs.JsonCanonicalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionPackageVerifierTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `signed exact layout package is verified and module bytes are defensive copies`() {
        val fixture = fixture()
        val verified = verifier().verify(
            stagedArchive = fixture.archive,
            catalog = fixture.catalog,
            publisherKey = fixture.publisherKey,
            hostAllowedRoles = setOf(SourceRole.CALENDAR),
            hostAllowedHosts = setOf("aniworld.to"),
            policyVersion = 1,
            runtimeVersion = "wasmtime-37.0.0",
            now = NOW,
        )

        assertEquals("aniworld.release", verified.extensionId.value)
        assertEquals("aniworld", verified.providerId.value)
        assertEquals("AniWorld", verified.displayName)
        assertEquals(emptySet<NavigationCapability>(), verified.navigationCapabilities)
        assertEquals(setOf(SourceRole.CALENDAR), verified.grantedRoles)
        assertEquals(setOf("aniworld.to"), verified.grantedHosts)
        assertArrayEquals(fixture.module, verified.moduleBytes)
        val exposed = verified.moduleBytes
        exposed[0] = 0
        assertArrayEquals(fixture.module, verified.moduleBytes)
    }

    @Test
    fun `signature tampering is rejected after authenticated package binding checks`() {
        val fixture = fixture(tamperSignature = true)

        val failure = reject(fixture)

        assertEquals(ExtensionPackageFailure.INVALID_SIGNATURE, failure.failure)
    }

    @Test
    fun `duplicate manifest keys are rejected before canonicalization`() {
        val fixture = fixture(duplicateManifestKey = true)

        val failure = reject(fixture)

        assertEquals(ExtensionPackageFailure.INVALID_MANIFEST, failure.failure)
    }

    @Test
    fun `undeclared archive entries are rejected`() {
        val fixture = fixture(extraEntry = "extra.txt" to "not part of AREX".toByteArray())

        val failure = reject(fixture)

        assertEquals(ExtensionPackageFailure.ARCHIVE_INVALID, failure.failure)
    }

    @Test
    fun `module content must match the signed digest`() {
        val fixture = fixture(moduleOverride = "different wasm bytes".toByteArray())

        val failure = reject(fixture)

        assertEquals(ExtensionPackageFailure.DIGEST_MISMATCH, failure.failure)
    }

    @Test
    fun `module profile rejection prevents package activation`() {
        val fixture = fixture()
        val verifier = ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { _, _ ->
            throw IllegalArgumentException("unexpected import")
        })

        val failure = reject(fixture, verifier)

        assertEquals(ExtensionPackageFailure.MODULE_PROFILE_REJECTED, failure.failure)
    }

    @Test
    fun `host destination policy must intersect signed and publisher grants`() {
        val fixture = fixture()

        val failure = reject(fixture, hostAllowedHosts = emptySet())

        assertEquals(ExtensionPackageFailure.KEY_UNAUTHORIZED, failure.failure)
    }

    @Test
    fun `signed navigation capabilities reach the wasm profile verifier`() {
        val fixture = fixture(navigation = "\"OVERVIEW_NAVIGATION\",\"EPISODE_NAVIGATION\"")
        var seen: Set<NavigationCapability> = emptySet()
        val verifier = ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { _, capabilities -> seen = capabilities })
        val verified = verifier.verify(fixture.archive, fixture.catalog, fixture.publisherKey,
            setOf(SourceRole.CALENDAR), setOf("aniworld.to"), 1, "wasmtime-37.0.0", NOW)
        assertEquals(setOf(NavigationCapability.OVERVIEW_NAVIGATION, NavigationCapability.EPISODE_NAVIGATION), seen)
        assertEquals(seen, verified.navigationCapabilities)
    }

    @Test
    fun `display name controls and bidi markers fail closed`() {
        for (name in listOf("", "bad\nname", "bad\u202ename", "bad\u2066name", "x".repeat(65))) {
            assertEquals(ExtensionPackageFailure.INVALID_MANIFEST, reject(fixture(displayName = name)).failure)
        }
    }

    @Test
    fun `unknown and duplicated navigation capabilities fail closed`() {
        for (navigation in listOf("\"OTHER\"", "\"OVERVIEW_NAVIGATION\",\"OVERVIEW_NAVIGATION\"")) {
            assertEquals(ExtensionPackageFailure.INVALID_MANIFEST, reject(fixture(navigation = navigation)).failure)
        }
    }

    @Test
    fun `catalog presentation and capabilities must match the signed manifest`() {
        val fixture = fixture()
        assertEquals(ExtensionPackageFailure.UNTRUSTED_BINDING,
            reject(fixture.copy(catalog = fixture.catalog.copy(displayName = "Other"))).failure)
        assertEquals(ExtensionPackageFailure.UNTRUSTED_BINDING,
            reject(fixture.copy(catalog = fixture.catalog.copy(
                navigationCapabilities = setOf(NavigationCapability.OVERVIEW_NAVIGATION)))).failure)
    }

    private fun reject(
        fixture: Fixture,
        verifier: ExtensionPackageVerifier = verifier(),
        hostAllowedHosts: Set<String> = setOf("aniworld.to"),
    ): ExtensionPackageVerificationException {
        try {
            verifier.verify(
                stagedArchive = fixture.archive,
                catalog = fixture.catalog,
                publisherKey = fixture.publisherKey,
                hostAllowedRoles = setOf(SourceRole.CALENDAR),
                hostAllowedHosts = hostAllowedHosts,
                policyVersion = 1,
                runtimeVersion = "wasmtime-37.0.0",
                now = NOW,
            )
        } catch (failure: ExtensionPackageVerificationException) {
            return failure
        }
        throw AssertionError("expected package verification to fail")
    }

    private fun verifier() = ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { module, _ ->
        assertTrue(module.contentEquals(WASM_MODULE))
    })

    private fun fixture(
        tamperSignature: Boolean = false,
        duplicateManifestKey: Boolean = false,
        extraEntry: Pair<String, ByteArray>? = null,
        moduleOverride: ByteArray? = null,
        displayName: String = "AniWorld",
        navigation: String = "",
    ): Fixture {
        val module = WASM_MODULE
        val provenance = provenance(module)
        val notice = "SPDX-License-Identifier: MIT\n".toByteArray(StandardCharsets.UTF_8)
        val manifest = manifest(module, provenance, notice, displayName.replace("\n", "\\n"), navigation)
        val canonicalManifest = JsonCanonicalizer(manifest).encodedString.toByteArray(StandardCharsets.UTF_8)
        val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val signatureMessage = "AREX-PACKAGE-V1\n".toByteArray(StandardCharsets.UTF_8) + canonicalManifest
        signer.update(signatureMessage, 0, signatureMessage.size)
        val signatureBytes = signer.generateSignature().also { signature ->
            if (tamperSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
        }
        val packageSignature = """{"algorithm":"Ed25519","keyId":"publisher-key-1","signature":"${Base64.toBase64String(signatureBytes)}"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val manifestBytes = if (duplicateManifestKey) {
            manifest.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"schemaVersion\":1,")
                .toByteArray(StandardCharsets.UTF_8)
        } else {
            manifest.toByteArray(StandardCharsets.UTF_8)
        }
        val entries = linkedMapOf(
            "manifest.json" to manifestBytes,
            "module.wasm" to (moduleOverride ?: module),
            "provenance.json" to provenance,
            "NOTICE" to notice,
            "package.sig" to packageSignature,
        )
        extraEntry?.let { entries[it.first] = it.second }
        val archive = temporaryFolder.newFile("package-${System.nanoTime()}.arex")
        ZipArchiveOutputStream(archive).use { output ->
            entries.forEach { (name, bytes) ->
                output.putArchiveEntry(ZipArchiveEntry(name))
                output.write(bytes)
                output.closeArchiveEntry()
            }
            output.finish()
        }
        val archiveBytes = archive.readBytes()
        val catalog = VerifiedCatalogPackageBinding(
            extensionId = "aniworld.release",
            providerId = "aniworld",
            displayName = displayName,
            navigationCapabilities = NavigationCapability.entries.filter { navigation.contains(it.name) }.toSet(),
            publisherId = "animine-ai",
            keyId = "publisher-key-1",
            version = "1.0.0",
            releaseSequence = 1,
            archiveSha256 = sha256(archiveBytes),
            archiveBytes = archiveBytes.size.toLong(),
            canonicalManifestSha256 = if (duplicateManifestKey) sha256("duplicate".toByteArray()) else sha256(canonicalManifest),
            yanked = false,
        )
        val publisherKey = AuthorizedExtensionPublisherKey(
            keyId = "publisher-key-1",
            publisherId = "animine-ai",
            extensionId = "aniworld.release",
            providerId = "aniworld",
            publicKey = privateKey.generatePublicKey().encoded,
            trustRootVersion = 1,
            allowedRoles = setOf(SourceRole.CALENDAR),
            allowedNavigationCapabilities = NavigationCapability.entries.toSet(),
            allowedHosts = setOf("aniworld.to"),
            notBefore = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
            revoked = false,
        )
        return Fixture(archive, catalog, publisherKey, module)
    }

    private fun manifest(module: ByteArray, provenance: ByteArray, notice: ByteArray, displayName: String, navigation: String): String =
        """{"schemaVersion":1,"extensionId":"aniworld.release","providerId":"aniworld","displayName":"$displayName","version":"1.0.0","releaseSequence":1,"hostApiMin":1,"hostApiMax":1,"capabilities":["CALENDAR"],"navigationCapabilities":[$navigation],"allowedHosts":["aniworld.to"],"digests":{"module":{"sha256":"${sha256(module)}","bytes":${module.size}},"provenance":{"sha256":"${sha256(provenance)}","bytes":${provenance.size}},"notice":{"sha256":"${sha256(notice)}","bytes":${notice.size}}},"publisherId":"animine-ai","keyId":"publisher-key-1","sourceRepository":"https://github.com/animine-ai/Anihyou","sourceCommit":"${"a".repeat(40)}","build":{"toolchainVersion":"rustc-1.88.0","target":"wasm32-wasip1","lockfileDigest":"${"b".repeat(64)}","workflowIdentity":".github/workflows/extension-build.yml"}}"""

    private fun provenance(module: ByteArray): ByteArray =
        """{"schemaVersion":1,"sourceRepository":"https://github.com/animine-ai/Anihyou","sourceCommit":"${"a".repeat(40)}","licenseSpdx":["MIT"],"components":[],"localModifications":[],"compilerVersion":"rustc-1.88.0","sdkVersion":"wasm32-wasip1","dependencyLockDigest":"${"b".repeat(64)}","reproducibleBuildCommand":"cargo build --locked --release --target wasm32-wasip1","workflowIdentity":".github/workflows/extension-build.yml","moduleDigest":"${sha256(module)}"}"""
            .toByteArray(StandardCharsets.UTF_8)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(
        val archive: File,
        val catalog: VerifiedCatalogPackageBinding,
        val publisherKey: AuthorizedExtensionPublisherKey,
        val module: ByteArray,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-28T12:00:00Z")
        val WASM_MODULE = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
    }
}

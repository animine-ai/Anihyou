package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.erdtman.jcs.JsonCanonicalizer

class ExtensionTrustVerifierTest {
    @Test
    fun `pinned initial root needs its threshold and is bounded by expiry`() {
        val fixture = TrustFixture()
        val initial = fixture.initialRoot()
        val verifier = fixture.verifier(initial)

        val trusted = verifier.root(initial.envelope(fixture.initialRootKeys.take(2)), null, NOW)

        assertEquals(1L, trusted.version)
        assertEquals(REPOSITORY_ID, trusted.repositoryId)
        assertEquals(initial.digest, trusted.digest)
        assertRejected { verifier.root(initial.envelope(fixture.initialRootKeys.take(1)), null, NOW) }
        assertRejected { verifier.root(initial.envelope(fixture.initialRootKeys.take(2)), null, NOW.plusSeconds(86400)) }
        assertRejected {
            ExtensionTrustVerifier(AppTrustPin(REPOSITORY_ID, "0".repeat(64), setOf("https://cdn.example")))
                .root(initial.envelope(fixture.initialRootKeys.take(2)), null, NOW)
        }
    }

    @Test
    fun `root rotation is signed by old and new thresholds and carries revocations forward`() {
        val fixture = TrustFixture()
        val initial = fixture.initialRoot()
        val verifier = fixture.verifier(initial)
        val oldRoot = verifier.root(initial.envelope(fixture.initialRootKeys.take(2)), null, NOW)
        val next = fixture.rotatedRoot(
            revokedKeys = setOf(fixture.initialIndexKey.id, fixture.orphanKey.id),
            revokedDigests = setOf(REVOKED_DIGEST),
        )

        val rotated = verifier.root(
            next.envelope(fixture.initialRootKeys.take(2) + fixture.rotatedRootKeys.take(2)),
            oldRoot,
            NOW,
        )

        assertEquals(2L, rotated.version)
        assertEquals(setOf(fixture.initialIndexKey.id, fixture.orphanKey.id), rotated.revokedKeys)
        assertEquals(setOf(REVOKED_DIGEST), rotated.revokedDigests)
        assertRejected { verifier.root(next.envelope(fixture.initialRootKeys.take(2)), oldRoot, NOW) }
        assertRejected { verifier.root(next.envelope(fixture.rotatedRootKeys.take(2)), oldRoot, NOW) }
        assertRejected {
            val removal = fixture.rotatedRoot(revokedKeys = emptySet(), revokedDigests = emptySet())
            verifier.root(removal.envelope(fixture.initialRootKeys.take(2) + fixture.rotatedRootKeys.take(2)), oldRoot, NOW)
        }
    }

    @Test
    fun `signed index is canonical threshold verified and binds package metadata and publisher scope`() {
        val fixture = TrustFixture()
        val rootDoc = fixture.initialRoot()
        val verifier = fixture.verifier(rootDoc)
        val root = verifier.root(rootDoc.envelope(fixture.initialRootKeys.take(2)), null, NOW)
        val indexDoc = fixture.index(root.version, sequence = 7)
        val signedIndex = verifier.index(indexDoc.envelope(listOf(fixture.initialIndexKey)), root, null, NOW)
        val packageEntry = signedIndex.packages.single()
        val publisher = verifier.publisher(root, packageEntry, NOW)

        assertEquals(7L, signedIndex.sequence)
        assertEquals(indexDoc.digest, signedIndex.digest)
        assertEquals("demo.extension", packageEntry.binding.extensionId)
        assertEquals("https://cdn.example/packages/demo-1.arex", packageEntry.url)
        assertEquals(1234L, packageEntry.binding.archiveBytes)
        assertEquals(ARCHIVE_DIGEST, packageEntry.binding.archiveSha256)
        assertEquals(MANIFEST_DIGEST, packageEntry.binding.canonicalManifestSha256)
        assertEquals("publisher-key", publisher.publisherId)
        assertEquals(setOf(SourceRole.CALENDAR), publisher.allowedRoles)
        assertEquals(setOf("aniworld.to"), publisher.allowedHosts)
        assertFalse(packageEntry.revoked)
    }

    @Test
    fun `index threshold signature and sequence high water reject tampering replay and downgrade`() {
        val fixture = TrustFixture()
        val rootDoc = fixture.initialRoot()
        val verifier = fixture.verifier(rootDoc)
        val root = verifier.root(rootDoc.envelope(fixture.initialRootKeys.take(2)), null, NOW)
        val firstDoc = fixture.index(root.version, sequence = 7)
        val first = verifier.index(firstDoc.envelope(listOf(fixture.initialIndexKey)), root, null, NOW)

        assertRejected { verifier.index(firstDoc.envelope(emptyList()), root, null, NOW) }
        assertRejected { verifier.index(firstDoc.envelope(listOf(fixture.initialIndexKey), corruptFirstSignature = true), root, null, NOW) }
        assertEquals(first.sequence, verifier.index(firstDoc.envelope(listOf(fixture.initialIndexKey)), root, first, NOW).sequence)
        assertRejected {
            val replayWithDifferentContent = fixture.index(root.version, sequence = first.sequence, displayName = "Changed")
            verifier.index(replayWithDifferentContent.envelope(listOf(fixture.initialIndexKey)), root, first, NOW)
        }
        assertRejected {
            val lower = fixture.index(root.version, sequence = first.sequence - 1)
            verifier.index(lower.envelope(listOf(fixture.initialIndexKey)), root, first, NOW)
        }
        val newerDoc = fixture.index(root.version, sequence = first.sequence + 1)
        assertEquals(8L, verifier.index(newerDoc.envelope(listOf(fixture.initialIndexKey)), root, first, NOW).sequence)
    }

    @Test
    fun `index enforces package origin scope binding and yank and revocation semantics`() {
        val fixture = TrustFixture()
        val rootDoc = fixture.initialRoot()
        val verifier = fixture.verifier(rootDoc)
        val root = verifier.root(rootDoc.envelope(fixture.initialRootKeys.take(2)), null, NOW)

        assertRejected {
            val unsignedOrigin = fixture.index(root.version, 1, packageUrl = "https://evil.example/demo.arex")
            verifier.index(unsignedOrigin.envelope(listOf(fixture.initialIndexKey)), root, null, NOW)
        }
        assertRejected {
            val wrongPublisher = fixture.index(root.version, 1, publisherId = "unknown-publisher")
            verifier.index(wrongPublisher.envelope(listOf(fixture.initialIndexKey)), root, null, NOW)
        }

        val yanked = verifier.index(
            fixture.index(root.version, 1, yanked = true).envelope(listOf(fixture.initialIndexKey)), root, null, NOW,
        ).packages.single()
        assertRejected { verifier.publisher(root, yanked, NOW) }

        val revokedByEntry = verifier.index(
            fixture.index(root.version, 1, revoked = true).envelope(listOf(fixture.initialIndexKey)), root, null, NOW,
        ).packages.single()
        assertTrue(revokedByEntry.revoked)
        assertRejected { verifier.publisher(root, revokedByEntry, NOW) }

        val revokedByRoot = fixture.rotatedRoot(
            revokedKeys = setOf(fixture.orphanKey.id),
            revokedDigests = setOf(REVOKED_DIGEST, ARCHIVE_DIGEST),
        )
        val rotatedRoot = verifier.root(
            revokedByRoot.envelope(fixture.initialRootKeys.take(2) + fixture.rotatedRootKeys.take(2)),
            root,
            NOW,
        )
        val packageFromRotatedIndex = verifier.index(
            fixture.index(rotatedRoot.version, 1).envelope(listOf(fixture.rotatedIndexKey)), rotatedRoot, null, NOW,
        ).packages.single()
        assertTrue(packageFromRotatedIndex.revoked)
        assertRejected { verifier.publisher(rotatedRoot, packageFromRotatedIndex, NOW) }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected trust verification to reject input")
    }

    private class TrustFixture {
        val initialRootKeys = listOf(key(1), key(2), key(3))
        val initialIndexKey = key(4)
        val publisherKey = key(5)
        val orphanKey = key(6)
        val rotatedRootKeys = listOf(key(11), key(12), key(13))
        val rotatedIndexKey = key(14)

        fun initialRoot(): TrustDocument = root(
            version = 1,
            rootKeys = initialRootKeys,
            indexKey = initialIndexKey,
            extraKeys = listOf(orphanKey),
            revokedKeys = setOf(orphanKey.id),
            revokedDigests = setOf(REVOKED_DIGEST),
        )

        fun rotatedRoot(
            revokedKeys: Set<String> = setOf(orphanKey.id),
            revokedDigests: Set<String> = setOf(REVOKED_DIGEST),
        ): TrustDocument = root(
            version = 2,
            rootKeys = rotatedRootKeys,
            indexKey = rotatedIndexKey,
            extraKeys = listOf(orphanKey),
            revokedKeys = revokedKeys,
            revokedDigests = revokedDigests,
        )

        fun verifier(initial: TrustDocument) = ExtensionTrustVerifier(
            AppTrustPin(REPOSITORY_ID, initial.digest, setOf("https://cdn.example")),
        )

        fun index(
            rootVersion: Long,
            sequence: Long,
            displayName: String = "Demo",
            packageUrl: String = "https://cdn.example/packages/demo-1.arex",
            publisherId: String = "publisher-key",
            yanked: Boolean = false,
            revoked: Boolean = false,
        ): TrustDocument {
            val entry = jsonObject(
                "extensionId" to string("demo.extension"),
                "providerId" to string("demo"),
                "displayName" to string(displayName),
                "navigationCapabilities" to JsonArray(emptyList()),
                "publisherId" to string(publisherId),
                "keyId" to string(publisherKey.id),
                "version" to string("1.0.0"),
                "releaseSequence" to number(1),
                "hostApiMin" to number(1),
                "hostApiMax" to number(1),
                "packageUrl" to string(packageUrl),
                "archiveSha256" to string(ARCHIVE_DIGEST),
                "archiveBytes" to number(1234),
                "manifestSha256" to string(MANIFEST_DIGEST),
                "yanked" to JsonPrimitive(yanked),
                "revoked" to JsonPrimitive(revoked),
            )
            val signed = jsonObject(
                "schemaVersion" to number(1),
                "repositoryId" to string(REPOSITORY_ID),
                "rootVersion" to number(rootVersion),
                "sequence" to number(sequence),
                "issuedAt" to string(NOW.minusSeconds(60).toString()),
                "expiresAt" to string(NOW.plusSeconds(3600).toString()),
                "entries" to JsonArray(listOf(entry)),
            )
            return TrustDocument(signed)
        }

        private fun root(
            version: Long,
            rootKeys: List<SigningKey>,
            indexKey: SigningKey,
            extraKeys: List<SigningKey>,
            revokedKeys: Set<String>,
            revokedDigests: Set<String>,
        ): TrustDocument {
            val allKeys = rootKeys + indexKey + publisherKey + extraKeys
            val signed = jsonObject(
                "schemaVersion" to number(1),
                "repositoryId" to string(REPOSITORY_ID),
                "version" to number(version),
                "expiresAt" to string(NOW.plusSeconds(86400).toString()),
                "keys" to JsonArray(allKeys.map { key ->
                    jsonObject("keyId" to string(key.id), "publicKey" to string(Base64.getEncoder().encodeToString(key.public)))
                }),
                "roles" to jsonObject(
                    "root" to jsonObject("threshold" to number(2), "keyIds" to JsonArray(rootKeys.map { string(it.id) })),
                    "index" to jsonObject("threshold" to number(1), "keyIds" to JsonArray(listOf(string(indexKey.id)))),
                ),
                "publishers" to JsonArray(listOf(jsonObject(
                    "publisherId" to string("publisher-key"),
                    "extensionId" to string("demo.extension"),
                    "providerId" to string("demo"),
                    "keyId" to string(publisherKey.id),
                    "roles" to JsonArray(listOf(string("CALENDAR"))),
                    "navigation" to JsonArray(emptyList()),
                    "hosts" to JsonArray(listOf(string("aniworld.to"))),
                    "notBefore" to string(NOW.minusSeconds(3600).toString()),
                    "expiresAt" to string(NOW.plusSeconds(86400).toString()),
                ))),
                "revokedKeys" to JsonArray(revokedKeys.sorted().map(::string)),
                "revokedDigests" to JsonArray(revokedDigests.sorted().map(::string)),
            )
            return TrustDocument(signed)
        }
    }

    private data class TrustDocument(val signed: JsonObject) {
        val digest: String get() = sha(canonical(signed))

        fun envelope(signers: List<SigningKey>, corruptFirstSignature: Boolean = false): ByteArray {
            val domain = if (signed.containsKey("version")) "AREX-ROOT-V1\n" else "AREX-INDEX-V1\n"
            val message = domain.toByteArray(StandardCharsets.UTF_8) + canonical(signed)
            val signatures = signers.mapIndexed { index, key ->
                val signer = Ed25519Signer()
                signer.init(true, key.privateKey)
                signer.update(message, 0, message.size)
                val signature = signer.generateSignature().also { bytes ->
                    if (corruptFirstSignature && index == 0) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                }
                jsonObject(
                    "algorithm" to string("Ed25519"),
                    "keyId" to string(key.id),
                    "signature" to string(Base64.getEncoder().encodeToString(signature)),
                )
            }
            return jsonObject(
                "signed" to signed,
                "signatures" to JsonArray(signatures),
            ).toString().toByteArray(StandardCharsets.UTF_8)
        }
    }

    private data class SigningKey(val privateKey: Ed25519PrivateKeyParameters) {
        val public: ByteArray = privateKey.generatePublicKey().encoded
        val id: String = sha(public)
    }

    companion object {
        private const val REPOSITORY_ID = "test-repository"
        private val ARCHIVE_DIGEST = "a".repeat(64)
        private val MANIFEST_DIGEST = "b".repeat(64)
        private val REVOKED_DIGEST = "c".repeat(64)
        private val NOW = Instant.parse("2026-09-28T12:00:00Z")

        private fun key(seed: Int): SigningKey = SigningKey(Ed25519PrivateKeyParameters(ByteArray(32) { (seed + it).toByte() }, 0))
        private fun canonical(value: JsonObject) = JsonCanonicalizer(value.toString()).encodedString.toByteArray(StandardCharsets.UTF_8)
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun jsonObject(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(fields.toMap())
        private fun string(value: String) = JsonPrimitive(value)
        private fun number(value: Long) = JsonPrimitive(value)
        private fun number(value: Int) = JsonPrimitive(value)
    }
}

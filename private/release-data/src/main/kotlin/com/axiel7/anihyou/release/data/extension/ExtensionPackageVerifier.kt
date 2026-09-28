package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Enumeration
import java.util.Locale
import java.util.zip.CRC32
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.erdtman.jcs.JsonCanonicalizer

/** Catalog claims become usable only after a signed index verifier has produced this binding. */
internal data class VerifiedCatalogPackageBinding(
    val extensionId: String,
    val providerId: String,
    val publisherId: String,
    val keyId: String,
    val version: String,
    val releaseSequence: Long,
    val archiveSha256: String,
    val archiveBytes: Long,
    val canonicalManifestSha256: String,
    val yanked: Boolean,
)

/** This authorization must be resolved from the app-pinned, already validated trust root. */
internal class AuthorizedExtensionPublisherKey(
    val keyId: String,
    val publisherId: String,
    val extensionId: String,
    val providerId: String,
    publicKey: ByteArray,
    val trustRootVersion: Long,
    allowedRoles: Set<SourceRole>,
    allowedHosts: Set<String>,
    val notBefore: Instant,
    val expiresAt: Instant,
    val revoked: Boolean,
) {
    private val keyContent = publicKey.copyOf()
    val publicKey: ByteArray get() = keyContent.copyOf()
    val allowedRoles: Set<SourceRole> = allowedRoles.toSet()
    val allowedHosts: Set<String> = allowedHosts.toSet()
}

/** The production implementation must inspect Wasm features/imports/exports before activation. */
internal fun interface WasmCoreModuleProfileVerifier {
    fun verify(moduleBytes: ByteArray)
}

internal enum class ExtensionPackageFailure {
    ARCHIVE_INVALID,
    ARCHIVE_TOO_LARGE,
    DIGEST_MISMATCH,
    INVALID_MANIFEST,
    INVALID_SIGNATURE,
    KEY_UNAUTHORIZED,
    MODULE_PROFILE_REJECTED,
    PACKAGE_REVOKED,
    PACKAGE_YANKED,
    SIZE_LIMIT,
    UNSUPPORTED_ABI,
    UNTRUSTED_BINDING,
}

internal class ExtensionPackageVerificationException(
    val failure: ExtensionPackageFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Verifies the bounded `.arex` container, canonical package manifest, scoped publisher
 * signature and content digests. It deliberately requires verified catalog/trust inputs
 * and a real Wasm profile validator; there is no unsigned/debug bypass.
 */
internal class ExtensionPackageVerifier(
    private val wasmProfileVerifier: WasmCoreModuleProfileVerifier,
) {
    fun verify(
        stagedArchive: File,
        catalog: VerifiedCatalogPackageBinding,
        publisherKey: AuthorizedExtensionPublisherKey,
        hostAllowedRoles: Set<SourceRole>,
        hostAllowedHosts: Set<String>,
        policyVersion: Int,
        runtimeVersion: String,
        now: Instant,
    ): VerifiedExtensionPackage {
        validateBinding(catalog)
        if (catalog.yanked) fail(ExtensionPackageFailure.PACKAGE_YANKED, "catalog entry is yanked")
        if (publisherKey.revoked) fail(ExtensionPackageFailure.PACKAGE_REVOKED, "publisher key is revoked")
        if (publisherKey.trustRootVersion <= 0L || now.isBefore(publisherKey.notBefore) || !now.isBefore(publisherKey.expiresAt)) {
            fail(ExtensionPackageFailure.KEY_UNAUTHORIZED, "publisher key is outside its authorized interval")
        }
        if (policyVersion <= 0 || runtimeVersion.isBlank()) {
            fail(ExtensionPackageFailure.UNTRUSTED_BINDING, "host runtime policy is incomplete")
        }
        if (!stagedArchive.isFile) fail(ExtensionPackageFailure.ARCHIVE_INVALID, "staged package is not a regular file")
        val archiveSize = stagedArchive.length()
        if (archiveSize !in MIN_ARCHIVE_BYTES.toLong()..MAX_ARCHIVE_BYTES.toLong()) {
            fail(ExtensionPackageFailure.ARCHIVE_TOO_LARGE, "archive size is outside the accepted range")
        }
        if (archiveSize != catalog.archiveBytes) {
            fail(ExtensionPackageFailure.DIGEST_MISMATCH, "archive size differs from the authenticated catalog")
        }
        val archiveDigest = sha256(stagedArchive)
        if (archiveDigest != catalog.archiveSha256) {
            fail(ExtensionPackageFailure.DIGEST_MISMATCH, "archive digest differs from the authenticated catalog")
        }

        val contents = readArchive(stagedArchive)
        if (stagedArchive.length() != archiveSize || sha256(stagedArchive) != archiveDigest) {
            fail(ExtensionPackageFailure.DIGEST_MISMATCH, "staged package changed during verification")
        }
        val manifestBytes = contents.getValue(MANIFEST)
        val moduleBytes = contents.getValue(MODULE)
        val provenanceBytes = contents.getValue(PROVENANCE)
        val noticeBytes = contents.getValue(NOTICE)
        val signatureBytes = contents.getValue(SIGNATURE)

        val manifest = parseManifest(manifestBytes)
        if (manifest.canonicalDigest != catalog.canonicalManifestSha256) {
            fail(ExtensionPackageFailure.DIGEST_MISMATCH, "canonical manifest digest differs from the authenticated catalog")
        }
        if (manifest.extensionId.value != catalog.extensionId ||
            manifest.providerId.value != catalog.providerId ||
            manifest.publisherId != catalog.publisherId ||
            manifest.keyId != catalog.keyId ||
            manifest.version != catalog.version ||
            manifest.releaseSequence != catalog.releaseSequence
        ) {
            fail(ExtensionPackageFailure.UNTRUSTED_BINDING, "manifest identity differs from the authenticated catalog")
        }
        validatePublisherScope(manifest, publisherKey, now)
        verifySignature(signatureBytes, manifest, publisherKey)
        verifyContentDigest("module", moduleBytes, manifest.digests.module)
        verifyContentDigest("provenance", provenanceBytes, manifest.digests.provenance)
        verifyContentDigest("NOTICE", noticeBytes, manifest.digests.notice)
        verifyProvenance(provenanceBytes, manifest)

        val grantedRoles = manifest.capabilities.intersect(publisherKey.allowedRoles).intersect(hostAllowedRoles)
        val grantedHosts = manifest.allowedHosts.intersect(publisherKey.allowedHosts).intersect(hostAllowedHosts)
        if (grantedRoles.isEmpty() || grantedHosts.isEmpty()) {
            fail(ExtensionPackageFailure.KEY_UNAUTHORIZED, "package has no host-approved role or destination")
        }
        try {
            wasmProfileVerifier.verify(moduleBytes.copyOf())
        } catch (error: Exception) {
            throw ExtensionPackageVerificationException(
                ExtensionPackageFailure.MODULE_PROFILE_REJECTED,
                "Wasm module does not match the frozen core-module profile",
                error,
            )
        }

        return VerifiedExtensionPackage(
            extensionId = manifest.extensionId,
            providerId = manifest.providerId,
            publisherId = manifest.publisherId,
            signingKeyId = manifest.keyId,
            trustRootVersion = publisherKey.trustRootVersion,
            releaseSequence = manifest.releaseSequence,
            abiVersion = ABI_VERSION,
            policyVersion = policyVersion,
            packageDigest = archiveDigest,
            manifestDigest = manifest.canonicalDigest,
            moduleDigest = manifest.digests.module.sha256,
            moduleBytes = moduleBytes,
            grantedRoles = grantedRoles,
            grantedHosts = grantedHosts,
            runtimeVersion = runtimeVersion,
        )
    }

    private fun readArchive(file: File): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val spans = ArrayList<ArchiveSpan>(EXPECTED_ENTRIES.size)
        var totalBytes = 0L
        try {
            ZipFile(file).use { zip ->
                val entries: Enumeration<ZipArchiveEntry> = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (result.size >= EXPECTED_ENTRIES.size) {
                        fail(ExtensionPackageFailure.ARCHIVE_INVALID, "archive contains too many entries")
                    }
                    val name = entry.name
                    if (name !in EXPECTED_ENTRIES || '/' in name || '\\' in name ||
                        entry.isDirectory || entry.isUnixSymlink ||
                        entry.generalPurposeBit.usesEncryption() ||
                        entry.method !in setOf(ZipMethod.STORED.code, ZipMethod.DEFLATED.code) ||
                        !zip.canReadEntryData(entry)
                    ) {
                        fail(ExtensionPackageFailure.ARCHIVE_INVALID, "archive entry violates the frozen package layout")
                    }
                    if (result.containsKey(name)) {
                        fail(ExtensionPackageFailure.ARCHIVE_INVALID, "archive contains a duplicate entry")
                    }
                    val perEntryLimit = ENTRY_LIMITS.getValue(name)
                    if (entry.size < 0L || entry.compressedSize < 0L || entry.size > perEntryLimit) {
                        fail(ExtensionPackageFailure.SIZE_LIMIT, "archive entry exceeds its declared bound")
                    }
                    totalBytes += entry.size
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        fail(ExtensionPackageFailure.SIZE_LIMIT, "archive exceeds the total uncompressed bound")
                    }
                    val headerOffset = entry.localHeaderOffset
                    val dataOffset = entry.dataOffset
                    val dataEnd = checkedAdd(dataOffset, entry.compressedSize)
                    if (headerOffset < 0L || dataOffset <= headerOffset || dataEnd > file.length()) {
                        fail(ExtensionPackageFailure.ARCHIVE_INVALID, "archive entry offsets are inconsistent")
                    }
                    spans += ArchiveSpan(headerOffset, dataEnd)
                    result[name] = readEntry(zip, entry, perEntryLimit)
                }
            }
        } catch (failure: ExtensionPackageVerificationException) {
            throw failure
        } catch (error: Exception) {
            throw ExtensionPackageVerificationException(
                ExtensionPackageFailure.ARCHIVE_INVALID,
                "package archive is malformed or unreadable",
                error,
            )
        }
        if (result.keys != EXPECTED_ENTRIES) {
            fail(ExtensionPackageFailure.ARCHIVE_INVALID, "archive does not contain the exact required root entries")
        }
        val orderedSpans = spans.sortedBy(ArchiveSpan::start)
        for (index in 1 until orderedSpans.size) {
            if (orderedSpans[index - 1].endExclusive > orderedSpans[index].start) {
                fail(ExtensionPackageFailure.ARCHIVE_INVALID, "archive entries overlap")
            }
        }
        return result
    }

    private fun readEntry(zip: ZipFile, entry: ZipArchiveEntry, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(entry.size.toInt())
        val crc = CRC32()
        val buffer = ByteArray(8192)
        var count = 0L
        zip.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                if (count > limit || count > entry.size) {
                    fail(ExtensionPackageFailure.SIZE_LIMIT, "decompressed entry exceeds its bound")
                }
                crc.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        if (count != entry.size || crc.value != entry.crc) {
            fail(ExtensionPackageFailure.ARCHIVE_INVALID, "entry size or CRC does not match its directory record")
        }
        return out.toByteArray()
    }

    private fun parseManifest(bytes: ByteArray): ParsedManifest {
        val root = strictObject(bytes, MAX_MANIFEST_BYTES, "manifest")
        val fields = root.exactFields(
            setOf(
                "schemaVersion", "extensionId", "providerId", "version", "releaseSequence",
                "hostApiMin", "hostApiMax", "capabilities", "allowedHosts", "digests",
                "publisherId", "keyId", "sourceRepository", "sourceCommit", "build",
            ),
            "manifest",
        )
        if (fields.int("schemaVersion") != 1) fail(ExtensionPackageFailure.UNSUPPORTED_ABI, "manifest schema is unsupported")
        val extensionId = parseExtensionId(fields.string("extensionId", 128))
        val providerId = parseProviderId(fields.string("providerId", 128))
        val version = fields.string("version", 128)
        if (!SEMVER.matches(version)) fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest version is not SemVer")
        val sequence = fields.safeLong("releaseSequence")
        if (sequence <= 0L) fail(ExtensionPackageFailure.INVALID_MANIFEST, "release sequence must be positive")
        val apiMin = fields.int("hostApiMin")
        val apiMax = fields.int("hostApiMax")
        if (apiMin != ABI_VERSION || apiMax != ABI_VERSION) {
            fail(ExtensionPackageFailure.UNSUPPORTED_ABI, "manifest host API range is unsupported")
        }
        val roles = fields.array("capabilities", SourceRole.entries.size).map { item ->
            val name = item.string("capability", 64)
            SourceRole.entries.firstOrNull { it.name == name }
                ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest has an unknown source role")
        }.toSet()
        if (roles.isEmpty() || roles.size != fields.array("capabilities", SourceRole.entries.size).size) {
            fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest capabilities must be nonempty and unique")
        }
        val hosts = fields.array("allowedHosts", MAX_HOSTS).map { item ->
            val host = item.string("allowedHost", 253)
            if (!DNS_NAME.matches(host) || host != host.lowercase(Locale.ROOT) || looksLikeIpLiteral(host)) {
                fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest contains a noncanonical host grant")
            }
            host
        }.toSet()
        if (hosts.isEmpty() || hosts.size != fields.array("allowedHosts", MAX_HOSTS).size) {
            fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest hosts must be nonempty, exact and unique")
        }
        val digests = parseDigests(fields.getValue("digests"))
        val publisherId = fields.string("publisherId", 128)
        val keyId = fields.string("keyId", 128)
        if (!ID.matches(publisherId) || !ID.matches(keyId)) {
            fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest publisher or key identifier is invalid")
        }
        val sourceRepository = fields.string("sourceRepository", MAX_REPOSITORY_BYTES)
        validateRepositoryUrl(sourceRepository)
        val sourceCommit = fields.string("sourceCommit", 64)
        if (!GIT_COMMIT.matches(sourceCommit)) fail(ExtensionPackageFailure.INVALID_MANIFEST, "manifest source commit is not full length")
        val build = fields.getValue("build").obj("build").exactFields(
            setOf("toolchainVersion", "target", "lockfileDigest", "workflowIdentity"), "build",
        )
        build.string("toolchainVersion", 128).nonBlank("build toolchain")
        build.string("target", 128).nonBlank("build target")
        val lockfileDigest = build.string("lockfileDigest", 64)
        requireSha256(lockfileDigest, "build lockfile digest")
        val workflowIdentity = build.string("workflowIdentity", MAX_WORKFLOW_BYTES).nonBlank("build workflow identity")
        val canonicalManifestBytes = canonicalBytes(root)
        return ParsedManifest(
            extensionId = extensionId,
            providerId = providerId,
            version = version,
            releaseSequence = sequence,
            capabilities = roles,
            allowedHosts = hosts,
            digests = digests,
            publisherId = publisherId,
            keyId = keyId,
            sourceRepository = sourceRepository,
            sourceCommit = sourceCommit,
            lockfileDigest = lockfileDigest,
            workflowIdentity = workflowIdentity,
            canonicalDigest = sha256(canonicalManifestBytes),
            canonicalManifestBytes = canonicalManifestBytes,
        )
    }

    private fun parseDigests(element: JsonElement): ContentDigests {
        val fields = element.obj("digests").exactFields(setOf("module", "provenance", "notice"), "digests")
        fun read(name: String): ContentDigest {
            val value = fields.getValue(name).obj("digests.$name")
                .exactFields(setOf("sha256", "bytes"), "digests.$name")
            val digest = value.string("sha256", 64)
            requireSha256(digest, "digests.$name.sha256")
            val byteCount = value.safeLong("bytes")
            val max = when (name) {
                "module" -> MAX_MODULE_BYTES.toLong()
                "provenance" -> MAX_PROVENANCE_BYTES.toLong()
                else -> MAX_NOTICE_BYTES.toLong()
            }
            if (byteCount !in 1..max) fail(ExtensionPackageFailure.SIZE_LIMIT, "manifest content size is outside its bound")
            return ContentDigest(digest, byteCount)
        }
        return ContentDigests(read("module"), read("provenance"), read("notice"))
    }

    private fun verifyProvenance(bytes: ByteArray, manifest: ParsedManifest) {
        val fields = strictObject(bytes, MAX_PROVENANCE_BYTES, "provenance").exactFields(
            setOf(
                "schemaVersion", "sourceRepository", "sourceCommit", "licenseSpdx", "components",
                "localModifications", "compilerVersion", "sdkVersion", "dependencyLockDigest",
                "reproducibleBuildCommand", "workflowIdentity", "moduleDigest",
            ),
            "provenance",
        )
        if (fields.int("schemaVersion") != 1) fail(ExtensionPackageFailure.INVALID_MANIFEST, "provenance schema is unsupported")
        if (fields.string("sourceRepository", MAX_REPOSITORY_BYTES) != manifest.sourceRepository ||
            fields.string("sourceCommit", 64) != manifest.sourceCommit ||
            fields.string("dependencyLockDigest", 64) != manifest.lockfileDigest ||
            fields.string("workflowIdentity", MAX_WORKFLOW_BYTES) != manifest.workflowIdentity ||
            fields.string("moduleDigest", 64) != manifest.digests.module.sha256
        ) {
            fail(ExtensionPackageFailure.DIGEST_MISMATCH, "provenance does not match the signed manifest")
        }
        val licenses = fields.array("licenseSpdx", MAX_PROVENANCE_ITEMS)
        if (licenses.isEmpty() || licenses.any { it.string("licenseSpdx", 256).isBlank() }) {
            fail(ExtensionPackageFailure.INVALID_MANIFEST, "provenance must identify its licenses")
        }
        fields.string("compilerVersion", 128).nonBlank("provenance compiler")
        fields.string("sdkVersion", 128).nonBlank("provenance SDK")
        fields.string("reproducibleBuildCommand", MAX_BUILD_COMMAND_BYTES).nonBlank("provenance build command")
        val components = fields.array("components", MAX_PROVENANCE_ITEMS)
        components.forEachIndexed { index, component ->
            component.obj("components[$index]").exactFields(
                setOf("name", "origin", "path", "licenseSpdx"), "components[$index]",
            ).also {
                it.string("name", 256).nonBlank("component name")
                it.string("origin", MAX_REPOSITORY_BYTES).nonBlank("component origin")
                it.string("path", MAX_PATH_BYTES).nonBlank("component path")
                it.string("licenseSpdx", 256).nonBlank("component license")
            }
        }
        fields.array("localModifications", MAX_PROVENANCE_ITEMS).forEach { it.string("localModifications", MAX_PATH_BYTES) }
    }

    private fun verifySignature(
        signatureBytes: ByteArray,
        manifest: ParsedManifest,
        trustedKey: AuthorizedExtensionPublisherKey,
    ) {
        val fields = strictObject(signatureBytes, MAX_SIGNATURE_BYTES, "package signature")
            .exactFields(setOf("algorithm", "keyId", "signature"), "package signature")
        if (fields.string("algorithm", 32) != "Ed25519" || fields.string("keyId", 128) != manifest.keyId) {
            fail(ExtensionPackageFailure.INVALID_SIGNATURE, "package signature algorithm or key does not match")
        }
        val signatureText = fields.string("signature", 128)
        if (!BASE64_SIGNATURE.matches(signatureText)) fail(ExtensionPackageFailure.INVALID_SIGNATURE, "package signature encoding is invalid")
        val signature = try {
            Base64.decode(signatureText)
        } catch (error: Exception) {
            throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_SIGNATURE, "package signature encoding is invalid", error)
        }
        if (signature.size != ED25519_SIGNATURE_BYTES || Base64.toBase64String(signature) != signatureText) {
            fail(ExtensionPackageFailure.INVALID_SIGNATURE, "package signature encoding is not canonical")
        }
        val publicKey = trustedKey.publicKey
        if (publicKey.size != ED25519_PUBLIC_KEY_BYTES) fail(ExtensionPackageFailure.KEY_UNAUTHORIZED, "trusted Ed25519 key has an invalid length")
        val message = PACKAGE_SIGNATURE_DOMAIN + canonicalBytesFromDigestSource(manifest)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        if (!verifier.verifySignature(signature)) fail(ExtensionPackageFailure.INVALID_SIGNATURE, "package signature verification failed")
    }

    private fun canonicalBytesFromDigestSource(manifest: ParsedManifest): ByteArray = manifest.canonicalManifestBytes

    private fun validatePublisherScope(
        manifest: ParsedManifest,
        trustedKey: AuthorizedExtensionPublisherKey,
        now: Instant,
    ) {
        if (trustedKey.keyId != manifest.keyId || trustedKey.publisherId != manifest.publisherId ||
            trustedKey.extensionId != manifest.extensionId.value || trustedKey.providerId != manifest.providerId.value ||
            trustedKey.publicKey.size != ED25519_PUBLIC_KEY_BYTES || trustedKey.revoked ||
            trustedKey.trustRootVersion <= 0L || now.isBefore(trustedKey.notBefore) || !now.isBefore(trustedKey.expiresAt)
        ) {
            fail(ExtensionPackageFailure.KEY_UNAUTHORIZED, "signing key is not authorized for this package")
        }
    }

    private fun verifyContentDigest(name: String, bytes: ByteArray, expected: ContentDigest) {
        if (bytes.size.toLong() != expected.bytes || sha256(bytes) != expected.sha256) {
            fail(ExtensionPackageFailure.DIGEST_MISMATCH, "$name bytes do not match the signed manifest")
        }
    }

    private fun validateBinding(binding: VerifiedCatalogPackageBinding) {
        if (!SHA256.matches(binding.archiveSha256) || !SHA256.matches(binding.canonicalManifestSha256) ||
            binding.archiveBytes !in MIN_ARCHIVE_BYTES.toLong()..MAX_ARCHIVE_BYTES.toLong() ||
            binding.releaseSequence <= 0L || !SEMVER.matches(binding.version) ||
            !ID.matches(binding.publisherId) || !ID.matches(binding.keyId)
        ) {
            fail(ExtensionPackageFailure.UNTRUSTED_BINDING, "authenticated catalog binding is malformed")
        }
        parseExtensionId(binding.extensionId)
        parseProviderId(binding.providerId)
    }

    private fun strictObject(bytes: ByteArray, maximumBytes: Int, field: String): JsonObject {
        if (bytes.size > maximumBytes) fail(ExtensionPackageFailure.SIZE_LIMIT, "$field exceeds its byte bound")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_MANIFEST, "$field is not valid UTF-8", error)
        }
        val parsed = try {
            ExtensionWireCodec.parseStrictJson(bytes, maximumBytes)
        } catch (error: Exception) {
            throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_MANIFEST, "$field JSON is invalid", error)
        }
        if (!hasWellFormedSurrogates(text)) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field contains an invalid Unicode scalar")
        return parsed.obj(field)
    }

    private fun canonicalBytes(element: JsonElement): ByteArray = try {
        JsonCanonicalizer(element.toString()).encodedString.toByteArray(StandardCharsets.UTF_8)
    } catch (error: Exception) {
        throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_MANIFEST, "signed JSON cannot be canonicalized as RFC 8785 JCS", error)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    private fun validateRepositoryUrl(value: String) {
        val uri = try { URI(value) } catch (error: Exception) {
            throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_MANIFEST, "source repository URL is invalid", error)
        }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.path.isNullOrBlank() || uri.path.split('/').count { it.isNotEmpty() } !in 2..3
        ) {
            fail(ExtensionPackageFailure.INVALID_MANIFEST, "source repository URL is not a bounded HTTPS repository URL")
        }
    }

    private fun parseExtensionId(value: String): ExtensionId = try { ExtensionId.parse(value) } catch (error: Exception) {
        throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_MANIFEST, "manifest extension identifier is invalid", error)
    }

    private fun parseProviderId(value: String): ProviderId = try { ProviderId.parse(value) } catch (error: Exception) {
        throw ExtensionPackageVerificationException(ExtensionPackageFailure.INVALID_MANIFEST, "manifest provider identifier is invalid", error)
    }

    private fun checkedAdd(left: Long, right: Long): Long = try { Math.addExact(left, right) } catch (error: ArithmeticException) {
        throw ExtensionPackageVerificationException(ExtensionPackageFailure.ARCHIVE_INVALID, "archive offset overflow", error)
    }

    private fun hasWellFormedSurrogates(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(char) -> return false
                else -> index++
            }
        }
        return true
    }

    private fun looksLikeIpLiteral(host: String): Boolean = ':' in host || host.all { it.isDigit() || it == '.' }

    private fun String.nonBlank(field: String): String = also {
        if (isBlank()) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field is blank")
    }

    private fun fail(failure: ExtensionPackageFailure, message: String): Nothing =
        throw ExtensionPackageVerificationException(failure, message)

    private data class ContentDigest(val sha256: String, val bytes: Long)
    private data class ContentDigests(val module: ContentDigest, val provenance: ContentDigest, val notice: ContentDigest)
    private data class ArchiveSpan(val start: Long, val endExclusive: Long)

    private data class ParsedManifest(
        val extensionId: ExtensionId,
        val providerId: ProviderId,
        val version: String,
        val releaseSequence: Long,
        val capabilities: Set<SourceRole>,
        val allowedHosts: Set<String>,
        val digests: ContentDigests,
        val publisherId: String,
        val keyId: String,
        val sourceRepository: String,
        val sourceCommit: String,
        val lockfileDigest: String,
        val workflowIdentity: String,
        val canonicalDigest: String,
        val canonicalManifestBytes: ByteArray,
    )

    private fun JsonElement.obj(field: String): JsonObject = this as? JsonObject
        ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field must be an object")

    private fun JsonObject.exactFields(expected: Set<String>, field: String): JsonObject {
        if (keys != expected) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field has missing or unknown fields")
        return this
    }

    private fun JsonObject.string(field: String, maxBytes: Int): String {
        val primitive = this[field] as? JsonPrimitive
        val value = primitive?.takeIf { it.isString }?.content
            ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field must be a string")
        if (value.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            fail(ExtensionPackageFailure.SIZE_LIMIT, "$field exceeds its byte bound")
        }
        return value
    }

    private fun JsonObject.int(field: String): Int {
        val raw = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (raw == null || !INTEGER.matches(raw)) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field must be an integer")
        val value = raw.toLongOrNull() ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field is outside the safe integer range")
        if (value !in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER || value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field is outside the safe integer range")
        }
        return value.toInt()
    }

    private fun JsonObject.safeLong(field: String): Long {
        val raw = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (raw == null || !POSITIVE_INTEGER.matches(raw)) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field must be a nonnegative integer")
        val value = raw.toLongOrNull() ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field is outside the safe integer range")
        if (value > MAX_SAFE_INTEGER) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field is outside the safe integer range")
        return value
    }

    private fun JsonObject.array(field: String, maximum: Int): JsonArray {
        val array = this[field] as? JsonArray ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field must be an array")
        if (array.size > maximum) fail(ExtensionPackageFailure.SIZE_LIMIT, "$field has too many entries")
        return array
    }

    private fun JsonElement.string(field: String, maxBytes: Int): String {
        val primitive = this as? JsonPrimitive
        val value = primitive?.takeIf { it.isString }?.content
            ?: fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field must be a string")
        if (value.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            fail(ExtensionPackageFailure.SIZE_LIMIT, "$field exceeds its byte bound")
        }
        return value
    }

    private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun requireSha256(value: String, field: String) {
        if (!SHA256.matches(value)) fail(ExtensionPackageFailure.INVALID_MANIFEST, "$field is not lowercase SHA-256")
    }

    private companion object {
        const val ABI_VERSION = 1
        const val MIN_ARCHIVE_BYTES = 1
        const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 12L * 1024 * 1024
        const val MAX_MODULE_BYTES = 8 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_PROVENANCE_BYTES = 256 * 1024
        const val MAX_NOTICE_BYTES = 256 * 1024
        const val MAX_SIGNATURE_BYTES = 1024
        const val MAX_HOSTS = 128
        const val MAX_PROVENANCE_ITEMS = 256
        const val MAX_REPOSITORY_BYTES = 2048
        const val MAX_WORKFLOW_BYTES = 512
        const val MAX_BUILD_COMMAND_BYTES = 4096
        const val MAX_PATH_BYTES = 1024
        const val ED25519_PUBLIC_KEY_BYTES = 32
        const val ED25519_SIGNATURE_BYTES = 64
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        const val MANIFEST = "manifest.json"
        const val MODULE = "module.wasm"
        const val PROVENANCE = "provenance.json"
        const val NOTICE = "NOTICE"
        const val SIGNATURE = "package.sig"
        val EXPECTED_ENTRIES = setOf(MANIFEST, MODULE, PROVENANCE, NOTICE, SIGNATURE)
        val ENTRY_LIMITS = mapOf(
            MANIFEST to MAX_MANIFEST_BYTES,
            MODULE to MAX_MODULE_BYTES,
            PROVENANCE to MAX_PROVENANCE_BYTES,
            NOTICE to MAX_NOTICE_BYTES,
            SIGNATURE to MAX_SIGNATURE_BYTES,
        )
        val SHA256 = Regex("[0-9a-f]{64}")
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
        val POSITIVE_INTEGER = Regex("0|[1-9][0-9]*")
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val GIT_COMMIT = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        val SEMVER = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        val DNS_NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
        val BASE64_SIGNATURE = Regex("[A-Za-z0-9+/]{86}==")
        val PACKAGE_SIGNATURE_DOMAIN = "AREX-PACKAGE-V1\n".toByteArray(StandardCharsets.UTF_8)
    }
}

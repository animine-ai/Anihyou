package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.erdtman.jcs.JsonCanonicalizer
import java.util.Base64

/** The pin is shipped with the app. A remote repository cannot supply or replace it. */
internal data class AppTrustPin(val repositoryId: String, val initialRootSha256: String, val distributionOrigins: Set<String>) {
    init {
        require(initialRootSha256.matches(Regex("[0-9a-f]{64}")))
        require(repositoryId.matches(Regex("[a-zA-Z0-9._-]{1,128}")))
        require(distributionOrigins.isNotEmpty() && distributionOrigins.all { origin ->
            val uri = URI(origin)
            uri.scheme == "https" && uri.host != null && uri.rawAuthority == uri.host &&
                (uri.path.isEmpty() || uri.path == "/") && uri.rawQuery == null && uri.rawFragment == null
        })
    }
}

internal data class TrustKey(val id: String, val publicKey: ByteArray) {
    fun matches(other: TrustKey) = id == other.id && publicKey.contentEquals(other.publicKey)
}

internal data class TrustRole(val threshold: Int, val keyIds: Set<String>)
internal data class PublisherScope(
    val publisherId: String, val extensionId: String, val providerId: String,
    val keyId: String, val roles: Set<SourceRole>, val navigation: Set<NavigationCapability>,
    val hosts: Set<String>, val notBefore: Instant, val expiresAt: Instant,
)
internal data class TrustedRoot(
    val version: Long, val expiresAt: Instant, val repositoryId: String,
    val keys: Map<String, TrustKey>, val root: TrustRole, val index: TrustRole,
    val publishers: List<PublisherScope>, val revokedKeys: Set<String>, val revokedDigests: Set<String>,
    val digest: String,
)
internal data class TrustedIndex(
    val sequence: Long, val digest: String, val issuedAt: Instant, val expiresAt: Instant,
    val rootVersion: Long, val packages: List<IndexedPackage>,
)
internal data class IndexedPackage(
    val binding: VerifiedCatalogPackageBinding, val url: String, val revoked: Boolean,
)

/** Strict JCS envelopes. The caller durably commits the returned high-water state before activation. */
internal class ExtensionTrustVerifier(private val pin: AppTrustPin) {
    fun root(bytes: ByteArray, previous: TrustedRoot?, now: Instant): TrustedRoot {
        val envelope = strict(bytes, 65536)
        envelope.fields("signed", "signatures")
        val signed = envelope.obj("signed")
        signed.fields("schemaVersion", "repositoryId", "version", "expiresAt", "keys", "roles", "publishers", "revokedKeys", "revokedDigests")
        require(signed.long("schemaVersion") == 1L)
        val repositoryId = signed.str("repositoryId", 128)
        require(repositoryId == pin.repositoryId)
        val version = signed.long("version")
        val expiry = signed.time("expiresAt")
        require(version > 0 && now.isBefore(expiry))
        val digest = sha(jcs(signed))
        if (previous == null) require(version == 1L && digest == pin.initialRootSha256)
        else require(version == previous.version + 1 && previous.repositoryId == repositoryId)
        val keys = signed.array("keys", 32).map { entry ->
            val o = entry as? JsonObject ?: error("key object")
            o.fields("keyId", "publicKey")
            val keyBytes = b64(o.str("publicKey", 64), 32)
            val id = sha(keyBytes)
            require(o.str("keyId", 64) == id)
            TrustKey(id, keyBytes)
        }
        require(keys.map { it.id }.toSet().size == keys.size && keys.isNotEmpty())
        val keyMap = keys.associateBy { it.id }
        val roles = signed.obj("roles")
        roles.fields("root", "index")
        fun role(name: String): TrustRole {
            val obj = roles.obj(name)
            obj.fields("threshold", "keyIds")
            val ids = obj.array("keyIds", 16).map { (it as JsonPrimitive).content }.toSet()
            val threshold = obj.long("threshold").toInt()
            require(ids.isNotEmpty() && ids.size == obj.array("keyIds", 16).size && ids.all { it in keyMap } && threshold in 1..ids.size)
            return TrustRole(threshold, ids)
        }
        val rootRole = role("root")
        val indexRole = role("index")
        if (previous == null) require(rootRole.threshold == 2 && rootRole.keyIds.size == 3)
        val revokedKeys = signed.array("revokedKeys", 32).map { (it as JsonPrimitive).content }.toSet()
        val revokedDigests = signed.array("revokedDigests", 256).map { (it as JsonPrimitive).content }.toSet()
        require(revokedDigests.all { it.matches(Regex("[0-9a-f]{64}")) })
        if (previous != null) {
            require(revokedKeys.containsAll(previous.revokedKeys) && revokedDigests.containsAll(previous.revokedDigests))
            require(previous.keys.filterKeys { it in revokedKeys }.none { (id, _) -> id in rootRole.keyIds || id in indexRole.keyIds })
        }
        val publishers = signed.array("publishers", 32).map { entry ->
            val o = entry as? JsonObject ?: error("publisher object")
            o.fields("publisherId", "extensionId", "providerId", "keyId", "roles", "navigation", "hosts", "notBefore", "expiresAt")
            val id = o.str("keyId", 64)
            require(id in keyMap && id !in revokedKeys)
            val rolesSet = o.array("roles", 4).map { SourceRole.valueOf((it as JsonPrimitive).content) }.toSet()
            val navSet = o.array("navigation", 4).map { NavigationCapability.valueOf((it as JsonPrimitive).content) }.toSet()
            val hosts = o.array("hosts", 32).map { (it as JsonPrimitive).content }.toSet()
            require(hosts.isNotEmpty() && hosts.all { it.matches(Regex("[a-z0-9.-]{1,253}")) && !it.startsWith(".") })
            val before = o.time("notBefore")
            val until = o.time("expiresAt")
            require(before.isBefore(until))
            PublisherScope(o.str("publisherId", 128), o.str("extensionId", 128), o.str("providerId", 128),
                id, rolesSet, navSet, hosts, before, until)
        }
        val signatures = signatures(envelope)
        if (previous != null) checkThreshold(previous.root, previous.keys, previous.revokedKeys, signatures, "AREX-ROOT-V1\n", signed)
        checkThreshold(rootRole, keyMap, revokedKeys, signatures, "AREX-ROOT-V1\n", signed)
        return TrustedRoot(version, expiry, repositoryId, keyMap, rootRole, indexRole, publishers, revokedKeys, revokedDigests, digest)
    }

    fun index(bytes: ByteArray, root: TrustedRoot, last: TrustedIndex?, now: Instant): TrustedIndex {
        require(now.isBefore(root.expiresAt))
        val envelope = strict(bytes, 262144)
        envelope.fields("signed", "signatures")
        val signed = envelope.obj("signed")
        signed.fields("schemaVersion", "repositoryId", "rootVersion", "sequence", "issuedAt", "expiresAt", "entries")
        require(signed.long("schemaVersion") == 1L && signed.str("repositoryId", 128) == root.repositoryId)
        val rootVersion = signed.long("rootVersion")
        require(rootVersion == root.version)
        val sequence = signed.long("sequence")
        val digest = sha(jcs(signed))
        require(sequence > 0 && (last == null || sequence > last.sequence || sequence == last.sequence && digest == last.digest))
        val issued = signed.time("issuedAt")
        val expiry = signed.time("expiresAt")
        require(!issued.isAfter(now.plusSeconds(600)) && expiry.isAfter(now) && expiry.isAfter(issued) &&
            !expiry.isAfter(issued.plusSeconds(7 * 86400)))
        checkThreshold(root.index, root.keys, root.revokedKeys, signatures(envelope), "AREX-INDEX-V1\n", signed)
        val packages = signed.array("entries", 256).map { entry ->
            val o = entry as? JsonObject ?: error("entry object")
            o.fields("extensionId", "providerId", "displayName", "navigationCapabilities", "publisherId", "keyId", "version", "releaseSequence", "hostApiMin", "hostApiMax", "packageUrl", "archiveSha256", "archiveBytes", "manifestSha256", "yanked", "revoked")
            require(o.long("hostApiMin") == 1L && o.long("hostApiMax") == 1L)
            val url = o.str("packageUrl", 2048)
            val uri = URI(url)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null &&
                uri.port == -1 && uri.host != null && !uri.host.all { it.isDigit() || it == '.' } && ':' !in uri.host &&
                "https://${uri.host}" in pin.distributionOrigins && uri.path.endsWith(".arex"))
            val publisher = o.str("publisherId", 128)
            val extension = o.str("extensionId", 128)
            val provider = o.str("providerId", 128)
            val key = o.str("keyId", 64)
            require(root.publishers.any { it.publisherId == publisher && it.extensionId == extension && it.providerId == provider && it.keyId == key && now >= it.notBefore && now < it.expiresAt })
            val archive = o.str("archiveSha256", 64)
            val manifest = o.str("manifestSha256", 64)
            require(listOf(archive, manifest).all { it.matches(Regex("[0-9a-f]{64}")) })
            val navigation = o.array("navigationCapabilities", 4).map { NavigationCapability.valueOf((it as JsonPrimitive).content) }.toSet()
            val binding = VerifiedCatalogPackageBinding(extension, provider, o.str("displayName", 256), navigation, publisher, key,
                o.str("version", 128), o.long("releaseSequence"), archive, o.long("archiveBytes"), manifest, o.bool("yanked"))
            IndexedPackage(binding, url, o.bool("revoked") || archive in root.revokedDigests || key in root.revokedKeys)
        }
        require(packages.zipWithNext().all { (a, b) ->
            a.binding.extensionId < b.binding.extensionId || a.binding.extensionId == b.binding.extensionId && a.binding.releaseSequence < b.binding.releaseSequence
        })
        return TrustedIndex(sequence, digest, issued, expiry, rootVersion, packages)
    }

    fun publisher(root: TrustedRoot, entry: IndexedPackage, now: Instant): AuthorizedExtensionPublisherKey {
        require(!entry.revoked && !entry.binding.yanked && entry.binding.archiveSha256 !in root.revokedDigests)
        val scope = root.publishers.single { it.publisherId == entry.binding.publisherId && it.extensionId == entry.binding.extensionId &&
            it.providerId == entry.binding.providerId && it.keyId == entry.binding.keyId && now >= it.notBefore && now < it.expiresAt }
        return AuthorizedExtensionPublisherKey(scope.keyId, scope.publisherId, scope.extensionId, scope.providerId,
            root.keys.getValue(scope.keyId).publicKey, root.version, scope.roles, scope.navigation, scope.hosts,
            scope.notBefore, scope.expiresAt, false)
    }

    private fun checkThreshold(role: TrustRole, keys: Map<String, TrustKey>, revoked: Set<String>, signatures: Map<String, ByteArray>, domain: String, signed: JsonObject) {
        val message = domain.toByteArray(StandardCharsets.UTF_8) + jcs(signed)
        val valid = signatures.count { (id, sig) ->
            if (id !in role.keyIds || id in revoked) false else {
                val verifier = Ed25519Signer()
                verifier.init(false, Ed25519PublicKeyParameters(keys.getValue(id).publicKey, 0))
                verifier.update(message, 0, message.size)
                verifier.verifySignature(sig)
            }
        }
        require(valid >= role.threshold) { "insufficient authorized signatures" }
    }

    private fun signatures(o: JsonObject): Map<String, ByteArray> {
        val entries = o.array("signatures", 32).map { item ->
            val obj = item as? JsonObject ?: error("signature object")
            obj.fields("algorithm", "keyId", "signature")
            require(obj.str("algorithm", 32) == "Ed25519")
            obj.str("keyId", 64) to b64(obj.str("signature", 128), 64)
        }
        require(entries.isNotEmpty() && entries.map { it.first }.toSet().size == entries.size)
        return entries.toMap()
    }
}

private fun strict(bytes: ByteArray, limit: Int): JsonObject = ExtensionWireCodec.parseStrictJson(bytes, limit) as? JsonObject ?: error("object required")
private fun jcs(o: JsonObject) = JsonCanonicalizer(o.toString()).encodedString.toByteArray(StandardCharsets.UTF_8)
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun b64(value: String, size: Int): ByteArray = Base64.getDecoder().decode(value).also {
    require(it.size == size && Base64.getEncoder().encodeToString(it) == value)
}
private fun JsonObject.fields(vararg expected: String) { require(keys == expected.toSet()) }
private fun JsonObject.obj(key: String) = getValue(key) as? JsonObject ?: error("$key object")
private fun JsonObject.array(key: String, max: Int) = (getValue(key) as? JsonArray ?: error("$key array")).also { require(it.size <= max) }
private fun JsonObject.str(key: String, max: Int) = (getValue(key) as? JsonPrimitive ?: error("$key string")).also { require(it.isString && it.content.length in 1..max) }.content
private fun JsonObject.long(key: String) = (getValue(key) as? JsonPrimitive ?: error("$key integer")).also { require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]{0,15}"))) }.content.toLong()
private fun JsonObject.bool(key: String) = (getValue(key) as? JsonPrimitive ?: error("$key boolean")).also { require(!it.isString && it.content in setOf("true", "false")) }.content.toBoolean()
private fun JsonObject.time(key: String) = Instant.parse(str(key, 40))

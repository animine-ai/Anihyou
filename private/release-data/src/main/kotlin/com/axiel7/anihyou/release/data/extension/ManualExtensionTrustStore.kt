package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.serialization.json.*

/** One source the user explicitly accepted without an independent identity check. Bound to this URL and this root. */
internal data class ManualTrustRecord(
    val url: String,
    val origin: String,
    val repositoryId: String,
    val rootSha256: String,
    val hosts: Set<String>,
    val authority: List<ManualTrustAuthority>,
    val acceptedAt: Instant,
) {
    val trustClass: String get() = TRUST_CLASS
    companion object { const val TRUST_CLASS = "MANUALLY_ACCEPTED_UNVERIFIED" }
}

/** The publisher scope of the accepted root. Narrow by construction: only what the accepted root itself declares. */
internal data class ManualTrustAuthority(
    val publisherId: String, val signingKeyId: String, val extensionId: String, val providerId: String,
    val roles: Set<String>,
)

/**
 * Private app storage for explicit first trust (temporary, private-test workaround). There is no global switch: an
 * acceptance exists per normalized source URL and per exact root digest, survives restarts, and disappears when the
 * source is removed. It never replaces any signature, expiry, revocation or package check.
 */
class ManualExtensionTrustStore(private val directory: File) {
    private val stateFile = File(directory, "manual-trust.json")
    private val lock = Any()
    private var records: List<ManualTrustRecord> = if (stateFile.exists()) decode(stateFile.readBytes()) else emptyList()

    internal fun find(url: String): ManualTrustRecord? = synchronized(lock) { records.singleOrNull { it.url == url } }
    internal fun all(): List<ManualTrustRecord> = synchronized(lock) { records.toList() }

    /** Replaces an earlier acceptance of the same URL; never touches another source. Null when the store is full. */
    internal fun accept(record: ManualTrustRecord): Boolean = synchronized(lock) {
        if (records.none { it.url == record.url } && records.size >= MAX_RECORDS) return@synchronized false
        persist(records.filterNot { it.url == record.url } + record)
        AppLog.i("trust") { "manual trust stored url=${record.url} repo=${record.repositoryId} root=${AppLog.short(record.rootSha256)} hosts=${record.hosts}" }
        true
    }

    internal fun remove(url: String) = synchronized(lock) {
        if (records.any { it.url == url }) {
            persist(records.filterNot { it.url == url })
            AppLog.i("trust") { "manual trust removed url=$url" }
        }
    }

    /**
     * The release Authority that follows from the accepted roots: exactly the publisher/key/extension/provider
     * coordinates the accepted root declares, for the roles the host supports. Nothing broader.
     */
    fun approvedAuthority(): Set<ApprovedExtensionAuthorityTuple> = synchronized(lock) {
        records.flatMap { record ->
            record.authority.mapNotNull { scope ->
                // Only the coordinate the host already supports; a root cannot make up another provider's Authority.
                if (scope.extensionId != SUPPORTED_EXTENSION || scope.providerId != SUPPORTED_PROVIDER) return@mapNotNull null
                val roles = scope.roles.mapNotNull { name -> SourceRole.entries.firstOrNull { it.name == name } }.toSet()
                if (roles.isEmpty()) null else runCatching {
                    ApprovedExtensionAuthorityTuple(scope.publisherId, scope.signingKeyId, scope.extensionId, scope.providerId, roles)
                }.getOrNull()
            }
        }.toSet()
    }

    private fun persist(next: List<ManualTrustRecord>) {
        require(directory.isDirectory || directory.mkdirs())
        val json = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "trustClass" to JsonPrimitive(ManualTrustRecord.TRUST_CLASS),
            "sources" to JsonArray(next.map { r ->
                JsonObject(mapOf("url" to JsonPrimitive(r.url), "origin" to JsonPrimitive(r.origin),
                    "repositoryId" to JsonPrimitive(r.repositoryId), "rootSha256" to JsonPrimitive(r.rootSha256),
                    "hosts" to JsonArray(r.hosts.sorted().map(::JsonPrimitive)),
                    "authority" to JsonArray(r.authority.map { a ->
                        JsonObject(mapOf("publisherId" to JsonPrimitive(a.publisherId), "signingKeyId" to JsonPrimitive(a.signingKeyId),
                            "extensionId" to JsonPrimitive(a.extensionId), "providerId" to JsonPrimitive(a.providerId),
                            "roles" to JsonArray(a.roles.sorted().map(::JsonPrimitive))))
                    }),
                    "acceptedAt" to JsonPrimitive(r.acceptedAt.toString())))
            })))
        val temporary = File(directory, "manual-trust.next")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        ExtensionFileDurability.syncDirectory(directory)
        records = next
    }

    private fun decode(bytes: ByteArray): List<ManualTrustRecord> {
        val json = ExtensionWireCodec.parseStrictJson(bytes, 256 * 1024) as JsonObject
        require(json.keys == setOf("schemaVersion", "trustClass", "sources") && json.getValue("schemaVersion").jsonPrimitive.int == 1 &&
            json.getValue("trustClass").jsonPrimitive.content == ManualTrustRecord.TRUST_CLASS)
        val entries = json.getValue("sources").jsonArray
        require(entries.size <= MAX_RECORDS)
        val parsed = entries.map { value ->
            val o = value.jsonObject
            require(o.keys == setOf("url", "origin", "repositoryId", "rootSha256", "hosts", "authority", "acceptedAt"))
            val url = o.getValue("url").jsonPrimitive.content
            val address = NormalizedExtensionSource.parse(url)
            require(address.url == url && address.origin == o.getValue("origin").jsonPrimitive.content)
            val rootSha = o.getValue("rootSha256").jsonPrimitive.content
            require(rootSha.matches(Regex("[0-9a-f]{64}")))
            ManualTrustRecord(url, address.origin, o.getValue("repositoryId").jsonPrimitive.content, rootSha,
                o.getValue("hosts").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                o.getValue("authority").jsonArray.map { item ->
                    val a = item.jsonObject
                    require(a.keys == setOf("publisherId", "signingKeyId", "extensionId", "providerId", "roles"))
                    ManualTrustAuthority(a.getValue("publisherId").jsonPrimitive.content, a.getValue("signingKeyId").jsonPrimitive.content,
                        a.getValue("extensionId").jsonPrimitive.content, a.getValue("providerId").jsonPrimitive.content,
                        a.getValue("roles").jsonArray.map { it.jsonPrimitive.content }.toSet())
                },
                Instant.parse(o.getValue("acceptedAt").jsonPrimitive.content))
        }
        require(parsed.map { it.url }.toSet().size == parsed.size)
        return parsed
    }

    private companion object {
        const val MAX_RECORDS = 64
        const val SUPPORTED_EXTENSION = "de.aniworld"
        const val SUPPORTED_PROVIDER = "aniworld"
    }
}

/**
 * The real bootstrap with two outcomes: an independently provisioned anchor, or the anchor of a source the user explicitly
 * accepted. Anything else is not authenticated. It never answers yes for an unknown source.
 */
internal class ManualTrustBootstrap(
    private val independent: ExtensionSourceTrustBootstrap,
    private val store: ManualExtensionTrustStore,
) : ExtensionSourceTrustBootstrap {
    override suspend fun authenticate(source: NormalizedExtensionSource): AuthenticatedExtensionSourceAnchor? {
        independent.authenticate(source)?.let { return it }
        val record = store.find(source.url) ?: return null
        if (record.origin != source.origin) return null
        return AuthenticatedExtensionSourceAnchor(
            AppTrustPin(record.repositoryId, record.rootSha256, setOf(record.origin)), record.hosts)
    }

    /** Adding a source is offered: either path can authenticate it. */
    override val provisioned: Boolean = true
}

package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*

internal data class NormalizedExtensionSource(val url: String, val origin: String) {
    companion object {
        fun parse(value: String): NormalizedExtensionSource {
            val text = value.trim()
            require(text.length in 1..2048 && text.all { it.code in 0x21..0x7e } && '\\' !in text)
            val uri = URI(text)
            val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
            require(uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443))
            require(host.length <= 253 && host.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")) &&
                !host.all { it.isDigit() || it == '.' })
            val path = uri.rawPath.orEmpty()
            require('%' !in path && path.split('/').none { it == "." || it == ".." } && "//" !in path)
            val origin = "https://$host"
            return NormalizedExtensionSource(origin + path.trimEnd('/'), origin)
        }
    }
}

internal data class RegisteredExtensionSource(
    val id: String, val address: NormalizedExtensionSource, val enabled: Boolean = true,
    val removed: Boolean = false, val epoch: Long = 0,
    val attemptedAt: Instant? = null, val succeededAt: Instant? = null,
    val failure: ExtensionSourceFailure? = null,
)

/** Lifecycle only. Signed trust, monotone counters and install pointers live in the install journal. */
internal class ExtensionSourceRegistry(private val directory: File) {
    private val stateFile = File(directory, "sources.json")
    private val lock = Any()
    private var records: List<RegisteredExtensionSource>
    init {
        require(directory.isDirectory || directory.mkdirs())
        records = if (stateFile.exists()) decode(stateFile.readBytes()) else emptyList()
    }

    fun all(): List<RegisteredExtensionSource> = synchronized(lock) { records.toList() }
    fun find(id: String) = synchronized(lock) { records.singleOrNull { it.id == id } }
    fun add(address: NormalizedExtensionSource): Pair<RegisteredExtensionSource, Boolean>? = synchronized(lock) {
        val previous = records.singleOrNull { it.address.url == address.url }
        if (previous != null && !previous.removed) return@synchronized previous to false
        if (previous == null && records.size >= 64) return@synchronized null
        val added = previous?.copy(enabled = true, removed = false, epoch = previous.epoch + 1)
            ?: RegisteredExtensionSource(UUID.randomUUID().toString(), address)
        persist(records.filterNot { it.id == added.id } + added)
        added to true
    }

    fun update(id: String, transform: (RegisteredExtensionSource) -> RegisteredExtensionSource) = synchronized(lock) {
        val old = records.single { it.id == id }
        val next = transform(old)
        require(next.id == old.id && next.address == old.address && next.epoch >= old.epoch)
        persist(records.map { if (it.id == id) next else it })
    }

    private fun persist(next: List<RegisteredExtensionSource>) {
        val json = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "sources" to JsonArray(next.map { s ->
            JsonObject(mapOf("id" to JsonPrimitive(s.id), "url" to JsonPrimitive(s.address.url),
                "enabled" to JsonPrimitive(s.enabled), "removed" to JsonPrimitive(s.removed),
                "epoch" to JsonPrimitive(s.epoch), "attemptedAt" to (s.attemptedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
                "succeededAt" to (s.succeededAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
                "failure" to (s.failure?.let { JsonPrimitive(it.name) } ?: JsonNull)))
        })))
        val temporary = File(directory, "sources.next")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray()); it.fd.sync() }
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(directory.toPath()).use { it.force(true) }
        records = next
    }

    private fun decode(bytes: ByteArray): List<RegisteredExtensionSource> {
        val json = ExtensionWireCodec.parseStrictJson(bytes, 256 * 1024) as JsonObject
        require(json.keys == setOf("schemaVersion", "sources") && json.getValue("schemaVersion").jsonPrimitive.int == 1)
        val entries = json.getValue("sources").jsonArray
        require(entries.size <= 64)
        val sources = entries.map { value ->
            val s = value.jsonObject
            require(s.keys == setOf("id", "url", "enabled", "removed", "epoch", "attemptedAt", "succeededAt", "failure"))
            fun time(key: String) = s[key]?.takeUnless { it == JsonNull }?.let { Instant.parse(it.jsonPrimitive.content) }
            val id = s.getValue("id").jsonPrimitive.content
            require(UUID.fromString(id).toString() == id)
            RegisteredExtensionSource(id, NormalizedExtensionSource.parse(s.getValue("url").jsonPrimitive.content),
                s.getValue("enabled").jsonPrimitive.boolean, s.getValue("removed").jsonPrimitive.boolean,
                s.getValue("epoch").jsonPrimitive.long.also { require(it >= 0) }, time("attemptedAt"), time("succeededAt"),
                s["failure"]?.takeUnless { it == JsonNull }?.let { ExtensionSourceFailure.valueOf(it.jsonPrimitive.content) })
        }
        require(sources.map { it.id }.toSet().size == sources.size && sources.map { it.address.url }.toSet().size == sources.size)
        return sources
    }
}

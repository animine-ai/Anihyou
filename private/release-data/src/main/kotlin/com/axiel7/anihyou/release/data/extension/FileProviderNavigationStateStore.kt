package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class AcceptedProviderInstallment(val projectionKey: String, val seriesKey: String,
    val sourceSeason: Int, val providerEpisode: String, val track: String)
data class ProviderNavigationStoredState(
    val source: ExtensionSelectionKey? = null,
    val releaseGeneration: Long = 0,
    val packageDigest: String? = null,
    val installments: List<AcceptedProviderInstallment> = emptyList(),
    val segments: List<ProviderEpisodeSegment> = emptyList(),
)

/** Separate source-bound product receipt. Never reads R2 as release truth. */
class FileProviderNavigationStateStore(private val directory: File) {
    private val mutex = Mutex()
    private val file = File(directory, "navigation-state.json")
    private val mutable = MutableStateFlow(runCatching { if (file.exists()) decode(file.readBytes())
        else ProviderNavigationStoredState() }.getOrElse { ProviderNavigationStoredState() })
    val state = mutable.asStateFlow()

    suspend fun record(source: ExtensionSelectionKey, releaseGeneration: Long, packageDigest: String,
        installments: List<AcceptedProviderInstallment>) = mutate { old ->
        require(packageDigest.matches(Regex("[0-9a-f]{64}")))
        val retained = if (old.source == source && old.releaseGeneration == releaseGeneration && old.packageDigest == packageDigest)
            old.installments else emptyList()
        old.copy(source = source, releaseGeneration = releaseGeneration, packageDigest = packageDigest,
            // Preserve contradictory route receipts so the product can reject ambiguity.
            installments = (retained + installments).distinct().takeLast(10000))
    }

    /** Only host matching/manual binding calls this. Ambiguous overlapping segments remain non-actionable. */
    suspend fun replaceSegments(segments: List<ProviderEpisodeSegment>) = mutate {
        require(segments.size <= 1024 && segments.distinct() == segments)
        it.copy(segments = segments)
    }

    suspend fun upsertSegment(segment: ProviderEpisodeSegment) = mutate { old ->
        val retained = old.segments.filterNot { it.key == segment.key && it.mediaId == segment.mediaId &&
            it.seriesKey == segment.seriesKey && it.sourceSeason == segment.sourceSeason &&
            it.providerFirst == segment.providerFirst && it.canonicalFirst == segment.canonicalFirst }
        require(retained.size < 1024)
        old.copy(segments = retained + segment)
    }

    private suspend fun mutate(transform: (ProviderNavigationStoredState) -> ProviderNavigationStoredState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(mutable.value)
            val bytes = encode(next).toString().toByteArray()
            require(bytes.size <= 4 * 1024 * 1024)
            require(directory.isDirectory || directory.mkdirs())
            val temporary = File(directory, "navigation-state.next")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory.toPath()).use { it.force(true) }
            mutable.value = next
        }
    }

    private fun key(key: ExtensionSelectionKey?): JsonElement = key?.let {
        JsonArray(listOf(it.sourceId, it.extensionId, it.publisherId, it.providerId).map(::JsonPrimitive))
    } ?: JsonNull
    private fun parseKey(value: JsonElement): ExtensionSelectionKey? {
        if (value == JsonNull) return null
        val parts = value.jsonArray.map { it.jsonPrimitive.content }; require(parts.size == 4)
        return ExtensionSelectionKey(parts[0], parts[1], parts[2], parts[3])
    }
    private fun encode(state: ProviderNavigationStoredState) = buildJsonObject {
        put("schemaVersion", 1); put("source", key(state.source)); put("releaseGeneration", state.releaseGeneration)
        put("packageDigest", state.packageDigest?.let(::JsonPrimitive) ?: JsonNull)
        put("installments", JsonArray(state.installments.map { i -> JsonArray(
            listOf(i.projectionKey, i.seriesKey, i.sourceSeason.toString(), i.providerEpisode, i.track).map(::JsonPrimitive)) }))
        put("segments", JsonArray(state.segments.map { s -> buildJsonObject {
            put("key", key(s.key)); put("mediaId", s.mediaId); put("seriesKey", s.seriesKey)
            put("sourceSeason", s.sourceSeason); put("providerFirst", s.providerFirst)
            put("canonicalFirst", s.canonicalFirst); put("count", s.count)
        } }))
    }
    private fun decode(bytes: ByteArray): ProviderNavigationStoredState {
        val json = ExtensionWireCodec.parseStrictJson(bytes, 4 * 1024 * 1024).jsonObject
        require(json.keys == setOf("schemaVersion", "source", "releaseGeneration", "packageDigest", "installments", "segments"))
        require(json.getValue("schemaVersion").jsonPrimitive.int == 1)
        val rows = json.getValue("installments").jsonArray; require(rows.size <= 10000)
        val installments = rows.map { row ->
            val p = row.jsonArray.map { it.jsonPrimitive.content }; require(p.size == 5)
            require(p[0].length in 1..2048 && p[1].length in 1..128 && p[2].toInt() in 1..9999 &&
                p[3].matches(Regex("[1-9][0-9]{0,3}(?:\\.[0-9]{1,2})?")) && p[4] in setOf("DE_SUB", "DE_DUB"))
            AcceptedProviderInstallment(p[0], p[1], p[2].toInt(), p[3], p[4])
        }
        val rawSegments = json.getValue("segments").jsonArray; require(rawSegments.size <= 1024)
        val segments = rawSegments.map { value ->
            val s = value.jsonObject
            require(s.keys == setOf("key", "mediaId", "seriesKey", "sourceSeason", "providerFirst", "canonicalFirst", "count"))
            fun int(name: String) = s.getValue(name).jsonPrimitive.int
            ProviderEpisodeSegment(requireNotNull(parseKey(s.getValue("key"))), int("mediaId"),
                s.getValue("seriesKey").jsonPrimitive.content, int("sourceSeason"), int("providerFirst"), int("canonicalFirst"), int("count"))
        }
        return ProviderNavigationStoredState(parseKey(json.getValue("source")),
            json.getValue("releaseGeneration").jsonPrimitive.long.also { require(it >= 0) },
            json.getValue("packageDigest").takeUnless { it == JsonNull }?.jsonPrimitive?.content?.also { require(it.matches(Regex("[0-9a-f]{64}"))) },
            installments, segments)
    }
}

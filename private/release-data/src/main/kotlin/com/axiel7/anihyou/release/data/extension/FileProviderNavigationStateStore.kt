package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.io.File
import java.io.FileOutputStream
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
/** AniList metadata only. It never grants release authority or a persistent series binding. */
data class ProviderMediaNumbering(val mediaId: Int, val titles: Set<String>, val episodeExtent: Int,
    val malId: Int? = null,
    val episodeRules: List<com.axiel7.anihyou.release.data.malsync.EpisodeNumberingRule> = emptyList(),
    val rulesCheckedAt: Long = 0,
) {
    init { require(mediaId > 0 && episodeExtent in 1..9999 && titles.size <= 64 &&
        titles.all { it.isNotBlank() && it.length <= 512 })
        require(malId == null || malId > 0)
        require(episodeRules.size <= 64 && rulesCheckedAt >= 0)
    }
}
data class ProviderNavigationStoredState(
    val source: ExtensionSelectionKey? = null,
    val releaseGeneration: Long = 0,
    val packageDigest: String? = null,
    val installments: List<AcceptedProviderInstallment> = emptyList(),
    val segments: List<ProviderEpisodeSegment> = emptyList(),
    val syncStatistics: Map<String, String> = emptyMap(),
    val navigationStatus: String? = null,
    val packageGeneration: Long = 0,
    /**
     * The source whose refresh last committed accepted rows. [source] also moves when a refresh only failed, so it
     * cannot say who owns the persisted rows: a new source whose first refresh fails must not take over the rows of
     * the previous source. Package updates and rollbacks of the same source keep it.
     */
    val rowsSource: ExtensionSelectionKey? = null,
    val mediaNumbering: List<ProviderMediaNumbering> = emptyList(),
)

/** Separate source-bound product receipt. Never reads R2 as release truth. */
class FileProviderNavigationStateStore(private val directory: File) {
    private val mutex = Mutex()
    private val file = File(directory, "navigation-state.json")
    private val mutable = MutableStateFlow(runCatching { if (file.exists()) decode(file.readBytes())
        else ProviderNavigationStoredState() }.getOrElse { ProviderNavigationStoredState() })
    val state = mutable.asStateFlow()

    suspend fun record(source: ExtensionSelectionKey, releaseGeneration: Long, packageDigest: String,
        installments: List<AcceptedProviderInstallment>,
        statistics: Map<String, String> = emptyMap(), packageGeneration: Long = 0,
        rowsCommitted: Boolean = false) = mutate { old ->
        require(packageDigest.matches(Regex("[0-9a-f]{64}")))
        require(releaseGeneration >= 0 && packageGeneration >= 0)
        require(old.source != source || packageGeneration >= old.packageGeneration) {
            "package generation cannot move backwards for the same extension lifecycle"
        }
        val sameSelection = old.source == source && old.releaseGeneration == releaseGeneration &&
            old.packageDigest == packageDigest && old.packageGeneration == packageGeneration
        val retained = if (sameSelection)
            old.installments else emptyList()
        old.copy(source = source, releaseGeneration = releaseGeneration, packageDigest = packageDigest,
            // Preserve contradictory route receipts so the product can reject ambiguity.
            installments = (retained + installments).distinct().takeLast(10000),
            syncStatistics = statistics.takeIf { it.isNotEmpty() } ?: if (sameSelection) old.syncStatistics else emptyMap(),
            navigationStatus = if (sameSelection) old.navigationStatus else null,
            packageGeneration = packageGeneration,
            rowsSource = if (rowsCommitted) source else old.rowsSource)
    }

    suspend fun rememberNumbering(metadata: ProviderMediaNumbering) = mutate { old ->
        val previous = old.mediaNumbering.singleOrNull { it.mediaId == metadata.mediaId }
        val retained = if (previous != null && previous.malId == metadata.malId)
            metadata.copy(episodeRules = previous.episodeRules, rulesCheckedAt = previous.rulesCheckedAt) else metadata
        old.copy(mediaNumbering = (old.mediaNumbering.filterNot { it.mediaId == metadata.mediaId } + retained).takeLast(2048))
    }

    /** Reject an ID change while a rule request was in flight; failures never call this. */
    suspend fun rememberEpisodeRules(mediaId: Int, malId: Int,
        rules: List<com.axiel7.anihyou.release.data.malsync.EpisodeNumberingRule>, checkedAt: Long) = mutate { old ->
        old.copy(mediaNumbering = old.mediaNumbering.map {
            if (it.mediaId == mediaId && it.malId == malId) it.copy(episodeRules = rules, rulesCheckedAt = checkedAt) else it
        })
    }

    suspend fun recordNavigation(source: ExtensionSelectionKey, packageDigest: String, status: String,
        packageGeneration: Long = 0) = mutate {
        require(status in setOf("READY", "UNAVAILABLE", "LAUNCHED", "LAUNCH_REJECTED"))
        require(packageGeneration >= 0)
        if (it.source == source && it.packageDigest == packageDigest && it.packageGeneration == packageGeneration)
            it.copy(navigationStatus = status) else it
    }

    /** Removes exactly these segments (a confirmed reset). Idempotent: absent segments are ignored. */
    suspend fun removeSegments(remove: Set<ProviderEpisodeSegment>) = mutate {
        if (remove.isEmpty()) it else it.copy(segments = it.segments.filterNot { segment -> segment in remove })
    }

    /** Replaces one segment by an explicitly corrected one; a no-op if [old] is no longer stored. */
    suspend fun replaceSegment(old: ProviderEpisodeSegment, replacement: ProviderEpisodeSegment) = mutate { state ->
        if (old !in state.segments) state
        else state.copy(segments = state.segments.map { if (it == old) replacement else it }.distinct())
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
            if (next == mutable.value) return@withLock
            require(next.syncStatistics.size <= 16 && next.syncStatistics.all { it.key.length <= 64 && it.value.length <= 4096 })
            val bytes = encode(next).toString().toByteArray()
            require(bytes.size <= 4 * 1024 * 1024)
            require(directory.isDirectory || directory.mkdirs())
            val temporary = File(directory, "navigation-state.next")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            ExtensionFileDurability.syncDirectory(directory)
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
        put("mediaNumbering", JsonArray(state.mediaNumbering.map { m -> buildJsonObject {
            put("mediaId", m.mediaId); put("episodeExtent", m.episodeExtent)
            put("titles", JsonArray(m.titles.sorted().map(::JsonPrimitive)))
            put("malId", m.malId?.let(::JsonPrimitive) ?: JsonNull); put("rulesCheckedAt", m.rulesCheckedAt)
            put("episodeRules", JsonArray(m.episodeRules.map { r -> JsonArray(listOf(r.fromMalId,
                r.toMalId, r.providerFirst, r.canonicalFirst, r.count).map(::JsonPrimitive)) }))
        } }))
        put("schemaVersion", 1); put("source", key(state.source)); put("releaseGeneration", state.releaseGeneration)
        put("packageDigest", state.packageDigest?.let(::JsonPrimitive) ?: JsonNull)
        put("packageGeneration", state.packageGeneration)
        put("rowsSource", key(state.rowsSource))
        put("syncStatistics", JsonObject(state.syncStatistics.mapValues { JsonPrimitive(it.value) }))
        put("navigationStatus", state.navigationStatus?.let(::JsonPrimitive) ?: JsonNull)
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
        val required = setOf("schemaVersion", "source", "releaseGeneration", "packageDigest", "installments", "segments")
        val optional = setOf("syncStatistics", "navigationStatus", "packageGeneration", "rowsSource", "mediaNumbering")
        require(json.keys.containsAll(required) && optional.containsAll(json.keys - required))
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
        val source = parseKey(json.getValue("source"))
        // Files written before this field existed: the receipt source owned the rows exactly when it had accepted
        // installments. A receipt that only ever recorded a failure has none.
        val rowsSource = if ("rowsSource" in json) parseKey(json.getValue("rowsSource"))
            else source.takeIf { installments.isNotEmpty() }
        return ProviderNavigationStoredState(source,
            json.getValue("releaseGeneration").jsonPrimitive.long.also { require(it >= 0) },
            json.getValue("packageDigest").takeUnless { it == JsonNull }?.jsonPrimitive?.content?.also { require(it.matches(Regex("[0-9a-f]{64}"))) },
            installments, segments,
            json["syncStatistics"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty().also { values ->
                require(values.size <= 16 && values.all { it.key.length <= 64 && it.value.length <= 4096 })
            },
            json["navigationStatus"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.also {
                require(it in setOf("READY", "UNAVAILABLE", "LAUNCHED", "LAUNCH_REJECTED"))
            },
            json["packageGeneration"]?.jsonPrimitive?.long?.also { require(it >= 0) } ?: 0,
            rowsSource,
            json["mediaNumbering"]?.jsonArray.orEmpty().also { require(it.size <= 2048) }.map { value ->
                val m = value.jsonObject
                require(m.keys.containsAll(setOf("mediaId", "episodeExtent", "titles")) &&
                    (m.keys - setOf("mediaId", "episodeExtent", "titles", "malId", "episodeRules", "rulesCheckedAt")).isEmpty())
                ProviderMediaNumbering(m.getValue("mediaId").jsonPrimitive.int,
                    m.getValue("titles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                    m.getValue("episodeExtent").jsonPrimitive.int,
                    m["malId"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int,
                    m["episodeRules"]?.jsonArray.orEmpty().also { require(it.size <= 64) }.map { row ->
                        val r = row.jsonArray.map { it.jsonPrimitive.int }; require(r.size == 5)
                        com.axiel7.anihyou.release.data.malsync.EpisodeNumberingRule(r[0], r[1], r[2], r[3], r[4])
                    }, m["rulesCheckedAt"]?.jsonPrimitive?.long ?: 0)
            })
    }
}

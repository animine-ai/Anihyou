package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
import com.axiel7.anihyou.release.core.api.ExtensionPostponementPresentationRepository
import com.axiel7.anihyou.release.core.api.ExtensionPostponementSnapshot
import com.axiel7.anihyou.release.core.extension.ObservationClaimKind
import com.axiel7.anihyou.release.core.extension.ObservationScheduleMarker
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderObservationV1
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Durable last-known-good presentation cache for extension postponement notices.
 *
 * This file is presentation state only. It cannot grant release authority and it
 * never infers an AniList binding from a title. A media id is attached only when
 * an already validated V3 external mapping proves the provider identity.
 */
class FileExtensionPostponementStore(
    private val directory: File,
    private val database: ReleaseDatabase,
) : ExtensionPostponementPresentationRepository {
    private val mutex = Mutex()
    private val file = File(directory, FILE_NAME)
    private val mutableSnapshot = MutableStateFlow(load())
    override val snapshot = mutableSnapshot.asStateFlow()
    override val presentation = combine(snapshot, database.releaseDao().observeActiveAniListMappings()) { value, mappings ->
        value.copy(notices = value.notices.map { notice ->
            notice.copy(mediaId = mappedAniListId(value.source, notice, mappings))
        })
    }

    suspend fun record(
        source: ExtensionSelectionKey,
        releaseGeneration: Long,
        packageDigest: String,
        packageGeneration: Long,
        observedAt: Instant,
        observations: List<ProviderObservationV1>,
    ) {
        require(releaseGeneration >= 0 && packageGeneration >= 0)
        require(packageDigest.matches(SHA256))
        val notices = observations.asSequence()
            .filter {
                it.sourceRole == SourceRole.POSTPONEMENT &&
                    it.claimKind == ObservationClaimKind.CORRECTION &&
                    it.scheduleMarker != ObservationScheduleMarker.NONE &&
                    it.scheduleMarker != ObservationScheduleMarker.UNKNOWN
            }
            .take(MAX_NOTICES + 1)
            .toList()
            .also { require(it.size <= MAX_NOTICES) }
            .map { observation ->
                ExtensionPostponementNotice(
                    title = observation.rawTitle,
                    sourceSeason = observation.sourceSeason,
                    navigationSeason = observation.navigationSeason,
                    installmentNumber = observation.installment.number,
                    track = observation.track,
                    marker = observation.scheduleMarker,
                    rawText = observation.sourceRawText,
                    providerSeriesKey = observation.providerSeriesKey,
                    // Persist presentation coordinates, never durable catalogue authority.
                    mediaId = null,
                )
            }
            .distinctBy { it.presentationKey }

        val next = ExtensionPostponementSnapshot(
            source = source,
            releaseGeneration = releaseGeneration,
            packageDigest = packageDigest,
            packageGeneration = packageGeneration,
            observedAt = observedAt,
            notices = notices,
        )
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Missing rows in a partial upstream page are not removal authority.
                val previous = mutableSnapshot.value.takeIf { it.source == source }
                val incomingKeys = notices.mapTo(mutableSetOf()) { it.presentationKey }
                val retained = previous?.notices.orEmpty().filter { it.presentationKey !in incomingKeys }
                val incremental = next.copy(notices = (notices + retained).take(MAX_NOTICES))
                persist(incremental)
                mutableSnapshot.value = incremental
            }
        }
    }

    private fun mappedAniListId(
        source: ExtensionSelectionKey?,
        notice: ExtensionPostponementNotice,
        mappings: List<ExternalMappingEntity>,
    ): Int? {
        if (source?.providerId != "aniworld") return null
        val slug = notice.providerSeriesKey ?: return null
        val navigationSeason = notice.navigationSeason ?: return null
        val site = runCatching { AniWorldSiteIdentifier(slug) }.getOrNull() ?: return null
        val subject = runCatching { AniWorldMappingSubject.Season(site, navigationSeason) }.getOrNull()
            ?: return null
        val row = mappings.singleOrNull {
            it.mappingSubjectKey == subject.stableKey && it.externalProvider == "anilist"
        } ?: return null
        if (row.toDomainOrNull()?.subject != subject) return null
        if (row.mappingStatus != "ACTIVE" ||
            row.confidence !in setOf("EXACT", "HIGH") ||
            row.validatedAt == null ||
            row.staleAt != null
        ) return null
        return row.externalId?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun load(): ExtensionPostponementSnapshot = runCatching {
        if (!file.exists()) ExtensionPostponementSnapshot()
        else decode(file.readBytes())
    }.getOrElse { ExtensionPostponementSnapshot() }

    private fun persist(value: ExtensionPostponementSnapshot) {
        require(directory.isDirectory || directory.mkdirs())
        val bytes = encode(value).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val temporary = File(directory, "$FILE_NAME.next")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        ExtensionFileDurability.syncDirectory(directory)
    }

    private fun encode(value: ExtensionPostponementSnapshot): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "source" to encodeKey(value.source),
            "releaseGeneration" to JsonPrimitive(value.releaseGeneration),
            "packageDigest" to nullable(value.packageDigest),
            "packageGeneration" to (value.packageGeneration?.let(::JsonPrimitive) ?: JsonNull),
            "observedAt" to nullable(value.observedAt?.toString()),
            "notices" to JsonArray(value.notices.map { notice ->
                JsonObject(linkedMapOf(
                    "title" to JsonPrimitive(notice.title),
                    "sourceSeason" to (notice.sourceSeason?.let(::JsonPrimitive) ?: JsonNull),
                    "navigationSeason" to (notice.navigationSeason?.let(::JsonPrimitive) ?: JsonNull),
                    "installmentNumber" to nullable(notice.installmentNumber),
                    "track" to JsonPrimitive(notice.track.name),
                    "marker" to JsonPrimitive(notice.marker.name),
                    "rawText" to nullable(notice.rawText),
                    "providerSeriesKey" to nullable(notice.providerSeriesKey),
                    "mediaId" to (notice.mediaId?.let(::JsonPrimitive) ?: JsonNull),
                ))
            }),
        ),
    )

    private fun decode(bytes: ByteArray): ExtensionPostponementSnapshot {
        val root = ExtensionWireCodec.parseStrictJson(bytes, MAX_BYTES).jsonObject
        require(root.keys == setOf(
            "schemaVersion", "source", "releaseGeneration", "packageDigest",
            "packageGeneration", "observedAt", "notices",
        ))
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
        val notices = root.getValue("notices").jsonArray
        require(notices.size <= MAX_NOTICES)
        return ExtensionPostponementSnapshot(
            source = decodeKey(root.getValue("source")),
            releaseGeneration = root.getValue("releaseGeneration").jsonPrimitive.long.also {
                require(it >= 0)
            },
            packageDigest = stringOrNull(root.getValue("packageDigest")),
            packageGeneration = longOrNull(root.getValue("packageGeneration"))?.also {
                require(it >= 0)
            },
            observedAt = stringOrNull(root.getValue("observedAt"))?.let(Instant::parse),
            notices = notices.map { element ->
                val item = element.jsonObject
                require(item.keys == setOf(
                    "title", "sourceSeason", "navigationSeason", "installmentNumber",
                    "track", "marker", "rawText", "providerSeriesKey", "mediaId",
                ))
                ExtensionPostponementNotice(
                    title = item.getValue("title").jsonPrimitive.content,
                    sourceSeason = intOrNull(item.getValue("sourceSeason")),
                    navigationSeason = intOrNull(item.getValue("navigationSeason")),
                    installmentNumber = stringOrNull(item.getValue("installmentNumber")),
                    track = ObservationTrack.valueOf(item.getValue("track").jsonPrimitive.content),
                    marker = ObservationScheduleMarker.valueOf(item.getValue("marker").jsonPrimitive.content),
                    rawText = stringOrNull(item.getValue("rawText")),
                    providerSeriesKey = stringOrNull(item.getValue("providerSeriesKey")),
                    mediaId = intOrNull(item.getValue("mediaId")),
                )
            },
        )
    }

    private fun encodeKey(value: ExtensionSelectionKey?): JsonElement = value?.let {
        JsonArray(listOf(it.sourceId, it.extensionId, it.publisherId, it.providerId).map(::JsonPrimitive))
    } ?: JsonNull

    private fun decodeKey(value: JsonElement): ExtensionSelectionKey? {
        if (value == JsonNull) return null
        val parts = value.jsonArray.map { it.jsonPrimitive.content }
        require(parts.size == 4)
        return ExtensionSelectionKey(parts[0], parts[1], parts[2], parts[3])
    }

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun stringOrNull(value: JsonElement): String? =
        if (value == JsonNull) null else value.jsonPrimitive.content
    private fun intOrNull(value: JsonElement): Int? =
        if (value == JsonNull) null else value.jsonPrimitive.int
    private fun longOrNull(value: JsonElement): Long? =
        if (value == JsonNull) null else value.jsonPrimitive.long

    private companion object {
        const val FILE_NAME = "postponements-v1.json"
        const val MAX_BYTES = 512 * 1024
        const val MAX_NOTICES = 512
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

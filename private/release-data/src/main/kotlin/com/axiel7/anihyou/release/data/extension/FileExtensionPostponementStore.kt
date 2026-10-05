package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.api.ExtensionPostponementNotice
import com.axiel7.anihyou.release.core.api.ExtensionPostponementPresentationRepository
import com.axiel7.anihyou.release.core.api.ExtensionPostponementSnapshot
import com.axiel7.anihyou.release.core.extension.ObservationClaimKind
import com.axiel7.anihyou.release.core.extension.ObservationScheduleMarker
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    // Only the bindings in force for the snapshot's own exact source, never another source's.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val presentation = snapshot.flatMapLatest { value ->
        val source = value.source
        val effective = if (source == null) flowOf(emptyList()) else database.matchingDao().observeEffectiveAniListMappings(
            source.sourceId, source.extensionId, source.publisherId, source.providerId,
            com.axiel7.anihyou.release.data.repository.MappingEntryIds.sourceKey(source))
        val titles = if (source == null) flowOf("") else database.matchingDao().observeLabelTrigger()
        kotlinx.coroutines.flow.combine(effective, titles) { mappings, _ -> mappings }.mapLatest { mappings ->
            value.copy(notices = value.notices.map { notice ->
                // The notices of the real page carry only a title and coordinates, no series key. The title of a series the
                // source listed elsewhere (calendar, recent) names it, and the binding of that series (made by the matcher
                // or by the user, never a title guess here) gives the media.
                val media = mappedAniListId(value.source, notice, mappings) ?: byTitle(value.source, notice)
                notice.copy(mediaId = media)
            })
        }
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
                    installmentKind = observation.installment.kind,
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

    private suspend fun byTitle(source: ExtensionSelectionKey?, notice: ExtensionPostponementNotice): Int? {
        if (source?.providerId != "aniworld" || notice.providerSeriesKey != null) return null
        if (notice.installmentKind != ObservationInstallmentKind.EPISODE) return null
        val season = notice.navigationSeason ?: notice.sourceSeason ?: return null
        val dao = database.matchingDao()
        val folded = com.axiel7.anihyou.release.core.matching.SearchTitleFolding.fold(notice.title)
        if (folded.isEmpty()) return null
        // The same title first. The page of the postponements often gives a short name ("Re:Zero") where the series has a
        // longer one: then the series that share the beginning of the title and have a binding for exactly this season.
        val exact = dao.labelsByTitle(source.sourceId, source.extensionId, source.publisherId, source.providerId, folded)
        val labels = exact.ifEmpty {
            dao.labelsSharingTitleStart(source.sourceId, source.extensionId, source.publisherId, source.providerId, folded)
        }
        val media = labels.mapNotNull { label ->
            val subject = runCatching {
                AniWorldMappingSubject.Season(AniWorldSiteIdentifier(label.providerSeriesKey), season)
            }.getOrNull() ?: return@mapNotNull null
            val row = dao.sourceMapping(source.sourceId, source.extensionId, source.publisherId, source.providerId,
                subject.stableKey, "anilist") ?: return@mapNotNull null
            if (row.mappingStatus != "ACTIVE" || row.confidence !in setOf("EXACT", "HIGH") ||
                row.validatedAt == null || row.staleAt != null) return@mapNotNull null
            row.externalId?.toIntOrNull()?.takeIf { it > 0 }
        }.distinct()
        // Two different entries are ambiguous: no link is better than a wrong one.
        return media.singleOrNull()
    }

    private fun mappedAniListId(
        source: ExtensionSelectionKey?,
        notice: ExtensionPostponementNotice,
        mappings: List<ExternalMappingEntity>,
    ): Int? {
        if (source?.providerId != "aniworld") return null
        val slug = notice.providerSeriesKey ?: return null
        val site = runCatching { AniWorldSiteIdentifier(slug) }.getOrNull() ?: return null
        val subject = runCatching {
            when (notice.installmentKind) {
                ObservationInstallmentKind.EPISODE -> AniWorldMappingSubject.Season(site, notice.navigationSeason ?: return null)
                ObservationInstallmentKind.FILM -> AniWorldMappingSubject.Film(site, notice.installmentNumber?.toIntOrNull() ?: return null)
                ObservationInstallmentKind.SPECIAL, ObservationInstallmentKind.UNKNOWN -> return null
            }
        }.getOrNull() ?: return null
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
            "schemaVersion" to JsonPrimitive(2),
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
                    "installmentKind" to JsonPrimitive(notice.installmentKind.name),
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
        val schema = root.getValue("schemaVersion").jsonPrimitive.int
        require(schema in 1..2)
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
                val noticeKeys = setOf(
                    "title", "sourceSeason", "navigationSeason", "installmentNumber",
                    "track", "marker", "rawText", "providerSeriesKey", "mediaId",
                ) + (if (schema == 2) setOf("installmentKind") else emptySet())
                require(item.keys == noticeKeys)
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
                    // Legacy cache preserves display text but cannot infer season/film authority.
                    installmentKind = if (schema == 2) ObservationInstallmentKind.valueOf(
                        item.getValue("installmentKind").jsonPrimitive.content,
                    ) else ObservationInstallmentKind.UNKNOWN,
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

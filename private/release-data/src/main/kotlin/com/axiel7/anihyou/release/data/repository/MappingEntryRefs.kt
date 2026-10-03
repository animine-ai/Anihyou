package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.ReleaseMappingEntity
import java.net.URLDecoder
import java.net.URLEncoder

/** The storage generation and exact identity of one managed entry. */
internal sealed interface MappingEntryRef {
    /** Accepted binding of one exact release source (Room v14). */
    data class V3Source(val key: ExtensionSelectionKey, val entryKey: String) : MappingEntryRef
    /** Provider-wide binding from before source-bound storage; its origin is unknown and never guessed. */
    data class V3Legacy(val entryKey: String) : MappingEntryRef
    /** Older release lane binding. Its origin is unknown as well. */
    data class R2(val streamKey: String) : MappingEntryRef
    /** A source-bound manual episode segment kept by the navigation receipt store. */
    data class Navigation(val key: ExtensionSelectionKey, val seriesKey: String, val sourceSeason: Int,
                          val providerFirst: Int, val canonicalFirst: Int) : MappingEntryRef

    /** The fence kind and key of this entry, or null for entries no automatic writer exists for. */
    val fence: Pair<String, String>?
        get() = when (this) {
            is V3Source -> FENCE_V3_SOURCE to "${MappingEntryIds.sourceKey(key)}|$entryKey"
            is V3Legacy -> FENCE_V3_LEGACY to entryKey
            is R2 -> FENCE_R2 to streamKey
            is Navigation -> null
        }

    companion object {
        const val FENCE_V3_SOURCE = "V3S"
        const val FENCE_V3_LEGACY = "V3L"
        const val FENCE_R2 = "R2"
    }
}

internal object MappingEntryIds {
    private const val SEPARATOR = '/'

    fun sourceKey(key: ExtensionSelectionKey): String =
        listOf(key.sourceId, key.extensionId, key.publisherId, key.providerId).joinToString("\u0001")

    fun encode(ref: MappingEntryRef): String = when (ref) {
        is MappingEntryRef.V3Source -> join("v3s", ref.key.sourceId, ref.key.extensionId, ref.key.publisherId,
            ref.key.providerId, ref.entryKey)
        is MappingEntryRef.V3Legacy -> join("v3l", ref.entryKey)
        is MappingEntryRef.R2 -> join("r2", ref.streamKey)
        is MappingEntryRef.Navigation -> join("nav", ref.key.sourceId, ref.key.extensionId, ref.key.publisherId,
            ref.key.providerId, ref.seriesKey, ref.sourceSeason.toString(), ref.providerFirst.toString(),
            ref.canonicalFirst.toString())
    }

    fun decode(id: String): MappingEntryRef? = runCatching {
        val parts = id.split(SEPARATOR).map { URLDecoder.decode(it, Charsets.UTF_8.name()) }
        when (parts.firstOrNull()) {
            "v3s" -> MappingEntryRef.V3Source(ExtensionSelectionKey(parts[1], parts[2], parts[3], parts[4]), parts[5])
            "v3l" -> MappingEntryRef.V3Legacy(parts[1])
            "r2" -> MappingEntryRef.R2(parts[1])
            "nav" -> MappingEntryRef.Navigation(ExtensionSelectionKey(parts[1], parts[2], parts[3], parts[4]),
                parts[5], parts[6].toInt(), parts[7].toInt(), parts[8].toInt())
            else -> null
        }
    }.getOrNull().takeIf { it != null && parsesBack(it, id) }

    private fun parsesBack(ref: MappingEntryRef, id: String) = encode(ref) == id

    fun navigation(segment: ProviderEpisodeSegment) = MappingEntryRef.Navigation(
        segment.key, segment.seriesKey, segment.sourceSeason, segment.providerFirst, segment.canonicalFirst)

    fun navigationRevision(segment: ProviderEpisodeSegment) = "${segment.mediaId}:${segment.count}"

    /**
     * Revisions of entries that have no revision column are the content that matters for a decision. The SQL of the
     * management list builds exactly the same strings; the two must stay equal.
     */
    fun legacyV3Revision(row: ExternalMappingEntity) =
        "${row.validatedAt ?: ""}|${row.externalId}|${row.mappingSource}|${row.confidence}|${row.createdAt}"

    fun r2Revision(row: ReleaseMappingEntity) =
        "${row.updatedAt}|${row.mediaId}|${row.origin ?: ""}|${row.confidence ?: ""}"

    private fun join(vararg parts: String) = parts.joinToString(SEPARATOR.toString()) {
        URLEncoder.encode(it, Charsets.UTF_8.name())
    }
}

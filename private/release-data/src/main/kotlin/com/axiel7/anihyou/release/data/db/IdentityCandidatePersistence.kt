package com.axiel7.anihyou.release.data.db

import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.model.SourceIdentity
import java.time.Instant
import java.time.LocalDate

private const val NULL_VALUE = "-"

private fun packValues(values: List<String?>): String = buildString {
    values.forEach { value ->
        val encoded = value ?: NULL_VALUE
        append(encoded.length).append(':').append(encoded)
    }
}

private fun unpackValues(payload: String): List<String?>? {
    val values = mutableListOf<String?>()
    var cursor = 0
    while (cursor < payload.length) {
        val separator = payload.indexOf(':', cursor)
        if (separator <= cursor) return null
        val length = payload.substring(cursor, separator).toIntOrNull() ?: return null
        if (length < 0 || separator + 1 + length > payload.length) return null
        val start = separator + 1
        val value = payload.substring(start, start + length)
        values += if (value == NULL_VALUE) null else value
        cursor = start + length
    }
    return values
}

private fun packList(values: List<String>): String = packValues(values)

private fun unpackList(payload: String): List<String>? = unpackValues(payload)
    ?.takeIf { values -> values.none { it == null } }
    ?.map { it!! }

private fun encodeCandidate(candidate: IdentityCandidate): String = packValues(
    listOf(
        candidate.mediaId.toString(),
        packList(candidate.titles.toList().sorted()),
        candidate.format,
        candidate.startDate?.toString(),
    ),
)

private fun decodeCandidate(payload: String): IdentityCandidate? {
    val fields = unpackValues(payload)?.takeIf { it.size == 4 } ?: return null
    val mediaId = fields[0]?.toIntOrNull() ?: return null
    val titles = fields[1]?.let(::unpackList) ?: return null
    if (titles.isEmpty()) return null
    val startDate = fields[3]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (fields[3] != null && startDate == null) return null
    return runCatching {
        IdentityCandidate(
            mediaId = mediaId,
            titles = titles.toSet(),
            format = fields[2],
            startDate = startDate,
        )
    }.getOrNull()
}

private fun encodeBatch(batch: CandidateBatch): String = packValues(
    listOf(
        batch.complete.toString(),
        batch.nextCursor,
        batch.pagesFetched.toString(),
        packList(batch.candidates.map(::encodeCandidate)),
    ),
)

private fun decodeBatch(payload: String): CandidateBatch? {
    val fields = unpackValues(payload) ?: return null
    val pagesFetched: Int
    val recordsPayload: String?
    when (fields.size) {
        3 -> {
            pagesFetched = 0
            recordsPayload = fields[2]
        }
        4 -> {
            pagesFetched = fields[2]?.toIntOrNull() ?: return null
            recordsPayload = fields[3]
        }
        else -> return null
    }
    val complete = fields[0]?.toBooleanStrictOrNull() ?: return null
    val records = recordsPayload?.let(::unpackList) ?: return null
    val candidates = records.map { decodeCandidate(it) ?: return null }
    return CandidateBatch(
        candidates = candidates,
        complete = complete,
        nextCursor = fields[1],
        pagesFetched = pagesFetched,
    )
}

fun IdentityCandidate.toEntity(
    sourceKey: String,
    fetchedAt: Instant,
    expiresAt: Instant,
): IdentityCandidateEntity = IdentityCandidateEntity(
    sourceKey = sourceKey,
    mediaId = mediaId,
    titlesPayload = packList(titles.toList().sorted()),
    format = format,
    startDate = startDate?.toString(),
    fetchedAt = fetchedAt.toString(),
    expiresAt = expiresAt.toString(),
)

fun IdentityCandidateEntity.toDomainOrNull(): IdentityCandidate? {
    val titles = unpackList(titlesPayload) ?: return null
    if (titles.isEmpty()) return null
    val parsedStartDate = this.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (this.startDate != null && parsedStartDate == null) return null
    val fetched = runCatching { Instant.parse(fetchedAt) }.getOrNull() ?: return null
    val expires = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return null
    if (expires < fetched) return null
    return runCatching {
        IdentityCandidate(
            mediaId = mediaId,
            titles = titles.toSet(),
            format = format,
            startDate = parsedStartDate,
        )
    }.getOrNull()
}

fun CandidateBatch.toCacheEntity(
    source: SourceIdentity,
    query: TargetedIdentityQuery,
    fetchedAt: Instant,
    expiresAt: Instant,
): LookupCacheEntity = LookupCacheEntity(
    cacheKey = lookupCacheKey(source, query.signature),
    sourceKey = source.stableKey,
    query = query.query,
    signature = query.signature,
    resultPayload = encodeBatch(this),
    fetchedAt = fetchedAt.toString(),
    expiresAt = expiresAt.toString(),
)

fun LookupCacheEntity.toDomainOrNull(now: Instant): CandidateBatch? {
    val fetched = runCatching { Instant.parse(fetchedAt) }.getOrNull() ?: return null
    val expires = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return null
    if (expires <= now || expires < fetched) return null
    return decodeBatch(resultPayload)
}

fun lookupCacheKey(source: SourceIdentity, signature: String): String =
    source.stableKey + "::" + signature

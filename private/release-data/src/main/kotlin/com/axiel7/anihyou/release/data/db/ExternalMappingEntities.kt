package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingAttempt
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.model.MappingAttemptResultKind
import java.time.Instant

@Entity(
    tableName = "v3_external_mapping",
    primaryKeys = ["mappingSubjectKey", "externalProvider"],
    indices = [
        Index(value = ["seriesStableKey", "externalProvider"], name = "idx_v3_mapping_series_provider"),
        Index(value = ["mappingStatus", "validatedAt"], name = "idx_v3_mapping_status_validation"),
    ],
)
data class ExternalMappingEntity(
    val mappingSubjectKey: String,
    val seriesStableKey: String,
    val siteSlug: String,
    val subjectType: String,
    val navigationSeason: Int?,
    val filmNumber: Int?,
    val externalProvider: String,
    val externalId: String?,
    val mappingSource: String,
    val mappingStatus: String,
    val confidence: String,
    val createdAt: String,
    val validatedAt: String?,
    val staleAt: String?,
    val provenance: String,
    val parserVersion: String?,
)

@Entity(
    tableName = "v3_mapping_attempt",
    indices = [
        Index(value = ["mappingSubjectKey", "externalProvider", "attemptedAt"], name = "idx_v3_attempt_subject_time"),
        Index(value = ["resultKind", "attemptedAt"], name = "idx_v3_attempt_result_time"),
    ],
)
data class MappingAttemptEntity(
    @PrimaryKey(autoGenerate = true)
    val attemptId: Long = 0,
    val mappingSubjectKey: String,
    val seriesStableKey: String,
    val siteSlug: String,
    val subjectType: String,
    val navigationSeason: Int?,
    val filmNumber: Int?,
    val externalProvider: String,
    val source: String,
    val attemptedAt: String,
    val resultKind: String,
    val diagnostic: String,
    val httpStatus: Int?,
    val retryAt: String?,
    val cooldownUntil: String?,
    val parserVersion: String?,
)

fun ExternalMapping.toEntity(): ExternalMappingEntity {
    val subjectParts = subject.toEntityParts()
    return ExternalMappingEntity(
        mappingSubjectKey = subject.stableKey,
        seriesStableKey = subject.siteIdentifier.stableKey,
        siteSlug = subject.siteIdentifier.slug,
        subjectType = subjectParts.type,
        navigationSeason = subjectParts.navigationSeason,
        filmNumber = subjectParts.filmNumber,
        externalProvider = externalProvider.value,
        externalId = externalId,
        mappingSource = mappingSource.name,
        mappingStatus = status.name,
        confidence = confidence.name,
        createdAt = createdAt.toString(),
        validatedAt = validatedAt?.toString(),
        staleAt = staleAt?.toString(),
        provenance = provenance,
        parserVersion = parserVersion,
    )
}

fun ExternalMappingEntity.toDomainOrNull(): ExternalMapping? = runCatching {
    val site = AniWorldSiteIdentifier(slug = siteSlug)
    require(seriesStableKey == site.stableKey) { "mapping series key does not match site slug" }
    val subject = subjectFromStorage(site)
    require(mappingSubjectKey == subject.stableKey) { "mapping subject key does not match subject fields" }
    ExternalMapping(
        subject = subject,
        externalProvider = ExternalProvider(externalProvider),
        externalId = externalId,
        mappingSource = MappingSource.valueOf(mappingSource),
        confidence = MappingConfidence.valueOf(confidence),
        createdAt = Instant.parse(createdAt),
        validatedAt = validatedAt?.let(Instant::parse),
        status = MappingStatus.valueOf(mappingStatus),
        staleAt = staleAt?.let(Instant::parse),
        provenance = provenance,
        parserVersion = parserVersion,
    )
}.getOrNull()

fun ExternalMappingAttempt.toEntity(): MappingAttemptEntity {
    val subjectParts = subject.toEntityParts()
    return MappingAttemptEntity(
        mappingSubjectKey = subject.stableKey,
        seriesStableKey = subject.siteIdentifier.stableKey,
        siteSlug = subject.siteIdentifier.slug,
        subjectType = subjectParts.type,
        navigationSeason = subjectParts.navigationSeason,
        filmNumber = subjectParts.filmNumber,
        externalProvider = externalProvider.value,
        source = source.name,
        attemptedAt = attemptedAt.toString(),
        resultKind = resultKind.name,
        diagnostic = diagnostic,
        httpStatus = httpStatus,
        retryAt = retryAt?.toString(),
        cooldownUntil = cooldownUntil?.toString(),
        parserVersion = parserVersion,
    )
}

fun MappingAttemptEntity.toDomainOrNull(): ExternalMappingAttempt? = runCatching {
    val site = AniWorldSiteIdentifier(slug = siteSlug)
    require(seriesStableKey == site.stableKey) { "attempt series key does not match site slug" }
    val subject = subjectFromStorage(site)
    require(mappingSubjectKey == subject.stableKey) { "attempt subject key does not match subject fields" }
    ExternalMappingAttempt(
        subject = subject,
        externalProvider = ExternalProvider(externalProvider),
        source = MappingSource.valueOf(source),
        attemptedAt = Instant.parse(attemptedAt),
        resultKind = MappingAttemptResultKind.valueOf(resultKind),
        diagnostic = diagnostic,
        httpStatus = httpStatus,
        retryAt = retryAt?.let(Instant::parse),
        cooldownUntil = cooldownUntil?.let(Instant::parse),
        parserVersion = parserVersion,
    )
}.getOrNull()

private data class SubjectParts(
    val type: String,
    val navigationSeason: Int?,
    val filmNumber: Int?,
)

private fun AniWorldMappingSubject.toEntityParts(): SubjectParts = when (this) {
    is AniWorldMappingSubject.Season -> SubjectParts("SEASON", navigationSeason, null)
    is AniWorldMappingSubject.Film -> SubjectParts("FILM", null, filmNumber)
}

private fun ExternalMappingEntity.subjectFromStorage(site: AniWorldSiteIdentifier): AniWorldMappingSubject =
    subjectFromStorage(site, subjectType, navigationSeason, filmNumber)

private fun MappingAttemptEntity.subjectFromStorage(site: AniWorldSiteIdentifier): AniWorldMappingSubject =
    subjectFromStorage(site, subjectType, navigationSeason, filmNumber)

private fun subjectFromStorage(
    site: AniWorldSiteIdentifier,
    subjectType: String,
    navigationSeason: Int?,
    filmNumber: Int?,
): AniWorldMappingSubject = when (subjectType) {
    "SEASON" -> AniWorldMappingSubject.Season(
        site,
        navigationSeason ?: error("season mapping is missing navigation season"),
    )
    "FILM" -> AniWorldMappingSubject.Film(
        site,
        filmNumber ?: error("film mapping is missing film number"),
    )
    else -> error("unknown mapping subject type")
}

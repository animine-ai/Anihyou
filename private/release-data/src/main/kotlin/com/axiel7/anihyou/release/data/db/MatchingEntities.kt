package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * The title and aliases a release source itself reported for one of its series. Written in the commit of the
 * cycle that observed them; a source's title is never replaced by an AniList title.
 */
@Entity(tableName = "v3_source_series_label",
    primaryKeys = ["sourceId", "extensionId", "publisherId", "providerId", "providerSeriesKey"],
    indices = [Index(value = ["sourceId", "extensionId", "publisherId", "providerId", "titleNormalized"],
        name = "idx_v3_label_title")])
data class SourceSeriesLabelEntity(
    val sourceId: String,
    val extensionId: String,
    val publisherId: String,
    val providerId: String,
    val providerSeriesKey: String,
    val title: String,
    val titleNormalized: String,
    /** Folded aliases separated by a newline, for bounded substring search. */
    val aliasesPayload: String,
    val firstSeenAt: String,
    val lastSeenAt: String,
)

/**
 * An accepted binding of one subject of one exact release source to an external id. Resetting removes the row and
 * raises the fence; a correction rewrites it with a new [revision].
 */
@Entity(tableName = "v3_source_mapping",
    primaryKeys = ["sourceId", "extensionId", "publisherId", "providerId", "mappingSubjectKey", "externalProvider"],
    indices = [
        Index(value = ["sourceId", "extensionId", "publisherId", "providerId", "siteSlug"], name = "idx_v3_source_mapping_slug"),
        Index(value = ["externalProvider", "externalId"], name = "idx_v3_source_mapping_target"),
    ])
data class SourceMappingEntity(
    val sourceId: String,
    val extensionId: String,
    val publisherId: String,
    val providerId: String,
    val mappingSubjectKey: String,
    val externalProvider: String,
    val seriesStableKey: String,
    val siteSlug: String,
    val subjectType: String,
    val navigationSeason: Int?,
    val filmNumber: Int?,
    val externalId: String?,
    val mappingSource: String,
    val mappingStatus: String,
    val confidence: String,
    val createdAt: String,
    val validatedAt: String?,
    val staleAt: String?,
    val provenance: String,
    val parserVersion: String?,
    val revision: Long,
    val updatedAt: String,
)

/**
 * The writer fence. An entry that was ever reset or corrected has one row: [changedAt] is when, [epoch] counts the
 * changes. A job remembers when it started observing; it may write an entry only if that moment is later than the
 * last change, so a job that started before a reset can never restore the removed binding.
 */
@Entity(tableName = "v3_mapping_fence", primaryKeys = ["entryKind", "entryKey"])
data class MappingFenceEntity(
    val entryKind: String,
    val entryKey: String,
    val epoch: Long,
    val changedAt: String,
)

/** A durable snapshot of a confirmed bulk scope: entry ids with their revisions, never re-expanded at execution. */
@Entity(tableName = "v3_mapping_action")
data class MappingActionEntity(
    @androidx.room.PrimaryKey val token: String,
    val createdAt: String,
    val scopeKind: String,
    val payload: String,
    val entryCount: Int,
    val consumedAt: String?,
)

package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "identity_candidate",
    primaryKeys = ["sourceKey", "mediaId"],
    indices = [
        Index(value = ["sourceKey", "expiresAt"], name = "idx_identity_candidate_source_expiry"),
        Index(value = ["mediaId"], name = "idx_identity_candidate_media"),
    ],
)
data class IdentityCandidateEntity(
    val sourceKey: String,
    val mediaId: Int,
    val titlesPayload: String,
    val format: String?,
    val startDate: String?,
    val fetchedAt: String,
    val expiresAt: String,
)

@Entity(
    tableName = "lookup_cache",
    indices = [
        Index(value = ["sourceKey", "expiresAt"], name = "idx_lookup_cache_source_expiry"),
        Index(value = ["signature", "expiresAt"], name = "idx_lookup_cache_signature_expiry"),
    ],
)
data class LookupCacheEntity(
    @androidx.room.PrimaryKey val cacheKey: String,
    val sourceKey: String,
    val query: String,
    val signature: String,
    val resultPayload: String,
    val fetchedAt: String,
    val expiresAt: String,
)

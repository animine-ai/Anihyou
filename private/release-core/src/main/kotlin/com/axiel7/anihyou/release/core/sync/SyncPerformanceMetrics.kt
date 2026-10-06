package com.axiel7.anihyou.release.core.sync

data class SyncPerformanceMetrics(
    val providerId: String,
    val generation: Long,
    val durationMillis: Long,
    val currentSeasonPagesFetched: Int,
    val previousSeasonPagesFetched: Int,
    val targetedQueries: Int,
    val pooledCandidateCount: Int,
    val targetedCandidateCount: Int,
    val maxConcurrentRequests: Int,
) {
    init {
        require(providerId.isNotBlank()) { "performance provider id must not be blank" }
        require(generation >= 0L) { "performance generation must be non-negative" }
        require(durationMillis >= 0L) { "performance duration must be non-negative" }
        require(currentSeasonPagesFetched >= 0) { "current season page count must be non-negative" }
        require(previousSeasonPagesFetched >= 0) { "previous season page count must be non-negative" }
        require(targetedQueries >= 0) { "targeted query count must be non-negative" }
        require(pooledCandidateCount >= 0) { "pooled candidate count must be non-negative" }
        require(targetedCandidateCount >= 0) { "targeted candidate count must be non-negative" }
        require(maxConcurrentRequests > 0) { "concurrency limit must be positive" }
    }

    val totalCandidateCount: Int
        get() = pooledCandidateCount + targetedCandidateCount

    fun toDiagnosticMessage(): String = listOf(
        "generation=$generation",
        "durationMs=$durationMillis",
        "currentSeasonPages=$currentSeasonPagesFetched",
        "previousSeasonPages=$previousSeasonPagesFetched",
        "targetedQueries=$targetedQueries",
        "pooledCandidates=$pooledCandidateCount",
        "targetedCandidates=$targetedCandidateCount",
        "maxConcurrentRequests=$maxConcurrentRequests",
    ).joinToString(";")
}

data class IdentityResolutionResult(
    val mappings: Map<String, com.axiel7.anihyou.release.core.model.ReleaseMapping?>,
    val metrics: SyncPerformanceMetrics,
)

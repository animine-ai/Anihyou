package com.axiel7.anihyou.release.core.sync

import com.axiel7.anihyou.release.core.model.SourceIdentity

data class SyncBudget(
    val currentSeasonMaxPages: Int = 4,
    val previousSeasonMaxPages: Int = 4,
    val maxTargetedQueries: Int = 4,
    val maxVariantsPerIdentity: Int = 2,
    val maxConcurrentRequests: Int = 2,
) {
    init {
        require(currentSeasonMaxPages > 0) { "current season page budget must be positive" }
        require(previousSeasonMaxPages > 0) { "previous season page budget must be positive" }
        require(maxTargetedQueries > 0) { "targeted query budget must be positive" }
        require(maxVariantsPerIdentity > 0) { "variant budget must be positive" }
        require(maxConcurrentRequests > 0) { "concurrency budget must be positive" }
    }
}

data class SyncPlanCursor(
    val currentSeasonPage: Int = 0,
    val previousSeasonPage: Int = 0,
    val targetedOffset: Int = 0,
) {
    init {
        require(currentSeasonPage >= 0) { "current season cursor must be non-negative" }
        require(previousSeasonPage >= 0) { "previous season cursor must be non-negative" }
        require(targetedOffset >= 0) { "targeted cursor must be non-negative" }
    }
}

data class TargetedLookupWork(
    val identity: SourceIdentity,
    val variants: List<String>,
    val signature: String,
) {
    init {
        require(variants.isNotEmpty()) { "targeted lookup needs a variant" }
        require(variants.all { it.isNotBlank() }) { "targeted variants must not be blank" }
        require(signature.isNotBlank()) { "targeted lookup signature must not be blank" }
    }
}

data class SyncPlanRequest(
    val generation: Long,
    val currentSeasonPagesAvailable: Int,
    val previousSeasonPagesAvailable: Int,
    val targetedWork: List<TargetedLookupWork> = emptyList(),
    val cursor: SyncPlanCursor = SyncPlanCursor(),
    val budget: SyncBudget = SyncBudget(),
) {
    init {
        require(generation >= 0L) { "generation must be non-negative" }
        require(currentSeasonPagesAvailable >= 0) { "current season page count must be non-negative" }
        require(previousSeasonPagesAvailable >= 0) { "previous season page count must be non-negative" }
    }
}

data class ReleaseSyncPlan(
    val generation: Long,
    val currentSeasonPages: Int,
    val previousSeasonPages: Int,
    val targetedWork: List<TargetedLookupWork>,
    val maxConcurrentRequests: Int,
    val nextCursor: SyncPlanCursor,
    val complete: Boolean,
)

object ReleaseSyncPlanner {
    fun plan(request: SyncPlanRequest): ReleaseSyncPlan {
        val currentStart = request.cursor.currentSeasonPage
            .coerceAtMost(request.currentSeasonPagesAvailable)
        val previousStart = request.cursor.previousSeasonPage
            .coerceAtMost(request.previousSeasonPagesAvailable)
        val currentCount = (request.currentSeasonPagesAvailable - currentStart)
            .coerceAtMost(request.budget.currentSeasonMaxPages)
        val previousCount = (request.previousSeasonPagesAvailable - previousStart)
            .coerceAtMost(request.budget.previousSeasonMaxPages)
        val orderedWork = request.targetedWork
            .groupBy { it.identity.stableKey }
            .toSortedMap()
            .values
            .flatMap { works ->
                works
                    .sortedWith(compareBy<TargetedLookupWork> { it.signature })
                    .flatMap { work ->
                        work.variants.distinct().sorted().map { variant ->
                            work.copy(variants = listOf(variant))
                        }
                    }
                    .distinctBy { work -> work.signature + "::" + work.variants.single() }
                    .take(request.budget.maxVariantsPerIdentity)
            }
        val targetedStart = request.cursor.targetedOffset.coerceAtMost(orderedWork.size)
        val targetedWork = orderedWork.drop(targetedStart).take(request.budget.maxTargetedQueries)
        val nextTargetedOffset = targetedStart + targetedWork.size
        val nextCursor = SyncPlanCursor(
            currentSeasonPage = currentStart + currentCount,
            previousSeasonPage = previousStart + previousCount,
            targetedOffset = nextTargetedOffset,
        )
        val complete = nextCursor.currentSeasonPage >= request.currentSeasonPagesAvailable &&
            nextCursor.previousSeasonPage >= request.previousSeasonPagesAvailable &&
            nextCursor.targetedOffset >= orderedWork.size
        return ReleaseSyncPlan(
            generation = request.generation,
            currentSeasonPages = currentCount,
            previousSeasonPages = previousCount,
            targetedWork = targetedWork,
            maxConcurrentRequests = request.budget.maxConcurrentRequests,
            nextCursor = nextCursor,
            complete = complete,
        )
    }
}

 
enum class CandidateSeason {
    WINTER,
    SPRING,
    SUMMER,
    FALL,
}

data class CandidatePoolWindow(
    val season: CandidateSeason,
    val year: Int,
) {
    init {
        require(year > 0) { "candidate pool year must be positive" }
    }

    val cacheKey: String = "season-pool:" + season.name.lowercase(java.util.Locale.ROOT) + ":" + year
}

data class CandidatePoolRequest(
    val window: CandidatePoolWindow,
    val maxPages: Int,
) {
    init {
        require(maxPages > 0) { "candidate pool page budget must be positive" }
    }
}

object CandidatePoolWindows {
    fun currentAndPrevious(sourceDate: java.time.LocalDate): List<CandidatePoolWindow> {
        val current = CandidatePoolWindow(
            season = seasonFor(sourceDate.monthValue),
            year = sourceDate.year,
        )
        val previous = when (current.season) {
            CandidateSeason.WINTER -> CandidatePoolWindow(CandidateSeason.FALL, current.year - 1)
            CandidateSeason.SPRING -> CandidatePoolWindow(CandidateSeason.WINTER, current.year)
            CandidateSeason.SUMMER -> CandidatePoolWindow(CandidateSeason.SPRING, current.year)
            CandidateSeason.FALL -> CandidatePoolWindow(CandidateSeason.SUMMER, current.year)
        }
        return listOf(current, previous)
    }

    private fun seasonFor(month: Int): CandidateSeason = when (month) {
        in 1..3 -> CandidateSeason.WINTER
        in 4..6 -> CandidateSeason.SPRING
        in 7..9 -> CandidateSeason.SUMMER
        else -> CandidateSeason.FALL
    }
}

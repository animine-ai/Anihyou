package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.sync.ForecastRecheckAction

data class ReleaseAccountContext(
    val accountId: Long?,
    val progressByMediaId: Map<Int, Int> = emptyMap(),
) {
    init {
        require(accountId == null || accountId > 0L) { "account id must be positive when present" }
        require(progressByMediaId.keys.all { it > 0 }) {
            "progress media ids must be positive"
        }
        require(progressByMediaId.values.all { it >= 0 }) {
            "progress values must be non-negative"
        }
        require(accountId != null || progressByMediaId.isEmpty()) {
            "progress requires an account"
        }
    }

    /**
     * Returns null when the local account cache did not contain this media id.
     * A missing row is not evidence of zero progress.
     */
    fun progressFor(mediaId: Int): Int? = progressByMediaId[mediaId]
}

fun interface ReleaseAccountContextProvider {
    suspend fun current(mediaIds: Set<Int>): ReleaseAccountContext
}

object EmptyReleaseAccountContextProvider : ReleaseAccountContextProvider {
    override suspend fun current(mediaIds: Set<Int>): ReleaseAccountContext =
        ReleaseAccountContext(accountId = null)
}

interface ReleaseRefreshCoordinator {
    suspend fun refresh(reason: RefreshReason): RefreshOutcome
}

fun interface ReleaseOutboxScheduler {
    fun schedule()
}

object NoopReleaseOutboxScheduler : ReleaseOutboxScheduler {
    override fun schedule() = Unit
}

interface ReleaseForecastRecheckScheduler {
    fun apply(
        actions: List<ForecastRecheckAction>,
        cancelWorkKeys: List<String> = emptyList(),
    )
}

object NoopReleaseForecastRecheckScheduler : ReleaseForecastRecheckScheduler {
    override fun apply(actions: List<ForecastRecheckAction>, cancelWorkKeys: List<String>) = Unit
}

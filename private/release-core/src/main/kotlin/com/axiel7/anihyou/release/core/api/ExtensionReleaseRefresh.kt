package com.axiel7.anihyou.release.core.api

/** Product release-data work, separate from repository metadata and the debug canary. */
interface ExtensionReleaseRefreshScheduler {
    fun scheduleDue()
    fun scheduleNow()
    fun cancel()
}

fun interface ExtensionReleaseRefreshCoordinator {
    suspend fun refresh(workId: String, force: Boolean): ShadowRefreshOutcome
}

package com.axiel7.anihyou.feature.mediadetails

import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Resolve a loaded anime's mapping once before observing navigation; progress changes only refresh navigation. */
internal fun <P, T> Flow<P>.observeNavigationAfterDetailMapping(
    mediaId: Int,
    request: DetailMappingRequest?,
    ensureDetailMapping: suspend (DetailMappingRequest) -> Unit,
    observe: (Int, P) -> Flow<T>,
): Flow<T> = flow {
    if (request != null) {
        try {
            ensureDetailMapping(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A lookup failure must not hide accepted streaming navigation results.
        }
    }
    emitAll(this@observeNavigationAfterDetailMapping.distinctUntilChanged()
        .flatMapLatest { progress -> observe(mediaId, progress) })
}

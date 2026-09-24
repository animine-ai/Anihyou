package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.time.Instant
import kotlinx.coroutines.flow.Flow

enum class ReleaseDeliveryState {
    PENDING,
    POSTING,
    DELIVERED,
    CANCELLED,
}

data class ReleaseDeliveryWork(
    val eventKey: String,
    val accountId: Long,
    val mediaId: Int,
    val identityKey: String,
    val displayTitle: String?,
    val installment: Installment?,
    val track: LanguageTrack?,
    val state: ReleaseDeliveryState,
    val attemptCount: Int,
)

interface ReleaseOutboxRepository {
    fun observeDue(now: Instant): Flow<List<ReleaseDeliveryWork>>

    suspend fun claim(
        eventKey: String,
        now: Instant,
        leaseUntil: Instant? = null,
    ): ReleaseDeliveryWork?

    suspend fun markDelivered(
        eventKey: String,
        deliveredAt: Instant,
    ): Boolean

    suspend fun reschedulePending(
        eventKey: String,
        nextAttemptAt: Instant,
        error: String,
    ): ReleaseDeliveryState?

    suspend fun cancel(
        eventKey: String,
        reason: String,
        cancelledAt: Instant,
    ): Boolean

    suspend fun cancelPending(
        reason: String,
        cancelledAt: Instant,
    ): Int

    suspend fun cancelPendingForProvider(
        providerId: String,
        reason: String,
        cancelledAt: Instant,
    ): Int
}

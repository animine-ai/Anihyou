package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleaseDeliveryState
import com.axiel7.anihyou.release.core.api.ReleaseDeliveryWork
import com.axiel7.anihyou.release.core.api.ReleaseOutboxRepository
import com.axiel7.anihyou.release.data.db.NotificationOutboxRecord
import com.axiel7.anihyou.release.data.db.NotificationOutboxStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReleaseOutboxRepository(
    private val store: RoomNotificationOutboxStore,
) : ReleaseOutboxRepository {
    override fun observeDue(now: Instant): Flow<List<ReleaseDeliveryWork>> =
        store.observeDue(now).map { rows -> rows.map { it.toReleaseDeliveryWork() } }

    override suspend fun claim(
        eventKey: String,
        now: Instant,
        leaseUntil: Instant?,
    ): ReleaseDeliveryWork? =
        store.claim(eventKey, now, leaseUntil)?.toReleaseDeliveryWork()

    override suspend fun markDelivered(eventKey: String, deliveredAt: Instant): Boolean =
        store.markDelivered(eventKey, deliveredAt)

    override suspend fun reschedulePending(
        eventKey: String,
        nextAttemptAt: Instant,
        error: String,
    ): ReleaseDeliveryState? =
        store.reschedulePending(eventKey, nextAttemptAt, error)?.toReleaseDeliveryState()

    override suspend fun cancel(
        eventKey: String,
        reason: String,
        cancelledAt: Instant,
    ): Boolean = store.cancel(eventKey, reason, cancelledAt)

    override suspend fun cancelPending(reason: String, cancelledAt: Instant): Int =
        store.cancelPending(reason, cancelledAt)

    override suspend fun cancelPendingForProvider(
        providerId: String,
        reason: String,
        cancelledAt: Instant,
    ): Int = store.cancelPendingForProvider(providerId, reason, cancelledAt)
}

private fun NotificationOutboxRecord.toReleaseDeliveryWork(): ReleaseDeliveryWork =
    ReleaseDeliveryWork(
        eventKey = eventKey,
        accountId = accountId,
        mediaId = mediaId,
        identityKey = identityKey,
        displayTitle = displayTitle,
        installment = installment,
        track = track,
        state = status.toReleaseDeliveryState(),
        attemptCount = attemptCount,
    )

private fun NotificationOutboxStatus.toReleaseDeliveryState(): ReleaseDeliveryState = when (this) {
    NotificationOutboxStatus.PENDING -> ReleaseDeliveryState.PENDING
    NotificationOutboxStatus.POSTING -> ReleaseDeliveryState.POSTING
    NotificationOutboxStatus.DELIVERED -> ReleaseDeliveryState.DELIVERED
    NotificationOutboxStatus.CANCELLED -> ReleaseDeliveryState.CANCELLED
}

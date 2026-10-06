package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.data.db.MAX_NOTIFICATION_ATTEMPTS
import com.axiel7.anihyou.release.data.db.MAX_NOTIFICATION_ERROR_LENGTH
import com.axiel7.anihyou.release.data.db.NotificationOutboxRecord
import com.axiel7.anihyou.release.data.db.NotificationOutboxStatus
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomNotificationOutboxStore(
    private val database: ReleaseDatabase,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao = database.releaseDao()

    suspend fun enqueue(
        candidate: ReleaseNotificationCandidate,
        createdAt: Instant? = null,
    ): Boolean = dao.insertNotificationIfAbsent(candidate.toEntity(createdAt ?: clock.instant())) != -1L

    fun observeDue(
        now: Instant? = null,
    ): Flow<List<NotificationOutboxRecord>> = dao.observePendingNotifications(
        now = (now ?: clock.instant()).toString(),
        maxAttempts = MAX_NOTIFICATION_ATTEMPTS,
    ).map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    suspend fun claim(
        eventKey: String,
        now: Instant? = null,
        leaseUntil: Instant? = null,
    ): NotificationOutboxRecord? {
        require(eventKey.isNotBlank()) { "event key must not be blank" }
        val effectiveNow = now ?: clock.instant()
        val lease = leaseUntil ?: effectiveNow.plusSeconds(POSTING_LEASE_SECONDS)
        require(lease > effectiveNow) { "posting lease must be in the future" }

        dao.cancelExhaustedNotifications(
            now = effectiveNow.toString(),
            cancelledAt = effectiveNow.toString(),
            reason = "maximum notification attempts reached",
            maxAttempts = MAX_NOTIFICATION_ATTEMPTS,
        )
        val claimed = dao.claimNotification(
            eventKey = eventKey,
            now = effectiveNow.toString(),
            leaseUntil = lease.toString(),
            maxAttempts = MAX_NOTIFICATION_ATTEMPTS,
        )
        if (claimed == 0) return null
        return dao.getNotificationOutbox(eventKey)
            ?.toDomainOrNull()
            ?.takeIf { it.status == NotificationOutboxStatus.POSTING }
    }

    suspend fun markDelivered(
        eventKey: String,
        deliveredAt: Instant? = null,
    ): Boolean {
        require(eventKey.isNotBlank()) { "event key must not be blank" }
        val current = dao.getNotificationOutbox(eventKey)?.toDomainOrNull() ?: return false
        if (current.status == NotificationOutboxStatus.DELIVERED) return true
        if (current.status != NotificationOutboxStatus.POSTING) return false
        return dao.markNotificationDelivered(eventKey, (deliveredAt ?: clock.instant()).toString()) > 0
    }

    suspend fun reschedulePending(
        eventKey: String,
        nextAttemptAt: Instant,
        error: String,
    ): NotificationOutboxStatus? {
        require(eventKey.isNotBlank()) { "event key must not be blank" }
        require(error.isNotBlank()) { "retry error must not be blank" }
        val current = dao.getNotificationOutbox(eventKey)?.toDomainOrNull() ?: return null
        when (current.status) {
            NotificationOutboxStatus.DELIVERED,
            NotificationOutboxStatus.CANCELLED,
            -> return current.status
            NotificationOutboxStatus.PENDING -> return current.status
            NotificationOutboxStatus.POSTING -> {
                val boundedError = error.take(MAX_NOTIFICATION_ERROR_LENGTH)
                if (current.attemptCount >= MAX_NOTIFICATION_ATTEMPTS) {
                    dao.markNotificationCancelled(
                        eventKey = eventKey,
                        cancelledAt = clock.instant().toString(),
                        reason = boundedError,
                    )
                } else {
                    dao.markNotificationPending(
                        eventKey = eventKey,
                        nextAttemptAt = nextAttemptAt.toString(),
                        lastError = boundedError,
                        maxAttempts = MAX_NOTIFICATION_ATTEMPTS,
                    )
                }
            }
        }
        return dao.getNotificationOutbox(eventKey)?.toDomainOrNull()?.status
    }

    suspend fun cancel(
        eventKey: String,
        reason: String,
        cancelledAt: Instant? = null,
    ): Boolean {
        require(eventKey.isNotBlank()) { "event key must not be blank" }
        require(reason.isNotBlank()) { "cancellation reason must not be blank" }
        val current = dao.getNotificationOutbox(eventKey)?.toDomainOrNull() ?: return false
        if (current.status == NotificationOutboxStatus.CANCELLED) return true
        if (current.status == NotificationOutboxStatus.DELIVERED) return false
        return dao.markNotificationCancelled(
            eventKey = eventKey,
            cancelledAt = (cancelledAt ?: clock.instant()).toString(),
            reason = reason.take(MAX_NOTIFICATION_ERROR_LENGTH),
        ) > 0
    }

    suspend fun cancelPending(
        reason: String,
        cancelledAt: Instant? = null,
    ): Int {
        require(reason.isNotBlank()) { "cancellation reason must not be blank" }
        return dao.cancelPendingNotifications(
            cancelledAt = (cancelledAt ?: clock.instant()).toString(),
            reason = reason.take(MAX_NOTIFICATION_ERROR_LENGTH),
        )
    }

    suspend fun cancelPendingForProvider(
        providerId: String,
        reason: String,
        cancelledAt: Instant? = null,
    ): Int {
        require(providerId.isNotBlank()) { "provider id must not be blank" }
        require(reason.isNotBlank()) { "cancellation reason must not be blank" }
        return dao.cancelPendingNotificationsForProvider(
            identityPrefix = providerId.trim() + "/%",
            cancelledAt = (cancelledAt ?: clock.instant()).toString(),
            reason = reason.take(MAX_NOTIFICATION_ERROR_LENGTH),
        )
    }

    /**
     * Compatibility name for callers that used the provisional retry API.
     * The persisted state remains PENDING or CANCELLED, never RETRY or FAILED.
     */
    suspend fun scheduleRetry(
        eventKey: String,
        nextAttemptAt: Instant,
        error: String,
    ): NotificationOutboxStatus? = reschedulePending(eventKey, nextAttemptAt, error)

    /**
     * A terminal posting failure is represented as CANCELLED in the canonical contract.
     */
    suspend fun markFailed(
        eventKey: String,
        error: String,
    ): Boolean = cancel(eventKey, error, clock.instant())

    private companion object {
        const val POSTING_LEASE_SECONDS = 10L * 60L
    }
}

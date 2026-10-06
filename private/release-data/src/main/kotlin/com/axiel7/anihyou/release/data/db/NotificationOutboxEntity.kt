package com.axiel7.anihyou.release.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_outbox",
    indices = [
        Index(
            value = ["accountId", "status", "nextAttemptAt"],
            name = "idx_notification_outbox_account_status_attempt",
        ),
        Index(
            value = ["status", "postingLeaseUntil"],
            name = "idx_notification_outbox_status_lease",
        ),
        Index(value = ["createdAt"], name = "idx_notification_outbox_created"),
    ],
)
data class NotificationOutboxEntity(
    @PrimaryKey val eventKey: String,
    val accountId: Long,
    val mediaId: Int,
    val eventKind: String,
    val identityKey: String,
    val payload: String,
    val evidenceKind: String,
    val observedAt: String,
    val status: String,
    val attemptCount: Int,
    val createdAt: String,
    val nextAttemptAt: String?,
    val postingLeaseUntil: String?,
    val deliveredAt: String?,
    val cancelledAt: String?,
    val lastError: String?,
    val displayTitle: String? = null,
    val installmentPayload: String? = null,
    val track: String? = null,
)

package com.axiel7.anihyou.release.data.db

import com.axiel7.anihyou.release.core.notification.NotificationEventKind
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import java.time.Instant

enum class NotificationOutboxStatus {
    PENDING,
    POSTING,
    DELIVERED,
    CANCELLED,
}

data class NotificationOutboxRecord(
    val eventKey: String,
    val accountId: Long,
    val mediaId: Int,
    val eventKind: NotificationEventKind,
    val identityKey: String,
    val payload: String,
    val displayTitle: String?,
    val installment: Installment?,
    val track: LanguageTrack?,
    val evidenceKind: String,
    val observedAt: Instant,
    val status: NotificationOutboxStatus,
    val attemptCount: Int,
    val createdAt: Instant,
    val nextAttemptAt: Instant?,
    val postingLeaseUntil: Instant?,
    val deliveredAt: Instant?,
    val cancelledAt: Instant?,
    val lastError: String?,
)

const val MAX_NOTIFICATION_ATTEMPTS = 3
const val MAX_NOTIFICATION_ERROR_LENGTH = 512

fun ReleaseNotificationCandidate.toEntity(createdAt: Instant): NotificationOutboxEntity = NotificationOutboxEntity(
    eventKey = eventKey,
    accountId = accountId,
    mediaId = mediaId,
    eventKind = NotificationEventKind.CONFIRMATION.name,
    identityKey = identity.stableKey,
    payload = identity.stableKey,
    evidenceKind = evidenceKind.name,
    observedAt = observedAt.toString(),
    status = NotificationOutboxStatus.PENDING.name,
    attemptCount = 0,
    createdAt = createdAt.toString(),
    nextAttemptAt = null,
    postingLeaseUntil = null,
    deliveredAt = null,
    cancelledAt = null,
    lastError = null,
    displayTitle = displayTitle?.trim()?.takeIf { it.isNotBlank() },
    installmentPayload = encodeInstallment(identity.installment),
    track = identity.stream.languageTrack.name,
)

fun NotificationOutboxEntity.toDomainOrNull(): NotificationOutboxRecord? {
    if (eventKey.isBlank() || accountId <= 0L || mediaId <= 0 || identityKey.isBlank()) return null
    if (payload != identityKey || attemptCount !in 0..MAX_NOTIFICATION_ATTEMPTS) return null

    val parsedEventKind = runCatching { NotificationEventKind.valueOf(eventKind) }.getOrNull() ?: return null
    if (parsedEventKind != NotificationEventKind.CONFIRMATION) return null
    val parsedInstallment = installmentPayload?.let { decodeInstallment(it) ?: return null } ?: return null
    val parsedTrack = track?.let {
        runCatching { LanguageTrack.valueOf(it) }.getOrNull() ?: return null
    } ?: return null
    val parsedEvidence = runCatching {
        com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind.valueOf(evidenceKind)
    }.getOrNull() ?: return null
    val expectedIdentitySuffix = "/${parsedTrack.name}/${parsedInstallment.stableKey}"
    if (!identityKey.endsWith(expectedIdentitySuffix)) return null
    val expectedEventKey = listOf(
        "release-confirmation-v1",
        accountId.toString(),
        mediaId.toString(),
        identityKey,
        parsedEvidence.name,
    ).joinToString("::")
    if (eventKey != expectedEventKey) return null
    val parsedDisplayTitle = displayTitle?.trim()?.takeIf { it.isNotBlank() }
    val parsedStatus = runCatching { NotificationOutboxStatus.valueOf(status) }.getOrNull() ?: return null
    val parsedObservedAt = runCatching { Instant.parse(observedAt) }.getOrNull() ?: return null
    val parsedCreatedAt = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return null
    val parsedNextAttemptAt = nextAttemptAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (nextAttemptAt != null && parsedNextAttemptAt == null) return null
    val parsedLease = postingLeaseUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (postingLeaseUntil != null && parsedLease == null) return null
    val parsedDeliveredAt = deliveredAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (deliveredAt != null && parsedDeliveredAt == null) return null
    val parsedCancelledAt = cancelledAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (cancelledAt != null && parsedCancelledAt == null) return null

    when (parsedStatus) {
        NotificationOutboxStatus.PENDING -> {
            if (parsedLease != null || parsedDeliveredAt != null || parsedCancelledAt != null) return null
        }
        NotificationOutboxStatus.POSTING -> {
            if (parsedLease == null || parsedNextAttemptAt != null ||
                parsedDeliveredAt != null || parsedCancelledAt != null || attemptCount <= 0
            ) return null
        }
        NotificationOutboxStatus.DELIVERED -> {
            if (parsedDeliveredAt == null || parsedNextAttemptAt != null ||
                parsedLease != null || parsedCancelledAt != null
            ) return null
        }
        NotificationOutboxStatus.CANCELLED -> {
            if (parsedCancelledAt == null || parsedNextAttemptAt != null ||
                parsedLease != null || parsedDeliveredAt != null
            ) return null
        }
    }

    return NotificationOutboxRecord(
        eventKey = eventKey,
        accountId = accountId,
        mediaId = mediaId,
        eventKind = parsedEventKind,
        identityKey = identityKey,
        payload = payload,
        displayTitle = parsedDisplayTitle,
        installment = parsedInstallment,
        track = parsedTrack,
        evidenceKind = evidenceKind,
        observedAt = parsedObservedAt,
        status = parsedStatus,
        attemptCount = attemptCount,
        createdAt = parsedCreatedAt,
        nextAttemptAt = parsedNextAttemptAt,
        postingLeaseUntil = parsedLease,
        deliveredAt = parsedDeliveredAt,
        cancelledAt = parsedCancelledAt,
        lastError = lastError?.take(MAX_NOTIFICATION_ERROR_LENGTH),
    )
}

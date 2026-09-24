package com.axiel7.anihyou.release.core.notification

import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.ReleaseState
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.state.ReleaseProjector

enum class NotificationEventKind {
    CONFIRMATION,
}

enum class NotificationSuppressionReason {
    INITIAL_STATE,
    STREAM_CHANGED,
    INVALID_STATE,
    MEDIA_MAPPING_MISMATCH,
    INVALID_AUTHORITY,
    NO_NEW_CONFIRMATION,
}

data class ReleaseNotificationCandidate(
    val accountId: Long,
    val mediaId: Int,
    val identity: SourceIdentity,
    val evidenceKind: ConfirmationEvidenceKind,
    val observedAt: java.time.Instant,
    val displayTitle: String? = null,
) {
    init {
        require(accountId > 0L) { "account id must be positive" }
        require(mediaId > 0) { "media id must be positive" }
    }

    val eventKey: String = ReleaseNotificationPolicy.eventKey(
        accountId = accountId,
        mediaId = mediaId,
        identity = identity,
        evidenceKind = evidenceKind,
    )
}

sealed interface ReleaseNotificationDecision {
    data class Enqueue(
        val candidate: ReleaseNotificationCandidate,
    ) : ReleaseNotificationDecision

    data class Suppressed(
        val reason: NotificationSuppressionReason,
    ) : ReleaseNotificationDecision
}

object ReleaseNotificationPolicy {
    fun decide(
        previous: ReleaseState?,
        current: ReleaseState,
        accountId: Long,
        mediaId: Int,
        accountProgress: Int,
    ): List<ReleaseNotificationDecision> {
        require(accountId > 0L) { "account id must be positive" }
        require(mediaId > 0) { "media id must be positive" }
        require(accountProgress >= 0) { "account progress must be non-negative" }

        if (previous == null) return suppressed(NotificationSuppressionReason.INITIAL_STATE)
        if (previous.stream != current.stream) return suppressed(NotificationSuppressionReason.STREAM_CHANGED)
        val mapping = current.mapping ?: return suppressed(NotificationSuppressionReason.INVALID_AUTHORITY)
        if (mapping.mediaId != mediaId) return suppressed(NotificationSuppressionReason.MEDIA_MAPPING_MISMATCH)
        if (current.confirmations.any { it.identity.stream != current.stream } ||
            previous.confirmations.any { it.identity.stream != previous.stream }) {
            return suppressed(NotificationSuppressionReason.INVALID_STATE)
        }

        val projection = ReleaseProjector.project(current, accountProgress)
        if (projection.authority != AuthorityStatus.VALID) {
            return suppressed(NotificationSuppressionReason.INVALID_AUTHORITY)
        }

        val previousKeys = previous.confirmations.map(::confirmationKey).toSet()
        val newConfirmations = current.confirmations
            .filter { confirmationKey(it) !in previousKeys }
            .distinctBy(::confirmationKey)
            .sortedWith(compareBy<Confirmation> { it.confirmedObservedAt }.thenBy { it.identity.stableKey })
        if (newConfirmations.isEmpty()) return suppressed(NotificationSuppressionReason.NO_NEW_CONFIRMATION)

        return newConfirmations.map { confirmation ->
            ReleaseNotificationDecision.Enqueue(
                candidate = ReleaseNotificationCandidate(
                    accountId = accountId,
                    mediaId = mediaId,
                    identity = confirmation.identity,
                    evidenceKind = confirmation.evidenceKind,
                    observedAt = confirmation.confirmedObservedAt,
                ),
            )
        }
    }

    fun eventKey(
        accountId: Long,
        mediaId: Int,
        identity: SourceIdentity,
        evidenceKind: ConfirmationEvidenceKind,
    ): String {
        require(accountId > 0L) { "account id must be positive" }
        require(mediaId > 0) { "media id must be positive" }
        return listOf(
            "release-confirmation-v1",
            accountId.toString(),
            mediaId.toString(),
            identity.stableKey,
            evidenceKind.name,
        ).joinToString("::")
    }

    private fun confirmationKey(confirmation: Confirmation): String =
        confirmation.identity.stableKey + "::" + confirmation.evidenceKind.name

    private fun suppressed(reason: NotificationSuppressionReason): List<ReleaseNotificationDecision> =
        listOf(ReleaseNotificationDecision.Suppressed(reason))
}
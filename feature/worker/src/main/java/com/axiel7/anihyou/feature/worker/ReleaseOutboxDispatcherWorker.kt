package com.axiel7.anihyou.feature.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.utils.NotificationUtils.showNotification
import com.axiel7.anihyou.release.core.api.ReleaseDeliveryState
import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.api.ReleaseNotificationGate
import com.axiel7.anihyou.release.core.api.ReleaseOutboxRepository
import com.axiel7.anihyou.release.core.api.ReleasePreferencesRepository
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

class ReleaseOutboxDispatcherWorker(
    context: Context,
    params: WorkerParameters,
    private val releaseOutboxRepository: ReleaseOutboxRepository,
    private val releaseNotificationGate: ReleaseNotificationGate,
    private val releasePreferencesRepository: ReleasePreferencesRepository,
    private val defaultPreferencesRepository: DefaultPreferencesRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        val now = clock.instant()
        val preferences = releasePreferencesRepository.releasePreferences.first()
        if (preferences.selectedProvider != PROVIDER_ID) {
            releaseOutboxRepository.cancelPendingForProvider(
                providerId = PROVIDER_ID,
                reason = "provider disabled",
                cancelledAt = now,
            )
            return Result.success()
        }
        if (!preferences.notificationsEnabled) {
            releaseOutboxRepository.cancelPendingForProvider(
                providerId = PROVIDER_ID,
                reason = "release notifications disabled",
                cancelledAt = now,
            )
            return Result.success()
        }

        if (defaultPreferencesRepository.userId.firstOrNull() == null) return Result.retry()
        var retryNeeded = false
        releaseOutboxRepository.observeDue(now).first().forEach { pending ->
            val activeAccountId = defaultPreferencesRepository.userId.firstOrNull()?.toLong()
            if (activeAccountId == null) {
                retryNeeded = true
                return@forEach
            }
            if (pending.accountId != activeAccountId) {
                releaseOutboxRepository.cancel(
                    eventKey = pending.eventKey,
                    reason = "account no longer active",
                    cancelledAt = now,
                )
                return@forEach
            }
            val claimed = releaseOutboxRepository.claim(pending.eventKey, now)
                ?: return@forEach
            if (claimed.state != ReleaseDeliveryState.POSTING) return@forEach

            val accountAfterClaim = defaultPreferencesRepository.userId.firstOrNull()?.toLong()
            if (accountAfterClaim == null) {
                releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(PERMISSION_RETRY_SECONDS),
                    error = "account temporarily unavailable",
                )
                retryNeeded = true
                return@forEach
            }
            if (accountAfterClaim != claimed.accountId) {
                releaseOutboxRepository.cancel(
                    eventKey = claimed.eventKey,
                    reason = "account changed before delivery",
                    cancelledAt = now,
                )
                return@forEach
            }
            val currentPreferences = releasePreferencesRepository.releasePreferences.first()
            if (currentPreferences.selectedProvider != PROVIDER_ID ||
                !currentPreferences.notificationsEnabled
            ) {
                releaseOutboxRepository.cancel(
                    eventKey = claimed.eventKey,
                    reason = "release delivery preference changed",
                    cancelledAt = now,
                )
                return@forEach
            }
            if (claimed.track != currentPreferences.preferredTrack.toLanguageTrack()) {
                releaseOutboxRepository.cancel(
                    eventKey = claimed.eventKey,
                    reason = "release language track changed",
                    cancelledAt = now,
                )
                return@forEach
            }

            if (!canPostNotifications()) {
                releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(PERMISSION_RETRY_SECONDS),
                    error = "notification permission unavailable",
                )
                retryNeeded = true
                return@forEach
            }

            val deliveryAllowed = runCatching {
                releaseNotificationGate.allowReleaseDelivery(
                    accountId = claimed.accountId,
                    mediaId = claimed.mediaId,
                    identityKey = claimed.identityKey,
                    installment = claimed.installment,
                )
            }.getOrElse { error ->
                releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(retryDelaySeconds(claimed.attemptCount)),
                    error = error.message?.take(512).orEmpty().ifBlank {
                        "release notification revalidation failed"
                    },
                )
                retryNeeded = true
                return@forEach
            }
            if (!deliveryAllowed) {
                releaseOutboxRepository.cancel(
                    eventKey = claimed.eventKey,
                    reason = "release no longer eligible",
                    cancelledAt = now,
                )
                return@forEach
            }

            val accountBeforePost = defaultPreferencesRepository.userId.firstOrNull()?.toLong()
            if (accountBeforePost == null) {
                releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(PERMISSION_RETRY_SECONDS),
                    error = "account temporarily unavailable",
                )
                retryNeeded = true
                return@forEach
            }
            if (accountBeforePost != claimed.accountId) {
                releaseOutboxRepository.cancel(
                    eventKey = claimed.eventKey,
                    reason = "account changed before Android post",
                    cancelledAt = now,
                )
                return@forEach
            }

            try {
                applicationContext.showNotification(
                    notificationId = com.axiel7.anihyou.release.core.notification.ReleaseNotificationId
                        .fromEventKey(claimed.eventKey),
                    channelId = AIRING_CHANNEL_ID,
                    title = applicationContext.getString(R.string.notifications_airing),
                    text = applicationContext.getString(
                        R.string.release_notification_text,
                        claimed.displayTitle
                            ?: applicationContext.getString(R.string.release_notification_unknown_title),
                        claimed.installment.notificationLabel(applicationContext),
                    ),
                    group = "airing",
                )
                if (!releaseOutboxRepository.markDelivered(claimed.eventKey, clock.instant())) {
                    retryNeeded = true
                }
            } catch (error: Exception) {
                releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(retryDelaySeconds(claimed.attemptCount)),
                    error = error.message?.take(512).orEmpty().ifBlank {
                        "release notification post failed"
                    },
                )
                retryNeeded = true
            }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    private fun retryDelaySeconds(attemptCount: Int): Long = when (attemptCount) {
        1 -> 60L
        2 -> 5L * 60L
        else -> 15L * 60L
    }

    companion object {
        const val WORK_NAME = "release_outbox_dispatcher"
        private const val PROVIDER_ID = "aniworld"
        private const val AIRING_CHANNEL_ID = "airing_channel_id"
        private const val PERMISSION_RETRY_SECONDS = 15L * 60L

        fun WorkManager.scheduleReleaseOutboxWork() {
            val request = OneTimeWorkRequestBuilder<ReleaseOutboxDispatcherWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    10L,
                    TimeUnit.MINUTES,
                )
                .build()
            enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

internal fun ReleaseGermanTrack.toLanguageTrack(): LanguageTrack = when (this) {
    ReleaseGermanTrack.DE_SUB -> LanguageTrack.DE_SUB
    ReleaseGermanTrack.DE_DUB -> LanguageTrack.DE_DUB
}

private fun Installment?.notificationLabel(context: Context): String = when (this) {
    is Installment.Episode -> context.getString(
        R.string.release_notification_episode,
        if (fraction == null) number.toString() else "$number.$fraction",
    )
    is Installment.Film -> context.getString(R.string.release_notification_film)
    is Installment.Special -> context.getString(R.string.release_notification_special)
    null -> context.getString(R.string.release_notification_unknown_installment)
}

package com.axiel7.anihyou.feature.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import com.axiel7.anihyou.core.base.APP_PACKAGE_NAME
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaRepository
import com.axiel7.anihyou.core.ui.utils.ImageUtils.getBitmapFromUrl
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import com.axiel7.anihyou.core.model.DeepLink
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.utils.NotificationUtils.showNotification
import com.axiel7.anihyou.release.core.api.ReleaseDeliveryState
import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.api.ReleaseNotificationGate
import com.axiel7.anihyou.release.core.api.ReleaseDeliveryDecision
import com.axiel7.anihyou.release.core.api.ReleaseOutboxRepository
import com.axiel7.anihyou.release.core.api.ReleasePreferencesRepository
import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.feature.worker.NotificationWorker.Companion.createDefaultNotificationChannels
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
    private val mediaRepository: MediaRepository,
    private val productPolicy: ExtensionProductPolicyRepository,
) : CoroutineWorker(context, params) {

    /** The user chose a data source in the extension center, or switched the old provider lane on. Either owns release delivery. */
    private fun releaseDeliveryActive(selectedProvider: String?): Boolean =
        selectedProvider == PROVIDER_ID || productPolicy.policy.value.activeReleaseSource != null

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        val now = clock.instant()
        // The channel is created here as well: it used to exist only after AniList push notifications had been switched on,
        // and Android drops a notification for a channel that does not exist.
        applicationContext.createDefaultNotificationChannels()
        val preferences = releasePreferencesRepository.releasePreferences.first()
        AppLog.i("notification") {
            "dispatcher start: lane=${releaseDeliveryActive(preferences.selectedProvider)} legacyProvider=${preferences.selectedProvider} " +
                "extensionSource=${productPolicy.policy.value.activeReleaseSource != null} enabled=${preferences.notificationsEnabled} " +
                "track=${preferences.preferredTrack} permission=${canPostNotifications()}"
        }
        if (!releaseDeliveryActive(preferences.selectedProvider)) {
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
        val due = releaseOutboxRepository.observeDue(now).first()
        AppLog.i("notification") { "dispatcher: ${due.size} notification(s) due" }
        due.forEach { pending ->
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
            // The optional image may suspend. Revalidate preferences and release eligibility after it completes.
            val cover = try {
                // The details are usually not cached for an anime the user never opened: one small request gets the cover.
                withTimeoutOrNull(COVER_TIMEOUT_MILLIS) {
                    mediaRepository.mediaPreview(claimed.mediaId)?.coverUrl?.let { url ->
                        applicationContext.getBitmapFromUrl(url)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }

            val currentPreferences = releasePreferencesRepository.releasePreferences.first()
            if (!releaseDeliveryActive(currentPreferences.selectedProvider) ||
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
                AppLog.w("notification") { "not posted media=${claimed.mediaId}: notification permission or app notifications are off" }
                releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(PERMISSION_RETRY_SECONDS),
                    error = "notification permission unavailable",
                )
                retryNeeded = true
                return@forEach
            }

            val deliveryAllowed = runCatching {
                releaseNotificationGate.evaluateReleaseDelivery(
                    accountId = claimed.accountId,
                    mediaId = claimed.mediaId,
                    identityKey = claimed.identityKey,
                    installment = claimed.installment,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
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
            if (deliveryAllowed == ReleaseDeliveryDecision.UNKNOWN) {
                val deferredState = releaseOutboxRepository.reschedulePending(
                    eventKey = claimed.eventKey,
                    nextAttemptAt = now.plusSeconds(retryDelaySeconds(claimed.attemptCount)),
                    error = "release progress temporarily unknown",
                )
                // Persistence applies its existing retry cap; do not keep waking after its terminal cancellation.
                retryNeeded = retryNeeded || deferredState == ReleaseDeliveryState.PENDING
                return@forEach
            }
            if (deliveryAllowed == ReleaseDeliveryDecision.INELIGIBLE) {
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
                val notificationId = com.axiel7.anihyou.release.core.notification.ReleaseNotificationId
                    .fromEventKey(claimed.eventKey)
                val posted = applicationContext.showNotification(
                    notificationId = notificationId,
                    channelId = AIRING_CHANNEL_ID,
                    title = applicationContext.getString(R.string.release_notification_title),
                    text = applicationContext.getString(
                        R.string.release_notification_text,
                        claimed.displayTitle
                            ?: applicationContext.getString(R.string.release_notification_unknown_title),
                        claimed.installment.notificationLabel(applicationContext),
                    ),
                    largeIcon = cover,
                    bigPicture = cover,
                    pendingIntent = mediaDetailsIntent(notificationId, claimed.mediaId),
                    group = "airing",
                )
                if (!posted) {
                    val deferredState = releaseOutboxRepository.reschedulePending(
                        eventKey = claimed.eventKey,
                        nextAttemptAt = now.plusSeconds(retryDelaySeconds(claimed.attemptCount)),
                        error = "Android did not post release notification",
                    )
                    retryNeeded = retryNeeded || deferredState == ReleaseDeliveryState.PENDING
                    return@forEach
                }
                AppLog.i("notification") { "posted media=${claimed.mediaId} installment=${claimed.installment} id=$notificationId" }
                if (!releaseOutboxRepository.markDelivered(claimed.eventKey, clock.instant())) {
                    retryNeeded = true
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
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

    /** A tap opens the media's details, the same target the AniList airing notification uses. */
    private fun mediaDetailsIntent(notificationId: Int, mediaId: Int): PendingIntent? = runCatching {
        val target = releaseTapTarget(mediaId)
        applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.apply {
            action = target.action
            putExtra("content_id", target.contentId)
            // The widget flag keeps the tap from marking the user's AniList notifications as read.
            putExtra("widget", target.opensFromOwnSurface)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }?.let { intent ->
            // One request code per notification: extras alone do not make two pending intents different.
            PendingIntent.getActivity(applicationContext, notificationId, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }.getOrNull()

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
        private const val COVER_TIMEOUT_MILLIS = 8_000L
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
            // APPEND_OR_REPLACE: a new delivery is not hidden behind a run that waits in its backoff, and a run that is
            // posting right now is not cancelled.
            enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** What a tap on a release notification asks the main activity for; kept free of Android types so a test can pin it. */
internal data class ReleaseTapTarget(val action: String, val contentId: String, val opensFromOwnSurface: Boolean)

internal fun releaseTapTarget(mediaId: Int) = ReleaseTapTarget(
    action = DeepLink.Type.ANIME.intentAction,
    contentId = mediaId.toString(),
    opensFromOwnSurface = true,
)

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

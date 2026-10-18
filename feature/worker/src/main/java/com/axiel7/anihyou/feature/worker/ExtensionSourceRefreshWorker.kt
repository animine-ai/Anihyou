package com.axiel7.anihyou.feature.worker

import com.axiel7.anihyou.release.core.log.AppLog
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.core.ui.utils.NotificationUtils.createNotificationChannel
import com.axiel7.anihyou.core.ui.utils.NotificationUtils.showNotification
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import kotlinx.coroutines.CancellationException

enum class ExtensionSourceRefreshDecision { SUCCESS, RETRY, FAILURE }

internal suspend fun evaluateExtensionSourceRefresh(
    repository: ExtensionSourceRepository,
    runAttemptCount: Int,
): ExtensionSourceRefreshDecision {
    val needsRetry = try {
        repository.refreshEnabled()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    if (!needsRetry) return ExtensionSourceRefreshDecision.SUCCESS
    return if (runAttemptCount + 1 < ExtensionSourceRefreshWorker.MAX_ATTEMPTS) {
        ExtensionSourceRefreshDecision.RETRY
    } else {
        ExtensionSourceRefreshDecision.FAILURE
    }
}

/** Retries transient or unexpected failures at most three times in total. */
class ExtensionSourceRefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: ExtensionSourceRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppLog.i("worker") { "source refresh start attempt=$runAttemptCount" }
        val decision = evaluateExtensionSourceRefresh(repository, runAttemptCount)
        AppLog.i("worker") { "source refresh decision=$decision attempt=$runAttemptCount" }
        if (decision == ExtensionSourceRefreshDecision.SUCCESS) installUpdates()
        return when (decision) {
            ExtensionSourceRefreshDecision.SUCCESS -> Result.success()
            ExtensionSourceRefreshDecision.RETRY -> Result.retry()
            ExtensionSourceRefreshDecision.FAILURE -> Result.failure()
        }
    }

    /** Installs a newer signed package of an installed extension and says so, also when it could not be installed. */
    private suspend fun installUpdates() {
        val outcomes = try {
            installAvailableExtensionUpdates(repository, SharedPreferencesExtensionUpdateLedger(applicationContext))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("worker", error) { "extension update check failed" }
            return
        }
        outcomes.forEach { outcome ->
            runCatching {
                if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
                ) {
                    AppLog.i("notification") { "extension update ${outcome.displayName}: notifications are off, nothing posted" }
                    return@runCatching
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.createNotificationChannel(
                        id = UPDATE_CHANNEL_ID, name = applicationContext.getString(R.string.extension_update_channel),
                    )
                }
                val opensApp = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
                    ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
                    ?.let { PendingIntent.getActivity(applicationContext, UPDATE_NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE) }
                val posted = applicationContext.showNotification(
                    notificationId = UPDATE_NOTIFICATION_ID + (outcome.displayName.hashCode() and 0xff),
                    channelId = UPDATE_CHANNEL_ID,
                    title = applicationContext.getString(
                        if (outcome.installed) R.string.extension_update_installed_title else R.string.extension_update_failed_title,
                    ),
                    text = applicationContext.getString(
                        if (outcome.installed) R.string.extension_update_installed_text else R.string.extension_update_failed_text,
                        outcome.displayName, outcome.toVersion,
                    ),
                    pendingIntent = opensApp,
                )
                AppLog.i("notification") { "extension update ${outcome.displayName} ${outcome.toVersion}: installed=${outcome.installed} posted=$posted" }
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val UPDATE_CHANNEL_ID = "extension_update_channel_id"
        private const val UPDATE_NOTIFICATION_ID = 7300
    }
}

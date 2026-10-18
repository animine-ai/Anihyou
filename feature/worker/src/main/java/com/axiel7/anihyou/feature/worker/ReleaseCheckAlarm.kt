package com.axiel7.anihyou.feature.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Constraints
import androidx.work.NetworkType
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.log.AppLog
import java.time.Instant

/**
 * Wakes the app at the planned time of a release check. A delayed work of WorkManager waits for the next maintenance window
 * of an idle phone: the check planned for 20:10 ran at 20:24, when the screen was switched on, and the release it was meant to
 * announce came 14 minutes late. An alarm that is allowed while idle fires near its time; it needs no permission, is not exact
 * (an idle phone lets an app have one about every nine minutes, which the spacing of the checks respects) and only starts
 * the check, which then runs as expedited work.
 */
interface DueCheckAlarms {
    /** A release check at [at], planned from the planned time of an episode. */
    fun setDueCheck(at: Instant)

    /** The refresh slot at [at] of the schedule the user chose. */
    fun setSlot(at: Instant)

    object None : DueCheckAlarms {
        override fun setDueCheck(at: Instant) = Unit
        override fun setSlot(at: Instant) = Unit
    }
}

class AndroidDueCheckAlarms(private val context: Context) : DueCheckAlarms {
    override fun setDueCheck(at: Instant) = set(at, ReleaseCheckAlarmReceiver.KIND_DUE)
    override fun setSlot(at: Instant) = set(at, ReleaseCheckAlarmReceiver.KIND_SLOT)

    private fun set(at: Instant, kind: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReleaseCheckAlarmReceiver::class.java).setAction(ReleaseCheckAlarmReceiver.ACTION)
            .putExtra(ReleaseCheckAlarmReceiver.EXTRA_KIND, kind)
            .putExtra(ReleaseCheckAlarmReceiver.EXTRA_AT, at.epochSecond)
        // One alarm per kind and minute: planning the same time again replaces it instead of adding a second one.
        val pending = PendingIntent.getBroadcast(
            context, (at.epochSecond / 60L).toInt() * 2 + if (kind == ReleaseCheckAlarmReceiver.KIND_SLOT) 1 else 0,
            intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // An exact alarm that may fire while the phone idles starts the check at its minute: the inexact one can wait for
        // the app's standby bucket (a rarely used app gets very few of them). Without the permission it stays inexact.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
            AppLog.d("worker") { "alarm armed: $kind at $at exact=$exact" }
        }.onFailure { AppLog.w("worker", it) { "alarm for the $kind at $at could not be set" } }
    }
}

/**
 * The alarm arrived: the check or the slot runs now, expedited, so it starts at once although the phone has just woken up.
 * The delayed work of the same name that WorkManager still holds is replaced by it.
 */
class ReleaseCheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val slot = intent.getStringExtra(EXTRA_KIND) == KIND_SLOT
        val at = intent.getLongExtra(EXTRA_AT, 0L)
        AppLog.i("worker") { "release alarm fired: ${if (slot) "refresh slot" else "due-release check"} planned for epoch $at starts now" }
        runCatching {
            val scheduler = WorkManagerExtensionReleaseRefreshScheduler
            val builder = OneTimeWorkRequestBuilder<ExtensionReleaseRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(
                    ExtensionReleaseRefreshWorker.INPUT_TRIGGER to
                        (if (slot) ExtensionRefreshTrigger.SCHEDULED_SLOT else ExtensionRefreshTrigger.DUE_RELEASE).name,
                    ExtensionReleaseRefreshWorker.INPUT_CHAIN to 0,
                ))
                .addTag(scheduler.TAG)
            if (slot) builder.addTag(scheduler.SLOT_TAG).addTag("${scheduler.SLOT_TAG_PREFIX}$at")
            else builder.addTag(scheduler.DUE_RELEASE_TAG)
            val name = if (slot) "${scheduler.SLOT}-$at" else "${scheduler.DUE_RELEASE}-$at"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, builder.build())
        }.onFailure { AppLog.w("worker", it) { "release work could not be started by its alarm" } }
    }

    companion object {
        const val ACTION = "com.axiel7.anihyou.action.RELEASE_CHECK_ALARM"
        const val EXTRA_KIND = "kind"
        const val EXTRA_AT = "at"
        const val KIND_DUE = "due"
        const val KIND_SLOT = "slot"
    }
}

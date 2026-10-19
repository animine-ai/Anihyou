package com.axiel7.anihyou.feature.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.axiel7.anihyou.release.core.log.AppLog

/**
 * The phone started, the app was updated or the clock or the time zone changed. Alarms do not survive a restart or an update, and
 * a changed clock moves every planned time. This receiver starts the process; the application then plans the slot, the
 * periodic backbone, the planned release checks and their alarms again, the way it does at every start.
 */
class ReleaseRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLog.i("worker") { "re-arm: ${intent.action}, the planned checks and alarms are set again by the process start" }
    }
}

package com.axiel7.anihyou.feature.settings.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Reads and switches the icon of the app on the home screen. */
class AppIconManager(private val context: Context) {
    private val packageManager get() = context.packageManager

    private fun component(icon: AppIcon) = ComponentName(context, icon.className)

    fun current(): AppIcon =
        AppIcon.current { icon -> packageManager.getComponentEnabledSetting(component(icon)) }

    /** Returns false when the system refused; the icon then stays as it was or the default one remains enabled. */
    fun select(icon: AppIcon): Boolean = runCatching {
        AppIcon.switchPlan(icon).forEach { (entry, enabled) ->
            packageManager.setComponentEnabledSetting(
                component(entry),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                // Without this flag the system closes the app when its entry changes.
                PackageManager.DONT_KILL_APP,
            )
        }
    }.isSuccess
}

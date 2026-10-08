package com.axiel7.anihyou.feature.settings.appicon

import android.content.pm.PackageManager

/**
 * The icons the app can have on the home screen. Each one is an entry of its own in the manifest (an activity alias of
 * the main activity); exactly one of them is enabled, and the launcher shows that one. What each icon looks like in the
 * settings is in [AppIconDialog], so this model stays free of resources.
 */
enum class AppIcon(
    /** The name of the alias in the manifest. */
    val aliasName: String,
) {
    KIYORI("Kiyori"),
    KIYORI_LIGHT("KiyoriLight"),
    ARCTIC("Arctic"),
    ARCTIC_CALENDAR("ArcticCalendar"),
    ARCTIC_MOON("ArcticMoon"),
    ARCTIC_LIST("ArcticList"),
    CLASSIC("Classic");

    /** The class name of the alias, as the package manager knows it. */
    val className: String get() = "$ALIAS_PACKAGE.$aliasName"

    companion object {
        /** The icon the manifest enables. */
        val DEFAULT = KIYORI
        private const val ALIAS_PACKAGE = "com.axiel7.anihyou.launcher"

        /**
         * The icon on the home screen, from the component states of the aliases. A state that was never set ("default")
         * is what the manifest says: only the default icon is on.
         */
        fun current(stateOf: (AppIcon) -> Int): AppIcon =
            entries.firstOrNull { icon ->
                val state = stateOf(icon)
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == DEFAULT)
            } ?: DEFAULT

        /**
         * The changes that switch to [target], in the order to apply them: the new entry is enabled first, so the app
         * never has no entry on the home screen, then every other entry is disabled.
         */
        fun switchPlan(target: AppIcon): List<Pair<AppIcon, Boolean>> =
            listOf(target to true) + entries.filter { it != target }.map { it to false }
    }
}

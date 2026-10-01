package com.axiel7.anihyou.core.ui.common.navigation

import android.content.Context
import com.axiel7.anihyou.core.ui.common.BottomDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only catalog IDs are persisted. Route arguments, details and settings cannot be promoted. */
data class MainNavigationConfig(val visibleIds: List<String> = defaults) {
    fun normalized(): MainNavigationConfig {
        val known = visibleIds.filter { id -> BottomDestination.catalog.any { it.stableId == id && it.topLevelEligible } }
            .distinct().take(5).toMutableList()
        if ("home" !in known) {
            if (known.size == 5) known.removeAt(known.lastIndex)
            known.add("home")
        }
        return MainNavigationConfig(known)
    }

    fun move(id: String, offset: Int): MainNavigationConfig {
        val ids = normalized().visibleIds.toMutableList()
        val index = ids.indexOf(id)
        if (index < 0) return normalized()
        ids.removeAt(index)
        ids.add((index + offset).coerceIn(0, ids.size), id)
        return MainNavigationConfig(ids)
    }

    fun show(id: String): MainNavigationConfig =
        if (visibleIds.size >= 5 || id in visibleIds) normalized()
        else MainNavigationConfig(visibleIds + id).normalized()

    fun hide(id: String): MainNavigationConfig =
        if (id == "home") normalized() else MainNavigationConfig(visibleIds - id).normalized()

    companion object {
        val defaults = listOf("home", "anime", "manga", "explore", "calendar")
    }
}

object MainNavigationConfigCodec {
    fun encode(config: MainNavigationConfig) = "v1;" + config.normalized().visibleIds.joinToString(",")
    fun decode(raw: String?): MainNavigationConfig {
        if (raw.isNullOrBlank() || raw.length > 4096) return MainNavigationConfig()
        // Earlier optional editors used v2/v3/v4 with id:visibility. Preserve valid custom order.
        val prefix = raw.substringBefore(';')
        val body = raw.substringAfter(';', "")
        if (prefix == "v1") return MainNavigationConfig(body.split(',')).normalized()
        if (prefix in setOf("v2", "v3", "v4")) {
            val ids = body.split(',').mapNotNull {
                val pair = it.split(':')
                if (pair.size == 2 && pair[1] == "1") pair[0] else null
            }
            val legacyDefault = listOf("home", "anime", "manga", "profile", "explore")
            return if (ids == legacyDefault) MainNavigationConfig() else MainNavigationConfig(ids).normalized()
        }
        return MainNavigationConfig()
    }
}

object MainNavigationResolver {
    /** The complete ordered universe is constant, independent of visibility, season or login. */
    val allRoutes: Set<Route> = BottomDestination.catalog.mapTo(linkedSetOf()) { it.route }
    fun destinations(config: MainNavigationConfig): List<BottomDestination> =
        config.normalized().visibleIds.map { id -> BottomDestination.catalog.single { it.stableId == id } }
    fun visibleRoot(active: Route, config: MainNavigationConfig): Route =
        if (destinations(config).any { it.route == active }) active else Route.Home
}

/** One instance shared by the editor and both navigation surfaces. */
class MainNavigationConfigStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("main-navigation", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(MainNavigationConfigCodec.decode(preferences.getString("config", null)))
    val config = mutable.asStateFlow()

    @Synchronized
    fun update(transform: (MainNavigationConfig) -> MainNavigationConfig): Boolean {
        val next = transform(mutable.value).normalized()
        if (!preferences.edit().putString("config", MainNavigationConfigCodec.encode(next)).commit()) return false
        mutable.value = next
        return true
    }

    companion object {
        @Volatile private var instance: MainNavigationConfigStore? = null
        fun get(context: Context): MainNavigationConfigStore = instance ?: synchronized(this) {
            instance ?: MainNavigationConfigStore(context.applicationContext).also { instance = it }
        }
    }
}

package com.axiel7.anihyou.release.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.axiel7.anihyou.release.core.api.NotificationListStatus
import com.axiel7.anihyou.release.core.api.NotificationScopeMode
import com.axiel7.anihyou.release.core.api.ReleaseNotificationScope
import com.axiel7.anihyou.release.core.api.ReleaseNotificationScopeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The notification scope in a small private preferences file: four values, read once, written on change. */
class SharedPreferencesNotificationScopeRepository(
    private val preferences: SharedPreferences,
) : ReleaseNotificationScopeRepository {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val mutable = MutableStateFlow(read())
    override val scope: StateFlow<ReleaseNotificationScope> = mutable.asStateFlow()

    override suspend fun update(transform: (ReleaseNotificationScope) -> ReleaseNotificationScope) {
        withContext(Dispatchers.IO) {
            synchronized(this@SharedPreferencesNotificationScopeRepository) {
                val next = transform(mutable.value)
                if (next == mutable.value) return@synchronized
                preferences.edit()
                    .putString(MODE, next.mode.name)
                    .putString(STATUSES, next.statuses.joinToString(",") { it.name })
                    .putBoolean(NOT_ON_LIST, next.includeNotOnList)
                    .putBoolean(HIDDEN, next.includeHidden)
                    .apply()
                mutable.value = next
            }
        }
    }

    private fun read(): ReleaseNotificationScope {
        val defaults = ReleaseNotificationScope()
        val mode = preferences.getString(MODE, null)?.let { runCatching { NotificationScopeMode.valueOf(it) }.getOrNull() }
            ?: defaults.mode
        val statuses = preferences.getString(STATUSES, null)?.split(',')?.mapNotNull { name ->
            runCatching { NotificationListStatus.valueOf(name) }.getOrNull()
        }?.toSet() ?: defaults.statuses
        return ReleaseNotificationScope(
            mode = mode,
            statuses = statuses,
            includeNotOnList = preferences.getBoolean(NOT_ON_LIST, defaults.includeNotOnList),
            includeHidden = preferences.getBoolean(HIDDEN, defaults.includeHidden),
        )
    }

    private companion object {
        const val FILE = "release_notification_scope"
        const val MODE = "mode"
        const val STATUSES = "statuses"
        const val NOT_ON_LIST = "not_on_list"
        const val HIDDEN = "hidden"
    }
}

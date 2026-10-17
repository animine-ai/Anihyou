package com.axiel7.anihyou.release.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.axiel7.anihyou.release.core.api.ReleaseMediaArtStore

/** Cover URLs of the anime that were announced, the newest [CAPACITY] of them. */
class SharedPreferencesReleaseMediaArtStore(context: Context) : ReleaseMediaArtStore {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun remember(mediaId: Int, coverUrl: String) {
        if (mediaId <= 0 || coverUrl.isBlank() || coverUrl.length > MAX_URL) return
        val order = order().filterNot { it == mediaId } + mediaId
        val dropped = order.dropLast(CAPACITY)
        preferences.edit().apply {
            dropped.forEach { remove(key(it)) }
            putString(key(mediaId), coverUrl)
            putString(ORDER, order.takeLast(CAPACITY).joinToString(","))
        }.apply()
    }

    override fun coverUrl(mediaId: Int): String? = preferences.getString(key(mediaId), null)?.takeIf { it.isNotBlank() }

    private fun order(): List<Int> =
        preferences.getString(ORDER, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    private fun key(mediaId: Int) = "cover:$mediaId"

    private companion object {
        const val NAME = "release_media_art"
        const val ORDER = "order"
        const val CAPACITY = 200
        const val MAX_URL = 2048
    }
}

package com.axiel7.anihyou

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.axiel7.anihyou.core.domain.tracker.TrackerRange
import com.axiel7.anihyou.core.domain.tracker.TrackerSnapshotStore
import java.io.File

/**
 * Permission-protected local tracker contract. This process reads a credential-free snapshot only: it does not
 * initialise Koin, an HTTP client, a worker or the ordinary application startup. No write operations are exposed.
 */
class TrackerProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = requireNotNull(context)
        ctx.enforceCallingPermission(ctx.packageName + ".permission.READ_TRACKER_DATA", "Tracker read permission required")
        require(uri.authority == ctx.packageName + ".tracker" && uri.pathSegments.size == 1)
        require(selection == null && selectionArgs.isNullOrEmpty() && sortOrder == null) { "SQL is not supported" }
        val path = uri.lastPathSegment
        val allowed = when (path) { "watching" -> WATCHING; "airing" -> AIRING; else -> throw IllegalArgumentException("Unknown tracker path") }
        val columns = projection?.toList() ?: allowed
        require(columns.all { it in allowed } && columns.distinct().size == columns.size) { "Unknown projection" }
        val cursor = MatrixCursor(columns.toTypedArray())
        fun status(value: String, cachedAt: Long = 0) = cursor.apply {
            extras = Bundle().apply { putString("tracker_status", value); putLong("cached_at_millis", cachedAt); putString("scope", "watching"); putInt("contract_version", 1) }
        }
        val range = if (path == "airing") TrackerRange.parse(uri.getQueryParameter("from"), uri.getQueryParameter("to")) else null
        if (path == "airing" && (range == null || uri.getQueryParameter("mine") != "1")) return status("unsupported")
        val snapshot = runCatching { TrackerSnapshotStore(File(ctx.noBackupFilesDir, "tracker-share")).read() }.getOrNull()
            ?: return status("cache_missing")
        if (snapshot.accountId == null) return status("signed_out")
        if (!snapshot.complete) return status("cache_missing", snapshot.cachedAtMillis)
        snapshot.entries.forEach { entry ->
            val airingAt = entry.nextAiringAt
            if (path == "airing" && (airingAt == null || entry.nextEpisode == null || !range!!.contains(airingAt))) return@forEach
            val data = mapOf<String, Any?>(
                "media_id" to entry.mediaId, "title_romaji" to entry.romaji, "title_english" to entry.english,
                "cover_url" to entry.cover, "progress" to entry.progress, "episodes_total" to entry.total,
                "latest_episode" to entry.latest, "next_episode" to entry.nextEpisode, "next_airing_at" to entry.nextAiringAt,
                "episode" to entry.nextEpisode, "airing_at" to entry.nextAiringAt, "in_my_list" to 1,
                "list_status" to entry.status, "site_url" to "https://anilist.co/anime/${entry.mediaId}",
            )
            cursor.addRow(columns.map(data::get).toTypedArray())
        }
        return status("ok", snapshot.cachedAtMillis)
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        "watching", "airing" -> "vnd.android.cursor.dir/vnd.tracker.${uri.lastPathSegment}"
        else -> null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    companion object {
        private val WATCHING = listOf("media_id", "title_romaji", "title_english", "cover_url", "progress", "episodes_total", "latest_episode", "next_episode", "next_airing_at", "site_url")
        private val AIRING = listOf("media_id", "title_romaji", "title_english", "cover_url", "episode", "airing_at", "in_my_list", "list_status", "site_url")
    }
}

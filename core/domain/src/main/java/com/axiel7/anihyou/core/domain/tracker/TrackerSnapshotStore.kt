package com.axiel7.anihyou.core.domain.tracker

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A deliberately small, credential-free snapshot of data already read by the normal list screens. */
@Serializable
data class TrackerEntry(
    val mediaId: Int, val romaji: String?, val english: String?, val cover: String?,
    val progress: Int, val total: Int?, val latest: Int?, val nextEpisode: Int?,
    val nextAiringAt: Long?, val status: String,
)

@Serializable
data class TrackerSnapshot(
    val accountId: Int? = null, val cachedAtMillis: Long = 0,
    val pages: Map<Int, List<TrackerEntry>> = emptyMap(), val lastPage: Int? = null,
) {
    val complete: Boolean get() = lastPage?.let { it in 1..MAX_PAGES && (1..it).all(pages::containsKey) } == true
    val entries: List<TrackerEntry> get() = pages.toSortedMap().values.flatten().distinctBy { it.mediaId }
    companion object { const val MAX_PAGES = 20 }
}

/**
 * Shared by the ordinary app and its read-only provider process. Atomic rename and a cross-process file lock make
 * account changes and page publication one operation. In-flight results for a previous account are rejected.
 * No tokens, notes, network calls, scheduler or second account database.
 */
class TrackerSnapshotStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshotFile get() = File(root, "snapshot.json")

    fun read(): TrackerSnapshot? = locked { readLocked() }

    fun switchAccount(accountId: Int?) = locked {
        val previous = readLocked()
        if (accountId == null || previous?.accountId != accountId) writeLocked(TrackerSnapshot(accountId = accountId))
    }

    /** Only bootstrap an older installation once; an explicit signed-out marker must never be overwritten. */
    fun initializeIfMissing(accountId: Int) = locked {
        if (!snapshotFile.exists()) writeLocked(TrackerSnapshot(accountId = accountId))
    }

    fun recordPage(accountId: Int, page: Int, hasNextPage: Boolean, entries: List<TrackerEntry>, now: Long = System.currentTimeMillis()) = locked {
        val old = readLocked() ?: return@locked false
        if (old.accountId != accountId || page !in 1..TrackerSnapshot.MAX_PAGES || entries.size > 1000) return@locked false
        val pages = (if (page == 1) emptyMap() else old.pages).toMutableMap()
        pages[page] = entries
        if (pages.values.sumOf { it.size } > 1000) return@locked false
        val last = if (!hasNextPage) page else if (page == 1) null else old.lastPage
        writeLocked(TrackerSnapshot(accountId, now, pages, last))
        true
    }

    private fun readLocked(): TrackerSnapshot? {
        if (!snapshotFile.isFile || snapshotFile.length() > 2_000_000) return null
        return runCatching { json.decodeFromString<TrackerSnapshot>(snapshotFile.readText()) }.getOrNull()
    }

    private fun writeLocked(snapshot: TrackerSnapshot) {
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000)
        val temporary = File.createTempFile("snapshot-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(snapshotFile)) { "Cannot publish tracker snapshot" }
        } finally { temporary.delete() }
    }

    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        check(root.isDirectory || root.mkdirs()) { "Cannot open tracker cache" }
        RandomAccessFile(File(root, "snapshot.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    companion object { private val processLock = Any() }
}

/** The provider has a bounded, half-open time range and never accepts SQL or a network-refresh switch. */
data class TrackerRange(val from: Long, val to: Long) {
    fun contains(at: Long) = at >= from && at < to
    companion object {
        fun parse(from: String?, to: String?): TrackerRange? {
            val start = from?.toLongOrNull() ?: return null
            val end = to?.toLongOrNull() ?: return null
            return if (start >= 0 && end > start && end - start <= 31L * 86_400) TrackerRange(start, end) else null
        }
    }
}

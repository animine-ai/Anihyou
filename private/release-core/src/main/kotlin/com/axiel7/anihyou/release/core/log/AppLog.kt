package com.axiel7.anihyou.release.core.log

/**
 * Debug log of every place where data is chosen, replaced, filtered or where a long operation changes stage.
 *
 * Debug and performance test apps install the sink; ordinary releases print nothing. Messages carry ids, counts,
 * stage names and short digests. They never carry keys, seeds, tokens, cookies or page content: callers pass only what
 * [short] and [host] let through.
 */
object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, area: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    val enabled: Boolean get() = sink != null

    fun d(area: String, message: () -> String) = emit(Level.DEBUG, area, null, message)
    fun i(area: String, message: () -> String) = emit(Level.INFO, area, null, message)
    fun w(area: String, error: Throwable? = null, message: () -> String) = emit(Level.WARN, area, error, message)
    fun e(area: String, error: Throwable? = null, message: () -> String) = emit(Level.ERROR, area, error, message)

    private inline fun emit(level: Level, area: String, error: Throwable?, message: () -> String) {
        val target = sink ?: return
        // A log line must never break the feature it describes.
        runCatching { target.log(level, area, message(), error) }
    }

    /** First 12 characters of a digest, enough to correlate lines and not a secret. */
    fun short(digest: String?): String = digest?.take(12) ?: "-"

    /** Scheme and host only: a path may name a private repository, a query may carry a token. */
    fun host(url: String?): String = url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "-"

    /** A bounded, single-line form of an id list. */
    fun ids(values: Collection<*>, limit: Int = 12): String =
        values.take(limit).joinToString(",", "[", if (values.size > limit) ",+${values.size - limit}]" else "]")
}

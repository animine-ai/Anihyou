package com.axiel7.anihyou.release.data.aniworld

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One bounded-body HTTP transaction. Redirect validation remains in [AniWorldClient].
 * Cancellation disconnects the active socket and waits for its IO thread to leave
 * before the suspend call completes, so an outer semaphore cannot release early.
 */
class JdkAniWorldHttpTransport : AniWorldHttpTransport {
    override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse {
        require(request.timeoutMillis in 1..Int.MAX_VALUE.toLong())
        require(request.maxBytes > 0)
        val finished = CompletableDeferred<Unit>()
        return try {
            suspendCancellableCoroutine { continuation ->
                val cancelled = AtomicBoolean(false)
                val activeConnection = AtomicReference<HttpURLConnection?>(null)
                val activeThread = AtomicReference<Thread?>(null)
                try {
                    IO_EXECUTOR.execute {
                        activeThread.set(Thread.currentThread())
                        try {
                            check(!cancelled.get()) { "request cancelled before socket start" }
                            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                                connectTimeout = request.timeoutMillis.toInt()
                                readTimeout = request.timeoutMillis.toInt()
                                instanceFollowRedirects = false
                                requestMethod = "GET"
                                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                                setRequestProperty("User-Agent", "Kiyori-AniWorld-ReleaseSync/1")
                                request.ifNoneMatch?.let { setRequestProperty("If-None-Match", it.safeValidator()) }
                                request.ifModifiedSince?.let { setRequestProperty("If-Modified-Since", it.safeValidator()) }
                            }
                            activeConnection.set(connection)
                            if (cancelled.get()) connection.disconnect()
                            try {
                                val response = connection.readResponse(request)
                                continuation.resume(response)
                            } finally {
                                activeConnection.compareAndSet(connection, null)
                                connection.disconnect()
                            }
                        } catch (failure: Throwable) {
                            continuation.resumeWithException(failure)
                        } finally {
                            activeThread.set(null)
                            finished.complete(Unit)
                        }
                    }
                } catch (failure: Throwable) {
                    finished.complete(Unit)
                    continuation.resumeWithException(failure)
                }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    activeConnection.get()?.disconnect()
                    activeThread.get()?.interrupt()
                }
            }
        } finally {
            // Cancellable continuations resume promptly on cancellation. Keep the
            // caller's permit until the blocking worker confirms socket teardown.
            withContext(NonCancellable + Dispatchers.IO) { finished.await() }
        }
    }

    private fun HttpURLConnection.readResponse(request: AniWorldTransportRequest): AniWorldHttpResponse {
        val statusCode = responseCode
        val stream = if (statusCode in 200..299) inputStream else errorStream
        val bytes = stream?.use { it.readBounded(request.maxBytes) } ?: ByteArray(0)
        val oversized = bytes.size > request.maxBytes
        return AniWorldHttpResponse(
            statusCode = statusCode,
            contentType = contentType,
            finalUrl = url.toString(),
            body = if (oversized) "" else bytes.toString(Charsets.UTF_8),
            bodyTooLarge = oversized,
            rawBodyBytes = bytes.size,
            location = getHeaderField("Location")?.takeIf { it.length <= 2048 },
            retryAfter = getHeaderField("Retry-After")?.takeIf { it.length <= 256 },
            etag = getHeaderField("ETag")?.takeIf { it.length <= 512 },
            lastModified = getHeaderField("Last-Modified")?.takeIf { it.length <= 256 },
        )
    }

    companion object {
        private val IO_EXECUTOR = Executors.newCachedThreadPool { task ->
            Thread(task, "aniworld-http").apply { isDaemon = true }
        }
    }
}

private fun String.safeValidator(): String {
    require(length in 1..512 && none { it.code < 32 || it.code == 127 })
    return this
}

internal fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "maximum response size must be positive" }
    val output = ByteArrayOutputStream((maxBytes + 1).coerceAtMost(16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total <= maxBytes) {
        val read = read(buffer, 0, (maxBytes + 1 - total).coerceAtMost(buffer.size))
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

package com.axiel7.anihyou.release.data.aniworld

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded production transport for the provider client. Redirect validation remains
 * in [AniWorldClient], so this class never broadens the provider host allowlist.
 */
class JdkAniWorldHttpTransport : AniWorldHttpTransport {
    override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse =
        withContext(Dispatchers.IO) {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = request.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                readTimeout = request.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("User-Agent", "Kiyori-AniWorld-ReleaseSync/1")
                request.ifNoneMatch?.let { setRequestProperty("If-None-Match", it.safeValidator()) }
                request.ifModifiedSince?.let { setRequestProperty("If-Modified-Since", it.safeValidator()) }
            }
            try {
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val bytes = stream?.use { it.readBounded(request.maxBytes) } ?: ByteArray(0)
                val oversized = bytes.size > request.maxBytes
                AniWorldHttpResponse(
                    statusCode = statusCode,
                    contentType = connection.contentType,
                    finalUrl = connection.url.toString(),
                    body = if (oversized) "" else bytes.toString(Charsets.UTF_8),
                    bodyTooLarge = oversized,
                    rawBodyBytes = bytes.size,
                    location = connection.getHeaderField("Location")?.take(2048),
                    retryAfter = connection.getHeaderField("Retry-After")?.take(256),
                    etag = connection.getHeaderField("ETag")?.take(512),
                    lastModified = connection.getHeaderField("Last-Modified")?.take(256),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

private fun String.safeValidator(): String {
    require(length in 1..512 && none { it == '\r' || it == '\n' || it.isISOControl() })
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

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
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("User-Agent", "Kiyori-AniWorld-ReleaseSync/1")
            }
            try {
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.use { it.readBounded(request.maxBytes) }.orEmpty()
                AniWorldHttpResponse(
                    statusCode = statusCode,
                    contentType = connection.contentType,
                    finalUrl = connection.url.toString(),
                    body = body,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

private fun java.io.InputStream.readBounded(maxBytes: Int): String {
    require(maxBytes > 0) { "maximum response size must be positive" }
    val output = ByteArrayOutputStream((maxBytes + 1).coerceAtMost(16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total <= maxBytes) {
        val read = read(buffer)
        if (read < 0) break
        val remaining = maxBytes + 1 - total
        val copied = read.coerceAtMost(remaining)
        output.write(buffer, 0, copied)
        total += copied
        if (copied < read || total > maxBytes) break
    }
    return output.toString(Charsets.UTF_8.name())
}

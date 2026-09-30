package com.axiel7.anihyou.release.data.extension

import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Binary source requests are authorized by independently authenticated origins, never guest grants. */
internal fun interface ExtensionRepositoryTransport {
    suspend fun fetch(url: String, allowedOrigins: Set<String>, maxBytes: Int): ByteArray
}

/** Source metadata and archives share the proven destination-bound hop, without a guest session. */
internal class ProductionExtensionRepositoryTransport(
    private val resolver: ExtensionAddressResolver = SOURCE_RESOLVER,
    private val hop: BoundHttpsHopExecutor? = null,
) : ExtensionRepositoryTransport {
    override suspend fun fetch(url: String, allowedOrigins: Set<String>, maxBytes: Int): ByteArray {
        require(maxBytes in 1..MAX_BODY_BYTES)
        val origins = allowedOrigins.toSet()
        require(origins.isNotEmpty() && origins.size <= 16)
        origins.forEach(::validateOrigin)
        var current = normalizeUrl(url, origins)
        val executor = hop ?: OkHttpBoundHttpsHopExecutor(maxBodyBytes = maxBytes)
        val cancellation = NetworkCancellation()
        val visited = HashSet<String>()
        var redirects = 0
        try {
            return withTimeout(MAX_REQUEST_MILLIS) {
                while (true) {
                    coroutineContext.ensureActive()
                    cancellation.check()
                    require(visited.add(current)) { "repository redirect loop" }
                    val uri = URI(current)
                    val host = requireNotNull(uri.host)
                    val response = withTimeout(MAX_HOP_MILLIS) {
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_HOP_MILLIS)
                        val addresses = resolver.resolve(host).toList()
                        coroutineContext.ensureActive()
                        cancellation.check()
                        require(addresses.isNotEmpty() && addresses.size <= 16 &&
                            addresses.distinct().size == addresses.size) { "invalid repository DNS answers" }
                        require(addresses.all { ProductionExtensionHttpTransport.isPublicDestination(it) }) {
                            "forbidden repository DNS answer"
                        }
                        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (remaining <= 0) throw IOException("repository hop deadline exceeded")
                        executor.fetch(current, host, addresses, remaining, cancellation).also {
                            coroutineContext.ensureActive()
                            cancellation.check()
                            require(it.destination in addresses) { "repository socket escaped validated DNS set" }
                        }
                    }
                    val headers = validatedHeaders(response.headers)
                    val isRedirect = response.status in REDIRECT_CODES
                    val bodyLimit = if (isRedirect) minOf(REDIRECT_BODY_BYTES, maxBytes) else maxBytes
                    require(response.body.size <= bodyLimit) { "repository response body exceeds bound" }
                    headers["content-length"]?.let { value ->
                        require(value.matches(Regex("[0-9]{1,19}")) &&
                            value.toLongOrNull()?.let { it <= bodyLimit.toLong() } == true) {
                            "repository Content-Length exceeds bound"
                        }
                    }
                    require(headers["content-encoding"]?.equals("identity", ignoreCase = true) != false) {
                        "encoded repository response is forbidden"
                    }
                    if (isRedirect) {
                        require(redirects < MAX_REDIRECTS) { "repository redirect limit exceeded" }
                        val location = requireNotNull(headers["location"]) { "repository redirect location missing" }
                        validateUrlText(location)
                        require(!location.startsWith("//")) { "repository scheme-relative redirect is forbidden" }
                        val reference = URI(location)
                        validatePath(reference)
                        current = normalizeUrl(uri.resolve(reference).toString(), origins)
                        redirects++
                    } else {
                        if (response.status == 429 || response.status in 500..599) {
                            throw IOException("transient repository HTTP status ${response.status}")
                        }
                        require(response.status in 200..299) { "repository HTTP status ${response.status}" }
                        coroutineContext.ensureActive()
                        cancellation.check()
                        return@withTimeout response.body
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("repository response missing")
            }
        } catch (timedOut: TimeoutCancellationException) {
            // Internal network deadlines are retryable. A caller's cancelled Job must
            // retain cancellation so WorkManager/view-model teardown is never retried.
            coroutineContext.ensureActive()
            throw IOException("repository network deadline exceeded", timedOut)
        } finally {
            // Also closes an active real call when DNS, body validation or the parent job fails.
            cancellation.cancel()
        }
    }

    private fun validatedHeaders(headers: Map<String, String>): Map<String, String> {
        require(headers.size <= MAX_HEADER_COUNT)
        val bytes = headers.entries.sumOf {
            it.key.toByteArray(Charsets.UTF_8).size.toLong() +
                it.value.toByteArray(Charsets.UTF_8).size + 4
        }
        require(bytes <= MAX_HEADER_BYTES)
        val normalized = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        require(normalized.size == headers.size) { "ambiguous repository response headers" }
        return normalized
    }

    internal companion object {
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_REQUEST_MILLIS = 60_000L
        private const val MAX_HOP_MILLIS = 10_000L
        private const val MAX_REDIRECTS = 5
        private const val MAX_URL_BYTES = 2048
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val REDIRECT_BODY_BYTES = 4096
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val HOST = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
        private val DNS_EXECUTOR = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(32)) { runnable ->
            Thread(runnable, "extension-repository-dns").apply { isDaemon = true }
        }
        private val SOURCE_RESOLVER = ExtensionAddressResolver { host ->
            suspendCancellableCoroutine { continuation ->
                val task = DNS_EXECUTOR.submit {
                    try {
                        val addresses = InetAddress.getAllByName(host).toList()
                        if (continuation.isActive) continuation.resume(addresses)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    task.cancel(true)
                    DNS_EXECUTOR.remove(task as Runnable)
                }
            }
        }

        fun normalizeUrl(value: String, allowedOrigins: Set<String>): String {
            validateUrlText(value)
            val uri = URI(value)
            require(!uri.isOpaque && uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in setOf(-1, 443)) { "invalid repository HTTPS URL" }
            val host = requireNotNull(uri.host).lowercase(Locale.ROOT)
            validateHost(host)
            val origin = "https://$host"
            require(origin in allowedOrigins) { "repository origin is not independently authenticated" }
            validatePath(uri)
            return origin + uri.rawPath.ifEmpty { "/" }
        }

        private fun validateOrigin(origin: String) {
            validateUrlText(origin)
            val uri = URI(origin)
            val host = requireNotNull(uri.host)
            validateHost(host)
            require(origin == "https://$host") { "authenticated origin must be exactly https://host" }
        }

        private fun validateHost(host: String) {
            require(host.length in 1..253 && host == host.lowercase(Locale.ROOT) && HOST.matches(host) &&
                !host.all { it.isDigit() || it == '.' }) { "repository host must be a canonical DNS hostname" }
        }

        private fun validateUrlText(value: String) {
            require(value.length in 1..MAX_URL_BYTES && value.all { it.code in 0x21..0x7e } &&
                '%' !in value && '\\' !in value) { "ambiguous or oversized repository URL" }
        }

        private fun validatePath(uri: URI) {
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            val path = requireNotNull(uri.rawPath)
            require(path.split('/').none { it == "." || it == ".." }) { "repository path traversal forbidden" }
        }
    }
}

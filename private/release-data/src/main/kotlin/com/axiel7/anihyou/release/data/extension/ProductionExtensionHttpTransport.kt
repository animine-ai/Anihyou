package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionMethod
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.RequestSpec
import com.axiel7.anihyou.release.core.extension.ResponseEnvelope
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface ExtensionAddressResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

internal data class BoundHopResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray,
    val destination: InetAddress)

/** A hop executor may only connect to the already validated address set. */
internal fun interface BoundHttpsHopExecutor {
    suspend fun fetch(url: String, host: String, addresses: List<InetAddress>, timeoutMillis: Long,
        cancellation: NetworkCancellation): BoundHopResponse
}

internal class NetworkCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var active: Call? = null
    fun check() { if (cancelled.get()) throw CancellationException("extension network operation cancelled") }
    fun register(call: Call) { check(); active = call; if (cancelled.get()) call.cancel() }
    fun clear(call: Call) { if (active === call) active = null }
    fun cancel() { cancelled.set(true); active?.cancel() }
}

/** The app must provide a host-authoritative reservation ledger; no permissive default exists. */
internal interface ExtensionNetworkLedger {
    suspend fun reserve(provider: String, digest: String, generation: String, role: String,
        rootUrl: String, hopUrl: String, now: Instant): ExtensionNetworkReservation?
    suspend fun complete(reservation: ExtensionNetworkReservation, outcome: String,
        retryAfterSeconds: Long?, now: Instant)
}

internal data class ExtensionNetworkReservation(val token: String)

internal data class ExtensionFetchedResponse(
    val requestId: String, val status: ExtensionResponseStatus, val httpStatus: Int?,
    val finalUrl: String?, val bodyUtf8: String?, val sourceHash: String?,
    val bodyBytes: Int, val destination: String?, val redirects: Int,
)

/** Host-produced metadata; never accepted from WASM output. */
data class ExtensionResponseProvenance(
    val requestId: String,
    val finalUrl: String,
    val httpStatus: Int,
    val responseHeaders: Map<String, String>,
    val sourceHash: String,
    val bodyBytes: Int,
    val redirectCount: Int,
    val destinationAddress: String,
    val completedAt: Instant,
)

/** One session pins package and generation, budgets and every authenticated network hop. */
internal class ProductionExtensionHttpTransport(
    private val ledger: ExtensionNetworkLedger,
    private val resolver: ExtensionAddressResolver = ExtensionAddressResolver { host ->
        suspendCancellableCoroutine { continuation ->
            val task = DNS_EXECUTOR.submit {
                try {
                    val result = InetAddress.getAllByName(host).toList()
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { task.cancel(true) }
        }
    },
    private val hop: BoundHttpsHopExecutor = OkHttpBoundHttpsHopExecutor(),
    private val clock: Clock = Clock.systemUTC(),
) : DestinationBoundExtensionTransport {
    override val dnsDestinationBindingVerified = true

    fun open(extension: VerifiedExtensionPackage, generation: String): Session {
        require(generation.isNotBlank() && generation.length <= 128)
        require(extension.grantedHosts.isNotEmpty() && extension.packageDigest.matches(SHA256))
        return Session(extension, generation)
    }

    override suspend fun execute(extension: VerifiedExtensionPackage, request: RequestSpec): ResponseEnvelope =
        open(extension, "single-${request.requestId}").use { session ->
            session.fetch(request.requestId, request.sourceRole.name, request.url).let {
                ResponseEnvelope(it.requestId, request.sourceRole, it.status, it.httpStatus,
                    it.finalUrl, it.bodyUtf8, it.sourceHash)
            }
        }

    inner class Session internal constructor(
        private val extension: VerifiedExtensionPackage, val generation: String,
    ) : AutoCloseable {
        val packageDigest: String = extension.packageDigest
        private val cancellation = NetworkCancellation()
        private val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(240)
        private var logical = 0
        private var wire = 0
        private var totalBytes = 0L
        private val seenLogical = HashSet<String>()
        val provenance = ArrayList<ExtensionResponseProvenance>()

        suspend fun fetch(requestId: String, role: String, url: String): ExtensionFetchedResponse {
            cancellation.check()
            coroutineContext.ensureActive()
            require(requestId.matches(REQUEST_ID) && role in ROLES && seenLogical.add(requestId))
            require(++logical <= MAX_LOGICAL && extension.packageDigest == packageDigest)
            val root = normalizedUrl(url, extension.grantedHosts)
            var current = root
            val visited = HashSet<String>()
            var redirects = 0
            while (true) {
                cancellation.check()
                coroutineContext.ensureActive()
                require(visited.add(current) && redirects <= MAX_REDIRECTS) { "redirect loop or limit" }
                require(++wire <= MAX_WIRE && remainingMillis() > 0)
                val uri = URI(current)
                val host = requireNotNull(uri.host)
                val reservation = ledger.reserve(extension.providerId.value, packageDigest, generation,
                    role, root, current, clock.instant()) ?: return denied(requestId, current, redirects)
                var outcome = "TRANSPORT_FAILURE"
                var retryAfter: Long? = null
                try {
                    val dnsRemaining = remainingMillis()
                    require(dnsRemaining > 0)
                    val addresses = withTimeout(minOf(MAX_HOP_MILLIS, dnsRemaining)) { resolver.resolve(host) }
                    cancellation.check()
                    coroutineContext.ensureActive()
                    require(addresses.isNotEmpty() && addresses.size <= 16 && addresses.distinct().size == addresses.size)
                    require(addresses.all(::isPublicDestination)) { "forbidden DNS answer" }
                    val remaining = remainingMillis()
                    require(remaining > 0)
                    val response = hop.fetch(current, host, addresses, minOf(MAX_HOP_MILLIS, remaining), cancellation)
                    cancellation.check()
                    coroutineContext.ensureActive()
                    require(response.headers.size <= MAX_HEADER_COUNT && response.headers.entries.sumOf {
                        it.key.length + it.value.length
                    } <= MAX_HEADER_BYTES)
                    require(response.destination in addresses && response.body.size <= MAX_BODY &&
                        totalBytes + response.body.size <= MAX_TOTAL_BYTES)
                    totalBytes += response.body.size
                    retryAfter = parseRetryAfter(response.headers["retry-after"], clock.instant())
                    if (response.status in REDIRECTS) {
                        outcome = "REDIRECT"
                        require(redirects < MAX_REDIRECTS && response.body.size <= MAX_REDIRECT_BODY)
                        val location = response.headers["location"] ?: error("redirect location missing")
                        require(location.length <= MAX_URL && !location.contains('\\'))
                        current = normalizedUrl(uri.resolve(location).toString(), extension.grantedHosts)
                        redirects++
                        continue
                    }
                    outcome = when (response.status) {
                        in 200..299 -> "HTTP_2XX"
                        429 -> "HTTP_429"
                        else -> "HTTP_${response.status}"
                    }
                    if (response.status !in 200..299) return ExtensionFetchedResponse(requestId,
                        ExtensionResponseStatus.TRANSPORT_FAILURE, response.status, current, null, null,
                        0, response.destination.hostAddress, redirects)
                    val body = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(response.body)).toString()
                    cancellation.check()
                    coroutineContext.ensureActive()
                    val sourceHash = sha(response.body)
                    provenance += ExtensionResponseProvenance(requestId, current, response.status,
                        response.headers, sourceHash, response.body.size, redirects,
                        requireNotNull(response.destination.hostAddress), clock.instant())
                    return ExtensionFetchedResponse(requestId, ExtensionResponseStatus.OK, response.status,
                        current, body, sourceHash, response.body.size, response.destination.hostAddress, redirects)
                } catch (cancelled: CancellationException) {
                    outcome = "CANCELLED"
                    cancellation.cancel()
                    throw cancelled
                } catch (failure: IOException) {
                    cancellation.check()
                    return ExtensionFetchedResponse(requestId, ExtensionResponseStatus.TRANSPORT_FAILURE,
                        null, current, null, null, 0, null, redirects)
                } finally {
                    withContext(NonCancellable) {
                        ledger.complete(reservation, outcome, retryAfter, clock.instant())
                    }
                }
            }
        }

        override fun close() = cancellation.cancel()
        private fun remainingMillis(): Long =
            TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        private fun denied(id: String, url: String, redirects: Int) = ExtensionFetchedResponse(id,
            ExtensionResponseStatus.BUDGET_DENIED, null, url, null, null, 0, null, redirects)
    }

    internal companion object {
        private val DNS_EXECUTOR = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(32)) { runnable -> Thread(runnable, "extension-dns").apply { isDaemon = true } }
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val REQUEST_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val ROLES = setOf("CALENDAR", "RECENT", "POSTPONEMENT", "DIRECT", "NAVIGATION")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val MAX_LOGICAL = 7
        private const val MAX_WIRE = 22
        private const val MAX_REDIRECTS = 5
        private const val MAX_URL = 2048
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY = 2 * 1024 * 1024
        private const val MAX_REDIRECT_BODY = 4096
        private const val MAX_TOTAL_BYTES = 12L * 1024 * 1024
        private const val MAX_HOP_MILLIS = 10_000L

        fun normalizedUrl(value: String, allowedHosts: Set<String>): String {
            require(value.toByteArray(Charsets.UTF_8).size in 1..MAX_URL)
            val uri = URI(value)
            val host = uri.host ?: error("missing DNS host")
            require(uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.rawFragment == null &&
                uri.port in setOf(-1, 443) && uri.rawPath != null && !value.contains('\\'))
            require(host == host.lowercase(java.util.Locale.ROOT) && host in allowedHosts &&
                host.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")) &&
                !host.all { it.isDigit() || it == '.' } && ':' !in host)
            val path = uri.rawPath.takeIf { it.isNotEmpty() } ?: "/"
            return "https://$host$path" + (uri.rawQuery?.let { "?$it" } ?: "")
        }

        fun isPublicDestination(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size == 4) {
                val a = bytes[0].toInt() and 255
                val b = bytes[1].toInt() and 255
                val c = bytes[2].toInt() and 255
                return a !in setOf(0, 10, 127) && a < 224 &&
                    !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                    !(a == 172 && b in 16..31) && !(a == 192 &&
                        (b == 168 || b == 0 && c in setOf(0, 2) || b == 88 && c == 99)) &&
                    !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
                    !(a == 203 && b == 0 && c == 113)
            }
            if (bytes.size != 16) return false
            if (bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte())
                return isPublicDestination(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            if (a !in 0x20..0x3f || a == 0x20 && b == 0x02) return false
            if (a == 0x20 && b == 0x01) {
                val c = bytes[2].toInt() and 255
                if (c <= 0x03 || c in setOf(0x10, 0x20) ||
                    c == 0x0d && (bytes[3].toInt() and 255) == 0xb8) return false
            }
            return true
        }

        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun parseRetryAfter(value: String?, now: Instant): Long? {
            val text = value?.trim()?.takeIf { it.length in 1..256 } ?: return null
            val seconds = text.toLongOrNull() ?: runCatching {
                Duration.between(now, ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds
            }.getOrNull() ?: return null
            return seconds.coerceIn(0, 21_600)
        }
    }
}

/** A fresh client per hop prevents connection pooling across trust decisions. */
internal class OkHttpBoundHttpsHopExecutor(
    private val tlsSocketFactory: SSLSocketFactory? = null,
    private val trustManager: X509TrustManager? = null,
) : BoundHttpsHopExecutor {
    override suspend fun fetch(url: String, host: String, addresses: List<InetAddress>, timeoutMillis: Long,
        cancellation: NetworkCancellation): BoundHopResponse {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var failure: IOException? = null
        for (address in addresses) {
            cancellation.check()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            try {
                return fetchOne(url, host, address, remaining, cancellation)
            } catch (error: PreTlsConnectFailure) {
                failure = error.cause as? IOException ?: error
            }
        }
        throw failure ?: IOException("no validated address connected within deadline")
    }

    private class PreTlsConnectFailure(cause: IOException) : IOException(cause)

    private suspend fun fetchOne(url: String, host: String, address: InetAddress, timeoutMillis: Long,
        cancellation: NetworkCancellation): BoundHopResponse = suspendCancellableCoroutine { continuation ->
        val allowed = setOf(address)
        val dns = Dns { name ->
            cancellation.check()
            if (name != host) throw UnknownHostException("unexpected DNS hostname")
            listOf(address)
        }
        var connected: InetAddress? = null
        val tlsStarted = AtomicBoolean(false)
        val requestStarted = AtomicBoolean(false)
        val listener = object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                if (proxy != Proxy.NO_PROXY || inetSocketAddress.address !in allowed) {
                    call.cancel()
                    throw IOException("socket destination escaped validated DNS set")
                }
            }
            override fun connectionAcquired(call: Call, connection: Connection) {
                if (connection.socket().inetAddress !in allowed || connection.handshake() == null) {
                    call.cancel()
                    throw IOException("unvalidated or non-TLS connection")
                }
                connected = connection.socket().inetAddress
            }
            override fun secureConnectStart(call: Call) { tlsStarted.set(true) }
            override fun requestHeadersStart(call: Call) { requestStarted.set(true) }
        }
        val builder = OkHttpClient.Builder()
            .dns(dns).proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_1_1))
            .eventListener(listener).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        if (tlsSocketFactory != null) builder.sslSocketFactory(tlsSocketFactory, requireNotNull(trustManager))
        val client = builder.build()
        val request = Request.Builder().url(url).get().header("Accept-Encoding", "identity")
            .header("User-Agent", "AnimetrackerExtension/1").build()
        val call = client.newCall(request)
        cancellation.register(call)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                cancellation.clear(call)
                if (continuation.isActive) continuation.resumeWithException(
                    if (!tlsStarted.get() && !requestStarted.get() && !call.isCanceled())
                        PreTlsConnectFailure(error) else error)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        cancellation.check()
                        val headers = response.headers
                        require(headers.size <= 64 && headers.byteCount() <= 16 * 1024)
                        require(headers.values("content-encoding").all { it.equals("identity", true) })
                        require((response.body?.contentLength() ?: 0) <= 2L * 1024 * 1024)
                        val bodyLimit = if (response.code in setOf(301, 302, 303, 307, 308)) 4096
                            else 2 * 1024 * 1024
                        val stream = response.body?.byteStream()
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        if (stream != null) while (true) {
                            cancellation.check()
                            val n = stream.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= bodyLimit)
                            output.write(buffer, 0, n)
                        }
                        val permitted = setOf("location", "retry-after", "content-type", "content-length", "content-encoding")
                        val sanitized = headers.names().map(String::lowercase).filter { it in permitted }
                            .associateWith { headers[it].orEmpty() }
                        cancellation.check()
                        val destination = requireNotNull(connected) { "no validated socket" }
                        if (continuation.isActive) continuation.resume(BoundHopResponse(response.code, sanitized,
                            output.toByteArray(), destination))
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally { cancellation.clear(call) }
                }
            }
        })
    }
}

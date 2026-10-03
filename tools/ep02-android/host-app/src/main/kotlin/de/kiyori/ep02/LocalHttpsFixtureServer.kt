package de.kiyori.ep02

import android.content.Context
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Local HTTPS fixture for the standalone EP02 proof APK. */
internal class LocalHttpsFixtureServer(context: Context) : Closeable {
    private val fixtureContext = context
    private val closed = AtomicBoolean(false)
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile var retentionCalendarChanged: Boolean = false

    /** Transport-level failure injection through the real production socket and TLS path. */
    enum class FailureMode { NONE, RESET_AFTER_HANDSHAKE }
    @Volatile var failureMode: FailureMode = FailureMode.NONE
    private val handshakeFailures = AtomicInteger()
    private val largeBodyStarted = CountDownLatch(1)
    private val largeBodyAborted = CountDownLatch(1)
    private val largeBodyCompleted = CountDownLatch(1)
    private val slowBodyStarted = CountDownLatch(1)
    private val releaseSlowBody = CountDownLatch(1)
    private val slowBodyAborted = CountDownLatch(1)
    private val slowBodyCompleted = CountDownLatch(1)
    private val handlers = Executors.newCachedThreadPool { task ->
        Thread(task, "ep02-local-https-handler").apply { isDaemon = true }
    }
    private val serverSocket = createServerSocket(context)
    private val acceptThread = Thread(::acceptLoop, "ep02-local-https-accept").apply { isDaemon = true }

    fun start() = acceptThread.start()

    fun pathCount(path: String): Int = paths.count { it == path }

    fun totalRequests(): Int = paths.size

    fun awaitLargeBodyAbort(timeoutMillis: Long = 5_000): Boolean =
        largeBodyAborted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun largeBodyCompleted(): Boolean = largeBodyCompleted.count == 0L

    fun awaitSlowBodyStart(timeoutMillis: Long = 5_000): Boolean =
        slowBodyStarted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun releaseSlowBody() = releaseSlowBody.countDown()

    fun awaitSlowBodyAbort(timeoutMillis: Long = 5_000): Boolean =
        slowBodyAborted.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun slowBodyCompleted(): Boolean = slowBodyCompleted.count == 0L

    fun tlsHandshakeFailureCount(): Int = handshakeFailures.get()

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                serverSocket.accept() as SSLSocket
            } catch (_: IOException) {
                if (!closed.get()) handshakeFailures.incrementAndGet()
                return
            }
            handlers.execute { serve(socket) }
        }
    }

    private fun serve(socket: SSLSocket) {
        socket.use { connection ->
            try {
                connection.soTimeout = 10_000
                connection.startHandshake()
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val requestPath = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                paths += requestPath
                // The request reached the fixture (and is counted); the connection ends without any answer.
                if (failureMode == FailureMode.RESET_AFTER_HANDSHAKE) return
                when (requestPath) {
                    "/animekalender", "/neue-episoden", "/support/frage/anime-verschiebungen",
                    "/anime/stream/fixture-series", "/anime/stream/fixture-series/staffel-1/episode-1" -> {
                        val name = when (requestPath) {
                            "/animekalender" -> "calendar"
                            "/neue-episoden" -> "recent"
                            "/support/frage/anime-verschiebungen" -> "postponement"
                            "/anime/stream/fixture-series" -> "overview"
                            else -> "direct"
                        }
                        val vector = if (name == "overview") "overview-parse" else "$name-release-parse"
                        val input = fixtureContext.assets.open("aniworld-inputs/$vector-input.json")
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        var bodyText = org.json.JSONObject(input).getJSONArray("responses")
                            .getJSONObject(0).getString("bodyUtf8")
                        if (name == "calendar" && retentionCalendarChanged) {
                            val entry = Regex("<div class=\"col-md-15 col-sm-3 col-xs-6\">[\\s\\S]*?</div>")
                                .find(bodyText)?.value ?: error("missing calendar TEST fixture row")
                            val added = entry.replace("S01E01", "S01E02").replace("Episode 1", "Episode 2")
                                .replace("~ 12:00 Uhr", "~ 14:00 Uhr")
                            bodyText = bodyText.replace(entry, entry + added).replace("~ 12:00 Uhr", "~ 13:00 Uhr")
                        }
                        val body = bodyText.toByteArray(Charsets.UTF_8)
                        respond(connection, 200, "OK", body)
                    }
                    "/calendar", "/redirect-final" -> respond(
                        connection, 200, "OK", RELEASE_BODY.toByteArray(Charsets.UTF_8))
                    "/nav-source" -> respond(connection, 200, "OK", NAV_BODY.toByteArray(Charsets.UTF_8))
                    "/redirect" -> respond(
                        connection, 302, "Found", ByteArray(0), listOf("Location: /redirect-final"))
                    "/large" -> streamOversizedBody(connection)
                    "/slow" -> streamCancellableBody(connection)
                    else -> respond(connection, 404, "Not Found", ByteArray(0))
                }
            } catch (error: IOException) {
                if (error.message?.contains("handshake", ignoreCase = true) == true ||
                    error.javaClass.name.contains("SSL", ignoreCase = true)) {
                    handshakeFailures.incrementAndGet()
                }
            }
        }
    }

    private fun respond(
        socket: SSLSocket,
        status: Int,
        reason: String,
        body: ByteArray,
        headers: List<String> = emptyList(),
    ) {
        val output = socket.outputStream
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun streamOversizedBody(socket: SSLSocket) {
        val output = socket.outputStream
        writeChunkedHeaders(output)
        largeBodyStarted.countDown()
        val chunk = ByteArray(CHUNK_BYTES) { 'L'.code.toByte() }
        try {
            repeat(OVERSIZED_BODY_BYTES / CHUNK_BYTES) {
                writeChunk(output, chunk)
                try {
                    Thread.sleep(OVERSIZED_WRITE_PACE_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("fixture stream interrupted", interrupted)
                }
            }
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            largeBodyCompleted.countDown()
        } catch (_: IOException) {
            largeBodyAborted.countDown()
        }
    }

    private fun streamCancellableBody(socket: SSLSocket) {
        val output = socket.outputStream
        writeChunkedHeaders(output)
        val chunk = ByteArray(CHUNK_BYTES) { 'C'.code.toByte() }
        val clientDisconnected = CountDownLatch(1)
        Thread({
            try {
                if (socket.inputStream.read() < 0) clientDisconnected.countDown()
            } catch (_: IOException) {
                clientDisconnected.countDown()
            }
        }, "ep02-client-close-watch").apply { isDaemon = true }.start()
        try {
            writeChunk(output, chunk.copyOf(32))
            slowBodyStarted.countDown()
            if (!releaseSlowBody.await(5, TimeUnit.SECONDS)) return
            if (clientDisconnected.await(2, TimeUnit.SECONDS)) {
                slowBodyAborted.countDown()
                return
            }
            repeat(OVERSIZED_BODY_BYTES / CHUNK_BYTES) {
                writeChunk(output, chunk)
            }
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            slowBodyCompleted.countDown()
        } catch (_: IOException) {
            slowBodyAborted.countDown()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun writeChunkedHeaders(output: java.io.OutputStream) {
        output.write((
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeChunk(output: java.io.OutputStream, bytes: ByteArray) {
        output.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseSlowBody.countDown()
        runCatching { serverSocket.close() }
        handlers.shutdownNow()
    }

    private fun createServerSocket(context: Context): SSLServerSocket {
        val password = SERVER_KEYSTORE_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open("ep02_test_server.p12").use { keyStore.load(it, password) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, SecureRandom())
        }
        return (sslContext.serverSocketFactory.createServerSocket(
            SERVER_PORT, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        }
    }

    private companion object {
        const val SERVER_PORT = 8443
        const val SERVER_KEYSTORE_PASSWORD = "ep02-test-only"
        const val CHUNK_BYTES = 8192
        const val OVERSIZED_WRITE_PACE_MILLIS = 2L
        // The runner proxy and fast API35 emulators can buffer 16 MiB before the
        // client closes. Keep enough queued data to observe the bounded read abort.
        const val OVERSIZED_BODY_BYTES = 128 * 1024 * 1024
        const val RELEASE_BODY = "<article>Fixture episode 1</article>"
        const val NAV_BODY = "fixture navigation"
    }
}

package com.axiel7.anihyou.release.data.extension

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeErrorCode
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Wasmtime's bytecode/feature validator. Pair with [StrictWasmModuleProfileVerifier]. */
class WasmtimeNativeModuleProfileVerifier : WasmCoreModuleProfileVerifier {
    override fun verify(moduleBytes: ByteArray, navigationCapabilities: Set<NavigationCapability>) {
        require(moduleBytes.size in 8..MAX_MODULE_BYTES)
        val error = WasmtimeNativeBridge.nativeValidate(moduleBytes)
        require(error == null) { "Wasmtime rejected the frozen module profile: $error" }
    }

    private companion object {
        private val NEXT_INVOCATION_ID = AtomicLong(1)

        fun nextInvocationId(): Long =
            NEXT_INVOCATION_ID.getAndIncrement().also {
                check(it > 0) { "runtime invocation id overflow" }
            }

        const val MAX_MODULE_BYTES = 8 * 1024 * 1024
    }
}

/** One-call timings used by the Android proof and production diagnostics. */
data class ExtensionRuntimeCallDiagnostics(
    val totalMicros: Long,
    val serviceBindMicros: Long,
    val serviceReadMicros: Long,
    val nativeMicros: Long,
    val guestMicros: Long,
    val compileMicros: Long,
    val instantiateMicros: Long,
    val cacheHit: Boolean,
    val diagnosticCalls: Int,
    val diagnosticBytes: Int,
    val serviceGeneration: Long,
    val servicePid: Int,
    val serviceUid: Int,
    val serviceInternetPermissionGranted: Boolean,
)

/**
 * Production ExtensionRuntime. The service stays warm. Module bytes cross process only on a cache
 * miss and are keyed by the caller-verified immutable SHA-256 digest. Inputs/outputs use pipes,
 * avoiding Binder transaction-size coupling. Every guest call still gets a new Store/Instance.
 */
class AndroidIsolatedExtensionRuntime(context: Context) : ExtensionRuntime, AutoCloseable {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val sessionMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val nextGeneration = AtomicLong(1)
    private val callbackThread = HandlerThread("arex-runtime-replies").apply { start() }
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val started = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val lateResultRejections = AtomicLong(0)

    @Volatile private var session: Session? = null
    @Volatile private var activeToken: Long = 0
    @Volatile private var lastFenced: Pair<Long, Long>? = null
    @Volatile var lastDiagnostics: ExtensionRuntimeCallDiagnostics? = null
        private set

    private val callbackMessenger = Messenger(Handler(callbackThread.looper) { message ->
        val data = message.data
        val token = data.getLong(RuntimeProtocol.KEY_TOKEN)
        val generation = data.getLong(RuntimeProtocol.KEY_GENERATION)
        if (data.getString(RuntimeProtocol.KEY_STATUS) == RuntimeProtocol.STATUS_STARTED) {
            started[token]?.complete(Unit)
            return@Handler true
        }
        val current = pending[token]
        if (current == null || current.generation != generation) {
            lateResultRejections.incrementAndGet()
            return@Handler true
        }
        if (pending.remove(token, current)) {
            started.remove(token)
            current.reply.complete(RuntimeReply.from(data))
        } else {
            lateResultRejections.incrementAndGet()
        }
        true
    })

    override suspend fun execute(
        moduleDigest: String,
        moduleBytes: ByteArray,
        exportName: String,
        inputUtf8: ByteArray,
        limits: ExtensionExecutionLimits,
    ): ExtensionRuntimeResult = operationMutex.withLock {
        if (closed.get()) {
            return@withLock ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
        }
        if (!SHA256.matches(moduleDigest) ||
            exportName !in ALLOWED_EXPORTS || moduleBytes.size !in 8..MAX_MODULE_BYTES ||
            inputUtf8.size > limits.maxInputBytes || limits.maxOutputBytes > MAX_OUTPUT_BYTES ||
            limits.memoryBytes > MAX_MEMORY_BYTES
        ) {
            return@withLock ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.INVALID_INPUT)
        }

        val logicalStartedAt = System.nanoTime()
        val acquisition = try {
            acquireSession()
        } catch (_: Exception) {
            return@withLock ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
        }
        val activeSession = acquisition.session
        var sendModule = !activeSession.knownDigests.contains(moduleDigest)

        repeat(2) { attemptIndex ->
            when (val attempt = executeAttempt(
                activeSession = activeSession,
                moduleDigest = moduleDigest,
                moduleBytes = moduleBytes,
                exportName = exportName,
                inputUtf8 = inputUtf8,
                limits = limits,
                sendModule = sendModule,
                logicalStartedAt = logicalStartedAt,
                serviceBindMicros = acquisition.bindMicros,
            )) {
                is RuntimeAttempt.Success -> {
                    activeSession.knownDigests += moduleDigest
                    return@withLock ExtensionRuntimeResult.Success(attempt.output)
                }
                RuntimeAttempt.ModuleMiss -> {
                    activeSession.knownDigests.remove(moduleDigest)
                    if (sendModule || attemptIndex != 0) {
                        return@withLock ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
                    }
                    sendModule = true
                }
                is RuntimeAttempt.Failure -> return@withLock attempt.result
            }
        }
        ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
    }

    /**
     * Executes one IPC attempt. A stale host cache hint is recoverable exactly once by the caller;
     * all guest execution, timeout and cancellation failures remain fail-closed.
     */
    private suspend fun executeAttempt(
        activeSession: Session,
        moduleDigest: String,
        moduleBytes: ByteArray,
        exportName: String,
        inputUtf8: ByteArray,
        limits: ExtensionExecutionLimits,
        sendModule: Boolean,
        logicalStartedAt: Long,
        serviceBindMicros: Long,
    ): RuntimeAttempt {
        if (sendModule && sha256(moduleBytes) != moduleDigest) {
            return RuntimeAttempt.Failure(
                ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.INVALID_INPUT),
            )
        }
        val token = nextInvocationId()
        val generation = activeSession.generation
        activeToken = token
        val replyDeferred = CompletableDeferred<RuntimeReply>()
        val startedDeferred = CompletableDeferred<Unit>()
        pending[token] = Pending(generation, replyDeferred)
        started[token] = startedDeferred

        try {
            return coroutineScope {
                val inputInline = inputUtf8.size <= INLINE_PAYLOAD_BYTES
                val moduleInline = sendModule && moduleBytes.size <= INLINE_PAYLOAD_BYTES
                val inputPipe = if (inputInline) null else ParcelFileDescriptor.createPipe()
                val modulePipe = if (sendModule && !moduleInline) ParcelFileDescriptor.createPipe() else null

                val inputWriter = inputPipe?.let { pipe ->
                    async(Dispatchers.IO) {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(inputUtf8) }
                    }
                }
                val moduleWriter = modulePipe?.let { pipe ->
                    async(Dispatchers.IO) {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(moduleBytes) }
                    }
                }

                val message = Message.obtain(null, RuntimeProtocol.MSG_EXECUTE).apply {
                    replyTo = callbackMessenger
                    data = Bundle().apply {
                        putLong(RuntimeProtocol.KEY_TOKEN, token)
                        putLong(RuntimeProtocol.KEY_GENERATION, generation)
                        putString(RuntimeProtocol.KEY_DIGEST, moduleDigest)
                        putString(RuntimeProtocol.KEY_EXPORT, exportName)
                        putLong(RuntimeProtocol.KEY_MAX_OUTPUT, limits.maxOutputBytes.toLong())
                        putLong(RuntimeProtocol.KEY_MEMORY, limits.memoryBytes.toLong())
                        putLong(RuntimeProtocol.KEY_FUEL, limits.fuel)
                        putLong(RuntimeProtocol.KEY_DEADLINE, limits.deadlineMillis)
                        if (inputInline) putByteArray(RuntimeProtocol.KEY_INPUT_INLINE, inputUtf8)
                        else inputPipe?.let { putParcelable(RuntimeProtocol.KEY_INPUT_FD, it[0]) }
                        if (moduleInline) putByteArray(RuntimeProtocol.KEY_MODULE_INLINE, moduleBytes)
                        else modulePipe?.let { putParcelable(RuntimeProtocol.KEY_MODULE_FD, it[0]) }
                    }
                }

                try {
                    activeSession.messenger.send(message)
                } catch (error: RemoteException) {
                    fence(token, generation)
                    invalidate(activeSession)
                    throw error
                } finally {
                    inputPipe?.get(0)?.close()
                    modulePipe?.get(0)?.close()
                }

                val reply = withTimeoutOrNull(limits.deadlineMillis + HOST_DEADLINE_GRACE_MILLIS) {
                    replyDeferred.await()
                }
                if (reply == null) {
                    fence(token, generation)
                    sendCancel(activeSession, token, generation)
                    terminate(activeSession)
                    try { inputWriter?.await() } catch (_: Exception) {}
                    try { moduleWriter?.await() } catch (_: Exception) {}
                    return@coroutineScope RuntimeAttempt.Failure(
                        ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.DEADLINE)
                    )
                }

                inputWriter?.await()
                moduleWriter?.await()

                if (reply.status != RuntimeProtocol.STATUS_OK) {
                    reply.outputFd?.close()
                    if (!sendModule && reply.error?.contains(RuntimeProtocol.ERROR_MODULE_MISS) == true) {
                        return@coroutineScope RuntimeAttempt.ModuleMiss
                    }
                    return@coroutineScope RuntimeAttempt.Failure(
                        ExtensionRuntimeResult.Failure(mapError(reply.error))
                    )
                }

                val output = reply.outputInline ?: reply.outputFd?.let { fd ->
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { readBounded(it, limits.maxOutputBytes) }
                } ?: return@coroutineScope RuntimeAttempt.Failure(
                    ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.OUTPUT_LIMIT)
                )
                if (output.size != reply.outputBytes || output.size > limits.maxOutputBytes) {
                    return@coroutineScope RuntimeAttempt.Failure(
                        ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.OUTPUT_LIMIT)
                    )
                }

                val totalMicros = (System.nanoTime() - logicalStartedAt) / 1_000
                lastDiagnostics = parseDiagnostics(
                    reply = reply,
                    totalMicros = totalMicros,
                    generation = generation,
                    serviceBindMicros = serviceBindMicros,
                )
                RuntimeAttempt.Success(output)
            }
        } catch (cancelled: CancellationException) {
            fence(token, generation)
            sendCancel(activeSession, token, generation)
            terminate(activeSession)
            throw cancelled
        } catch (_: Exception) {
            fence(token, generation)
            if (!activeSession.binder.isBinderAlive) invalidate(activeSession)
            return RuntimeAttempt.Failure(
                ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
            )
        } finally {
            if (activeToken == token) activeToken = 0
            started.remove(token)
            pending.remove(token)
        }
    }

    /** Comparable same-process Wasmtime baseline. Never used by the production coordinator. */
    suspend fun executeInProcessForBenchmark(
        moduleDigest: String,
        moduleBytes: ByteArray,
        exportName: String,
        inputUtf8: ByteArray,
        limits: ExtensionExecutionLimits,
        clearCache: Boolean = false,
    ): Pair<ExtensionRuntimeResult, ExtensionRuntimeCallDiagnostics?> = withContext(Dispatchers.IO) {
        if (clearCache) WasmtimeNativeBridge.nativeClearModuleCache()
        val startedAt = System.nanoTime()
        try {
            val output = WasmtimeNativeBridge.nativeExecute(
                moduleDigest, moduleBytes, exportName, inputUtf8,
                limits.maxOutputBytes.toLong(), limits.memoryBytes.toLong(),
                limits.fuel, limits.deadlineMillis, nextInvocationId(),
            )
            val total = (System.nanoTime() - startedAt) / 1_000
            val metrics = parseNativeMetrics(
                raw = WasmtimeNativeBridge.nativeMetrics(),
                totalMicros = total,
                serviceBindMicros = 0,
                readMicros = 0,
                generation = 0,
            )
            ExtensionRuntimeResult.Success(output) to metrics
        } catch (error: RuntimeException) {
            ExtensionRuntimeResult.Failure(mapError(error.message)) to null
        }
    }

    suspend fun awaitActiveInvocationForTesting(timeoutMillis: Long = 5_000): Boolean {
        val token = activeToken
        if (token == 0L) return false
        val signal = started[token] ?: return false
        return withTimeoutOrNull(timeoutMillis) { signal.await(); true } ?: false
    }

    fun cancelActiveForTesting(): Boolean {
        val token = activeToken
        val activeSession = session ?: return false
        if (token == 0L) return false
        sendCancel(activeSession, token, activeSession.generation)
        return true
    }

    fun killServiceForTesting(): Boolean {
        val activeSession = session ?: return false
        return try {
            activeSession.messenger.send(Message.obtain(null, RuntimeProtocol.MSG_KILL))
            true
        } catch (_: RemoteException) {
            false
        }
    }

    suspend fun proveLateResultFenceForTesting(): Boolean {
        val fenced = lastFenced ?: return false
        val before = lateResultRejections.get()
        callbackMessenger.send(Message.obtain().apply {
            data = Bundle().apply {
                putLong(RuntimeProtocol.KEY_TOKEN, fenced.first)
                putLong(RuntimeProtocol.KEY_GENERATION, fenced.second)
                putString(RuntimeProtocol.KEY_STATUS, RuntimeProtocol.STATUS_OK)
                putInt(RuntimeProtocol.KEY_OUTPUT_BYTES, 0)
            }
        })
        repeat(20) {
            if (lateResultRejections.get() > before) return true
            delay(10)
        }
        return false
    }

    fun clearInProcessCacheForTesting() = WasmtimeNativeBridge.nativeClearModuleCache()

    private suspend fun acquireSession(): SessionAcquisition = sessionMutex.withLock {
        session?.takeIf { it.binder.isBinderAlive }?.let {
            return@withLock SessionAcquisition(it, bindMicros = 0)
        }
        val startedAt = System.nanoTime()
        val created = bind()
        session = created
        SessionAcquisition(
            session = created,
            bindMicros = (System.nanoTime() - startedAt) / 1_000,
        )
    }

    private suspend fun bind(): Session = suspendCancellableCoroutine { continuation ->
        val generation = nextGeneration.getAndIncrement()
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val created = Session(generation, service, Messenger(service), connection)
                try {
                    service.linkToDeath({
                        onBinderDeath(created)
                    }, 0)
                } catch (_: RemoteException) {
                    onBinderDeath(created)
                    if (continuation.isActive) continuation.resumeWithException(RemoteException("service already dead"))
                    return
                }
                if (continuation.isActive) continuation.resume(created)
                else runCatching { appContext.unbindService(connection) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                session?.takeIf { it.connection === connection }?.let(::onBinderDeath)
            }

            override fun onBindingDied(name: ComponentName) {
                session?.takeIf { it.connection === connection }?.let(::onBinderDeath)
            }
        }
        val bound = appContext.bindService(
            Intent(appContext, WasmtimeRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) continuation.resumeWithException(IllegalStateException("unable to bind isolated runtime"))
        continuation.invokeOnCancellation { if (bound) runCatching { appContext.unbindService(connection) } }
    }

    private fun onBinderDeath(dead: Session) {
        if (session?.binder === dead.binder) session = null
        dead.knownDigests.clear()
        pending.entries.toList().forEach { (token, value) ->
            if (value.generation == dead.generation && pending.remove(token, value)) {
                lastFenced = token to value.generation
                value.reply.complete(
                    RuntimeReply(
                        status = RuntimeProtocol.STATUS_DEAD,
                        error = "SERVICE_DEATH",
                        outputBytes = 0,
                        outputInline = null,
                        outputFd = null,
                        nativeMetrics = "{}",
                        readMicros = 0,
                        servicePid = 0,
                        serviceUid = 0,
                        serviceInternetPermissionGranted = false,
                    )
                )
            }
        }
    }

    private fun invalidate(value: Session) {
        if (session?.binder === value.binder) session = null
        value.knownDigests.clear()
        runCatching { appContext.unbindService(value.connection) }
    }

    private fun fence(token: Long, generation: Long) {
        pending.remove(token)?.let {
            lastFenced = token to generation
            it.reply.cancel()
        }
        started.remove(token)?.cancel()
    }

    private fun sendCancel(activeSession: Session, token: Long, generation: Long) {
        runCatching {
            activeSession.messenger.send(Message.obtain(null, RuntimeProtocol.MSG_CANCEL).apply {
                data = Bundle().apply {
                    putLong(RuntimeProtocol.KEY_TOKEN, token)
                    putLong(RuntimeProtocol.KEY_GENERATION, generation)
                }
            })
        }
    }

    /**
     * A timed-out or coroutine-cancelled invocation is no longer trusted to have stopped.
     * Killing only on failure keeps steady-state warm while guaranteeing the next invocation
     * cannot queue behind an unfenced guest.
     */
    private fun terminate(activeSession: Session) {
        runCatching {
            activeSession.messenger.send(Message.obtain(null, RuntimeProtocol.MSG_KILL))
        }
        invalidate(activeSession)
    }

    private fun parseDiagnostics(
        reply: RuntimeReply,
        totalMicros: Long,
        generation: Long,
        serviceBindMicros: Long,
    ): ExtensionRuntimeCallDiagnostics = parseNativeMetrics(
        raw = reply.nativeMetrics,
        totalMicros = totalMicros,
        serviceBindMicros = serviceBindMicros,
        readMicros = reply.readMicros,
        generation = generation,
        servicePid = reply.servicePid,
        serviceUid = reply.serviceUid,
        serviceInternetPermissionGranted = reply.serviceInternetPermissionGranted,
    )

    private fun parseNativeMetrics(
        raw: String,
        totalMicros: Long,
        serviceBindMicros: Long,
        readMicros: Long,
        generation: Long,
        servicePid: Int = android.os.Process.myPid(),
        serviceUid: Int = android.os.Process.myUid(),
        serviceInternetPermissionGranted: Boolean = true,
    ): ExtensionRuntimeCallDiagnostics {
        val json = JSONObject(raw)
        return ExtensionRuntimeCallDiagnostics(
            totalMicros = totalMicros,
            serviceBindMicros = serviceBindMicros,
            serviceReadMicros = readMicros,
            nativeMicros = json.optLong("nativeMicros"),
            guestMicros = json.optLong("guestMicros"),
            compileMicros = json.optLong("compileMicros"),
            instantiateMicros = json.optLong("instantiateMicros"),
            cacheHit = json.optBoolean("cacheHit"),
            diagnosticCalls = json.optInt("diagnosticCalls"),
            diagnosticBytes = json.optInt("diagnosticBytes"),
            serviceGeneration = generation,
            servicePid = servicePid,
            serviceUid = serviceUid,
            serviceInternetPermissionGranted = serviceInternetPermissionGranted,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val activeSession = session
        session = null
        if (activeSession != null) runCatching { appContext.unbindService(activeSession.connection) }
        pending.values.forEach { it.reply.cancel() }
        pending.clear()
        started.values.forEach { it.cancel() }
        started.clear()
        callbackThread.quitSafely()
    }

    private data class SessionAcquisition(
        val session: Session,
        val bindMicros: Long,
    )

    private data class Session(
        val generation: Long,
        val binder: IBinder,
        val messenger: Messenger,
        val connection: ServiceConnection,
        val knownDigests: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private data class Pending(val generation: Long, val reply: CompletableDeferred<RuntimeReply>)

    private sealed interface RuntimeAttempt {
        data class Success(val output: ByteArray) : RuntimeAttempt
        data class Failure(val result: ExtensionRuntimeResult.Failure) : RuntimeAttempt
        data object ModuleMiss : RuntimeAttempt
    }

    private companion object {
        private val NEXT_INVOCATION_ID = AtomicLong(1)

        fun nextInvocationId(): Long =
            NEXT_INVOCATION_ID.getAndIncrement().also {
                check(it > 0) { "runtime invocation id overflow" }
            }

        const val MAX_MODULE_BYTES = 8 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        const val MAX_MEMORY_BYTES = 32 * 1024 * 1024
        const val HOST_DEADLINE_GRACE_MILLIS = 750L
        const val INLINE_PAYLOAD_BYTES = 48 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")
        val ALLOWED_EXPORTS = setOf("plan_requests", "parse_responses", "plan_navigation", "parse_navigation")
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        fun readBounded(input: InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= maximum) { "pipe payload exceeds bound" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

/** Warm isolated process. Binder carries only control metadata and file descriptors. */
class WasmtimeRuntimeService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "arex-wasm-worker") }
    private lateinit var endpoint: Messenger

    override fun onCreate() {
        super.onCreate()
        endpoint = Messenger(Handler(mainLooper, ::receive))
    }

    override fun onBind(intent: Intent): IBinder = endpoint.binder

    private fun receive(message: Message): Boolean {
        when (message.what) {
            RuntimeProtocol.MSG_KILL -> {
                android.os.Process.killProcess(android.os.Process.myPid())
                return true
            }
            RuntimeProtocol.MSG_CANCEL -> {
                WasmtimeNativeBridge.nativeCancel(message.data.getLong(RuntimeProtocol.KEY_TOKEN))
                return true
            }
            RuntimeProtocol.MSG_EXECUTE -> {
                val reply = message.replyTo
                val data = message.data
                val token = data.getLong(RuntimeProtocol.KEY_TOKEN)
                val generation = data.getLong(RuntimeProtocol.KEY_GENERATION)
                worker.execute { execute(reply, token, generation, data) }
                return true
            }
        }
        return false
    }

    private fun execute(reply: Messenger, token: Long, generation: Long, data: Bundle) {
        respond(reply, token, generation, RuntimeProtocol.STATUS_STARTED)
        try {
            val readStarted = System.nanoTime()
            val module = data.getByteArray(RuntimeProtocol.KEY_MODULE_INLINE)
                ?: data.parcelFileDescriptor(RuntimeProtocol.KEY_MODULE_FD)?.let {
                    ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> readBounded(input, 8 * 1024 * 1024) }
                } ?: ByteArray(0)
            val input = data.getByteArray(RuntimeProtocol.KEY_INPUT_INLINE)
                ?: data.parcelFileDescriptor(RuntimeProtocol.KEY_INPUT_FD)?.let {
                    ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> readBounded(input, 4 * 1024 * 1024) }
                } ?: throw IllegalArgumentException("runtime input missing")
            val readMicros = (System.nanoTime() - readStarted) / 1_000
            val digest = requireNotNull(data.getString(RuntimeProtocol.KEY_DIGEST))
            require(SHA256.matches(digest)) { "invalid module digest" }
            if (module.isNotEmpty()) {
                require(sha256(module) == digest) { "module digest mismatch" }
            }

            val output = WasmtimeNativeBridge.nativeExecute(
                digest,
                module,
                requireNotNull(data.getString(RuntimeProtocol.KEY_EXPORT)),
                input,
                data.getLong(RuntimeProtocol.KEY_MAX_OUTPUT),
                data.getLong(RuntimeProtocol.KEY_MEMORY),
                data.getLong(RuntimeProtocol.KEY_FUEL),
                data.getLong(RuntimeProtocol.KEY_DEADLINE),
                token,
            )
            val nativeMetrics = WasmtimeNativeBridge.nativeMetrics()
            if (output.size <= INLINE_PAYLOAD_BYTES) {
                respond(reply, token, generation, RuntimeProtocol.STATUS_OK) {
                    putInt(RuntimeProtocol.KEY_OUTPUT_BYTES, output.size)
                    putByteArray(RuntimeProtocol.KEY_OUTPUT_INLINE, output)
                    putString(RuntimeProtocol.KEY_NATIVE_METRICS, nativeMetrics)
                    putLong(RuntimeProtocol.KEY_READ_MICROS, readMicros)
                    putInt(RuntimeProtocol.KEY_SERVICE_PID, android.os.Process.myPid())
                    putInt(RuntimeProtocol.KEY_SERVICE_UID, android.os.Process.myUid())
                    putBoolean(RuntimeProtocol.KEY_SERVICE_INTERNET,
                        checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }
            } else {
                val pipe = ParcelFileDescriptor.createPipe()
                val sent = respond(reply, token, generation, RuntimeProtocol.STATUS_OK) {
                    putInt(RuntimeProtocol.KEY_OUTPUT_BYTES, output.size)
                    putParcelable(RuntimeProtocol.KEY_OUTPUT_FD, pipe[0])
                    putString(RuntimeProtocol.KEY_NATIVE_METRICS, nativeMetrics)
                    putLong(RuntimeProtocol.KEY_READ_MICROS, readMicros)
                    putInt(RuntimeProtocol.KEY_SERVICE_PID, android.os.Process.myPid())
                    putInt(RuntimeProtocol.KEY_SERVICE_UID, android.os.Process.myUid())
                    putBoolean(RuntimeProtocol.KEY_SERVICE_INTERNET,
                        checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }
                pipe[0].close()
                if (sent) {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(output) }
                } else {
                    pipe[1].close()
                }
            }
        } catch (error: Throwable) {
            respond(reply, token, generation, RuntimeProtocol.STATUS_ERROR) {
                putString(RuntimeProtocol.KEY_ERROR, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
        // The service is warm for its bound lifetime. Once that lifecycle ends, discard all
        // compiled guest state rather than leaving a detached isolated process resident.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun respond(
        reply: Messenger,
        token: Long,
        generation: Long,
        status: String,
        block: Bundle.() -> Unit = {},
    ): Boolean = try {
        reply.send(Message.obtain().apply {
            data = Bundle().apply {
                putLong(RuntimeProtocol.KEY_TOKEN, token)
                putLong(RuntimeProtocol.KEY_GENERATION, generation)
                putString(RuntimeProtocol.KEY_STATUS, status)
                block()
            }
        })
        true
    } catch (_: RemoteException) {
        // The host fenced or died. Results are intentionally dropped.
        false
    }

    private fun Bundle.parcelFileDescriptor(key: String): ParcelFileDescriptor? {
        @Suppress("DEPRECATION")
        return getParcelable(key)
    }

    private companion object {
        const val INLINE_PAYLOAD_BYTES = 48 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun readBounded(input: InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(output.size() + count <= maximum)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}

internal object WasmtimeNativeBridge {
    init {
        System.loadLibrary("arex_runtime")
    }

    @JvmStatic external fun nativeValidate(moduleBytes: ByteArray): String?
    @JvmStatic external fun nativeExecute(
        moduleDigest: String,
        moduleBytes: ByteArray,
        exportName: String,
        inputUtf8: ByteArray,
        maxOutput: Long,
        memoryBytes: Long,
        fuel: Long,
        deadlineMillis: Long,
        invocationId: Long,
    ): ByteArray
    @JvmStatic external fun nativeCancel(invocationId: Long): Boolean
    @JvmStatic external fun nativeMetrics(): String
    @JvmStatic external fun nativeRuntimeVersion(): String
    @JvmStatic external fun nativeClearModuleCache()
}

private data class RuntimeReply(
    val status: String,
    val error: String?,
    val outputBytes: Int,
    val outputInline: ByteArray?,
    val outputFd: ParcelFileDescriptor?,
    val nativeMetrics: String,
    val readMicros: Long,
    val servicePid: Int,
    val serviceUid: Int,
    val serviceInternetPermissionGranted: Boolean,
) {
    companion object {
        fun from(bundle: Bundle) = RuntimeReply(
            status = bundle.getString(RuntimeProtocol.KEY_STATUS) ?: RuntimeProtocol.STATUS_ERROR,
            error = bundle.getString(RuntimeProtocol.KEY_ERROR),
            outputBytes = bundle.getInt(RuntimeProtocol.KEY_OUTPUT_BYTES),
            outputInline = bundle.getByteArray(RuntimeProtocol.KEY_OUTPUT_INLINE),
            outputFd = bundle.parcelFileDescriptorCompat(RuntimeProtocol.KEY_OUTPUT_FD),
            nativeMetrics = bundle.getString(RuntimeProtocol.KEY_NATIVE_METRICS) ?: "{}",
            readMicros = bundle.getLong(RuntimeProtocol.KEY_READ_MICROS),
            servicePid = bundle.getInt(RuntimeProtocol.KEY_SERVICE_PID),
            serviceUid = bundle.getInt(RuntimeProtocol.KEY_SERVICE_UID),
            serviceInternetPermissionGranted = bundle.getBoolean(RuntimeProtocol.KEY_SERVICE_INTERNET),
        )
    }
}

private fun Bundle.parcelFileDescriptorCompat(key: String): ParcelFileDescriptor? {
    @Suppress("DEPRECATION")
    return getParcelable(key)
}

private object RuntimeProtocol {
    const val MSG_EXECUTE = 1
    const val MSG_CANCEL = 2
    const val MSG_KILL = 3
    const val STATUS_STARTED = "started"
    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"
    const val STATUS_DEAD = "dead"
    const val ERROR_MODULE_MISS = "MODULE_MISS"

    const val KEY_TOKEN = "token"
    const val KEY_GENERATION = "generation"
    const val KEY_STATUS = "status"
    const val KEY_ERROR = "error"
    const val KEY_DIGEST = "digest"
    const val KEY_EXPORT = "export"
    const val KEY_MAX_OUTPUT = "maxOutput"
    const val KEY_MEMORY = "memory"
    const val KEY_FUEL = "fuel"
    const val KEY_DEADLINE = "deadline"
    const val KEY_INPUT_INLINE = "inputInline"
    const val KEY_INPUT_FD = "inputFd"
    const val KEY_MODULE_INLINE = "moduleInline"
    const val KEY_MODULE_FD = "moduleFd"
    const val KEY_OUTPUT_INLINE = "outputInline"
    const val KEY_OUTPUT_FD = "outputFd"
    const val KEY_OUTPUT_BYTES = "outputBytes"
    const val KEY_NATIVE_METRICS = "nativeMetrics"
    const val KEY_READ_MICROS = "readMicros"
    const val KEY_SERVICE_PID = "servicePid"
    const val KEY_SERVICE_UID = "serviceUid"
    const val KEY_SERVICE_INTERNET = "serviceInternet"
}

private fun mapError(message: String?): ExtensionRuntimeErrorCode = when {
    message == null -> ExtensionRuntimeErrorCode.TRAP
    "CANCELLED" in message -> ExtensionRuntimeErrorCode.CANCELLED
    "DEADLINE" in message -> ExtensionRuntimeErrorCode.DEADLINE
    "IMPORT_LIMIT" in message -> ExtensionRuntimeErrorCode.IMPORT_LIMIT
    "OUTPUT_LIMIT" in message -> ExtensionRuntimeErrorCode.OUTPUT_LIMIT
    "MEMORY_LIMIT" in message -> ExtensionRuntimeErrorCode.MEMORY_LIMIT
    "ABI_MISMATCH" in message -> ExtensionRuntimeErrorCode.ABI_MISMATCH
    "INVALID_INPUT" in message -> ExtensionRuntimeErrorCode.INVALID_INPUT
    else -> ExtensionRuntimeErrorCode.TRAP
}

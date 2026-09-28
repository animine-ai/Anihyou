package com.axiel7.anihyou.release.data.extension

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
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
 * miss and are keyed by the caller-verified immutable SHA-256 digest. Small bounded payloads use
 * Binder inline; larger payloads use pipes. Every guest call still gets a new Store/Instance.
 */
class AndroidIsolatedExtensionRuntime(context: Context) : ExtensionRuntime, AutoCloseable {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val sessionMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val nextGeneration = AtomicLong(1)
    private val transportThread = AtomicLong(1)
    private val transportExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "arex-binder-" + transportThread.getAndIncrement()).apply { isDaemon = true }
    }
    private val lateResultRejections = AtomicLong(0)

    @Volatile private var session: Session? = null
    @Volatile private var activeToken: Long = 0
    @Volatile private var lastFenced: FencedInvocation? = null
    @Volatile var lastDiagnostics: ExtensionRuntimeCallDiagnostics? = null
        private set

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
            limits.maxInputBytes > MAX_INPUT_BYTES || inputUtf8.size > limits.maxInputBytes ||
            limits.maxOutputBytes > MAX_OUTPUT_BYTES || limits.memoryBytes > MAX_MEMORY_BYTES ||
            limits.deadlineMillis > MAX_DEADLINE_MILLIS
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
     * One synchronous Binder request/reply per logical attempt. Binder work runs on a bounded host
     * executor so cancellation and the independent host deadline can fence and kill a wedged
     * isolated process without waiting for a blocked Binder transaction to return.
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

                val request = Bundle().apply {
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

                val replyDeferred = CompletableDeferred<RuntimeReply>()
                try {
                    transportExecutor.execute {
                        try {
                            replyDeferred.complete(transactExecute(activeSession.binder, request))
                        } catch (error: Throwable) {
                            replyDeferred.completeExceptionally(error)
                        } finally {
                            inputPipe?.get(0)?.close()
                            modulePipe?.get(0)?.close()
                        }
                    }
                } catch (rejected: RejectedExecutionException) {
                    inputPipe?.get(0)?.close()
                    modulePipe?.get(0)?.close()
                    throw rejected
                }

                val reply = withTimeoutOrNull(limits.deadlineMillis + HOST_DEADLINE_GRACE_MILLIS) {
                    replyDeferred.await()
                }
                if (reply == null) {
                    fence(activeSession, token, generation)
                    sendCancel(activeSession, token)
                    terminate(activeSession)
                    try { inputWriter?.await() } catch (_: Exception) {}
                    try { moduleWriter?.await() } catch (_: Exception) {}
                    return@coroutineScope RuntimeAttempt.Failure(
                        ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.DEADLINE)
                    )
                }

                inputWriter?.await()
                moduleWriter?.await()

                if (!acceptReply(activeSession, token, generation, reply)) {
                    reply.outputFd?.close()
                    return@coroutineScope RuntimeAttempt.Failure(
                        ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
                    )
                }

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
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                            readBounded(it, limits.maxOutputBytes)
                        }
                    } finally {
                        releaseOutput(activeSession, token)
                    }
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
            fence(activeSession, token, generation)
            sendCancel(activeSession, token)
            terminate(activeSession)
            throw cancelled
        } catch (_: Exception) {
            fence(activeSession, token, generation)
            if (!activeSession.binder.isBinderAlive) invalidate(activeSession)
            return RuntimeAttempt.Failure(
                ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
            )
        } finally {
            if (activeToken == token) activeToken = 0
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
        val activeSession = session ?: return false
        if (token == 0L) return false
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (session?.binder !== activeSession.binder || !activeSession.binder.isBinderAlive) return false
            val active = withContext(Dispatchers.IO) {
                runCatching {
                    transactBoolean(activeSession.binder, RuntimeProtocol.TX_IS_ACTIVE, token)
                }.getOrDefault(false)
            }
            if (active) return true
            delay(5)
        }
        return false
    }

    fun cancelActiveForTesting(): Boolean {
        val token = activeToken
        val activeSession = session ?: return false
        if (token == 0L) return false
        return runCatching {
            transactBoolean(activeSession.binder, RuntimeProtocol.TX_CANCEL, token)
        }.getOrDefault(false)
    }

    fun killServiceForTesting(): Boolean {
        val activeSession = session ?: return false
        return runCatching {
            transactOneWay(activeSession.binder, RuntimeProtocol.TX_KILL)
            true
        }.getOrDefault(false)
    }

    fun proveLateResultFenceForTesting(): Boolean {
        val fenced = lastFenced ?: return false
        val before = lateResultRejections.get()
        val fake = RuntimeReply(
            token = fenced.token,
            generation = fenced.generation,
            status = RuntimeProtocol.STATUS_OK,
            error = null,
            outputBytes = 0,
            outputInline = ByteArray(0),
            outputFd = null,
            nativeMetrics = "{}",
            readMicros = 0,
            servicePid = 0,
            serviceUid = 0,
            serviceInternetPermissionGranted = false,
        )
        val accepted = acceptReply(fenced.session, fenced.token, fenced.generation, fake)
        return !accepted && lateResultRejections.get() > before
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
                val created = Session(generation, service, connection)
                try {
                    service.linkToDeath({ onBinderDeath(created) }, 0)
                } catch (_: RemoteException) {
                    onBinderDeath(created)
                    if (continuation.isActive) {
                        continuation.resumeWithException(RemoteException("service already dead"))
                    }
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
        continuation.invokeOnCancellation {
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }

    private fun onBinderDeath(dead: Session) {
        if (session?.binder === dead.binder) session = null
        dead.knownDigests.clear()
        val token = activeToken
        if (token != 0L) lastFenced = FencedInvocation(token, dead.generation, dead)
    }

    private fun invalidate(value: Session) {
        if (session?.binder === value.binder) session = null
        value.knownDigests.clear()
        runCatching { appContext.unbindService(value.connection) }
    }

    private fun fence(activeSession: Session, token: Long, generation: Long) {
        lastFenced = FencedInvocation(token, generation, activeSession)
    }

    private fun acceptReply(
        activeSession: Session,
        token: Long,
        generation: Long,
        reply: RuntimeReply,
    ): Boolean {
        val current = session
        val accepted = reply.token == token &&
            reply.generation == generation &&
            current?.binder === activeSession.binder &&
            current.generation == generation &&
            activeSession.binder.isBinderAlive
        if (!accepted) lateResultRejections.incrementAndGet()
        return accepted
    }

    private fun sendCancel(activeSession: Session, token: Long) {
        runCatching { transactBoolean(activeSession.binder, RuntimeProtocol.TX_CANCEL, token) }
    }

    private fun releaseOutput(activeSession: Session, token: Long) {
        runCatching { transactBoolean(activeSession.binder, RuntimeProtocol.TX_RELEASE_OUTPUT, token) }
    }

    private fun terminate(activeSession: Session) {
        runCatching { transactOneWay(activeSession.binder, RuntimeProtocol.TX_KILL) }
        invalidate(activeSession)
    }

    private fun transactExecute(binder: IBinder, request: Bundle): RuntimeReply {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
            data.writeBundle(request)
            check(binder.transact(RuntimeProtocol.TX_EXECUTE, data, reply, 0)) {
                "isolated runtime rejected execute transaction"
            }
            reply.readException()
            val response = reply.readBundle(WasmtimeRuntimeService::class.java.classLoader)
                ?: error("isolated runtime returned no response")
            return RuntimeReply.from(response)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactBoolean(binder: IBinder, code: Int, token: Long): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
            data.writeLong(token)
            check(binder.transact(code, data, reply, 0)) {
                "isolated runtime rejected control transaction"
            }
            reply.readException()
            return reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactOneWay(binder: IBinder, code: Int) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
            check(binder.transact(code, data, null, IBinder.FLAG_ONEWAY)) {
                "isolated runtime rejected one-way transaction"
            }
        } finally {
            data.recycle()
        }
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
        transportExecutor.shutdownNow()
    }

    private data class SessionAcquisition(
        val session: Session,
        val bindMicros: Long,
    )

    private data class Session(
        val generation: Long,
        val binder: IBinder,
        val connection: ServiceConnection,
        val knownDigests: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private data class FencedInvocation(
        val token: Long,
        val generation: Long,
        val session: Session,
    )

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
        const val MAX_INPUT_BYTES = 4 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        const val MAX_MEMORY_BYTES = 32 * 1024 * 1024
        const val MAX_DEADLINE_MILLIS = 60_000L
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

/** Warm isolated process. One Binder roundtrip carries the steady-state control path. */
class WasmtimeRuntimeService : Service() {
    private val executionLock = ReentrantLock()
    private val activeToken = AtomicLong(0)
    private val outputWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "arex-output-writer").apply { isDaemon = true }
    }
    private val retainedOutputReaders = ConcurrentHashMap<Long, ParcelFileDescriptor>()

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(RuntimeProtocol.DESCRIPTOR)
                return true
            }
            data.enforceInterface(RuntimeProtocol.DESCRIPTOR)
            return try {
                when (code) {
                    RuntimeProtocol.TX_EXECUTE -> {
                        val request = data.readBundle(WasmtimeRuntimeService::class.java.classLoader)
                            ?: throw IllegalArgumentException("runtime request missing")
                        val response = executeRequest(request)
                        requireNotNull(reply).writeNoException()
                        reply.writeBundle(response)
                        true
                    }
                    RuntimeProtocol.TX_CANCEL -> {
                        val token = data.readLong()
                        val cancelled = activeToken.get() == token &&
                            WasmtimeNativeBridge.nativeCancel(token)
                        requireNotNull(reply).writeNoException()
                        reply.writeInt(if (cancelled) 1 else 0)
                        true
                    }
                    RuntimeProtocol.TX_IS_ACTIVE -> {
                        val token = data.readLong()
                        requireNotNull(reply).writeNoException()
                        reply.writeInt(if (activeToken.get() == token) 1 else 0)
                        true
                    }
                    RuntimeProtocol.TX_RELEASE_OUTPUT -> {
                        val token = data.readLong()
                        retainedOutputReaders.remove(token)?.close()
                        requireNotNull(reply).writeNoException()
                        reply.writeInt(1)
                        true
                    }
                    RuntimeProtocol.TX_KILL -> {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            } catch (error: Throwable) {
                if (reply != null) {
                    reply.writeException(IllegalStateException(error.message ?: error.javaClass.simpleName))
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = endpoint

    private fun executeRequest(request: Bundle): Bundle {
        executionLock.lock()
        try {
            val token = request.getLong(RuntimeProtocol.KEY_TOKEN)
            val generation = request.getLong(RuntimeProtocol.KEY_GENERATION)
            require(token > 0 && generation > 0) { "invalid runtime invocation identity" }

            val readStarted = System.nanoTime()
            val module = request.getByteArray(RuntimeProtocol.KEY_MODULE_INLINE)
                ?: request.parcelFileDescriptor(RuntimeProtocol.KEY_MODULE_FD)?.let {
                    ParcelFileDescriptor.AutoCloseInputStream(it).use { input ->
                        readBounded(input, MAX_MODULE_BYTES)
                    }
                } ?: ByteArray(0)
            val input = request.getByteArray(RuntimeProtocol.KEY_INPUT_INLINE)
                ?: request.parcelFileDescriptor(RuntimeProtocol.KEY_INPUT_FD)?.let {
                    ParcelFileDescriptor.AutoCloseInputStream(it).use { input ->
                        readBounded(input, MAX_INPUT_BYTES)
                    }
                } ?: throw IllegalArgumentException("runtime input missing")
            val readMicros = (System.nanoTime() - readStarted) / 1_000

            val digest = requireNotNull(request.getString(RuntimeProtocol.KEY_DIGEST))
            require(SHA256.matches(digest)) { "invalid module digest" }
            if (module.isNotEmpty()) require(sha256(module) == digest) { "module digest mismatch" }

            activeToken.set(token)
            val output = try {
                WasmtimeNativeBridge.nativeExecute(
                    digest,
                    module,
                    requireNotNull(request.getString(RuntimeProtocol.KEY_EXPORT)),
                    input,
                    request.getLong(RuntimeProtocol.KEY_MAX_OUTPUT),
                    request.getLong(RuntimeProtocol.KEY_MEMORY),
                    request.getLong(RuntimeProtocol.KEY_FUEL),
                    request.getLong(RuntimeProtocol.KEY_DEADLINE),
                    token,
                )
            } finally {
                activeToken.compareAndSet(token, 0)
            }
            val nativeMetrics = WasmtimeNativeBridge.nativeMetrics()

            return response(token, generation, RuntimeProtocol.STATUS_OK) {
                putInt(RuntimeProtocol.KEY_OUTPUT_BYTES, output.size)
                putString(RuntimeProtocol.KEY_NATIVE_METRICS, nativeMetrics)
                putLong(RuntimeProtocol.KEY_READ_MICROS, readMicros)
                putInt(RuntimeProtocol.KEY_SERVICE_PID, android.os.Process.myPid())
                putInt(RuntimeProtocol.KEY_SERVICE_UID, android.os.Process.myUid())
                putBoolean(
                    RuntimeProtocol.KEY_SERVICE_INTERNET,
                    checkSelfPermission(android.Manifest.permission.INTERNET) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED,
                )
                if (output.size <= INLINE_PAYLOAD_BYTES) {
                    putByteArray(RuntimeProtocol.KEY_OUTPUT_INLINE, output)
                } else {
                    putParcelable(RuntimeProtocol.KEY_OUTPUT_FD, retainOutputPipe(token, output))
                }
            }
        } catch (error: Throwable) {
            val token = request.getLong(RuntimeProtocol.KEY_TOKEN)
            val generation = request.getLong(RuntimeProtocol.KEY_GENERATION)
            return response(token, generation, RuntimeProtocol.STATUS_ERROR) {
                putString(RuntimeProtocol.KEY_ERROR, error.message ?: error.javaClass.simpleName)
            }
        } finally {
            executionLock.unlock()
        }
    }

    private fun retainOutputPipe(token: Long, output: ByteArray): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        retainedOutputReaders.put(token, pipe[0])?.close()
        try {
            outputWriter.execute {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(output) }
                } catch (_: Exception) {
                    runCatching { pipe[1].close() }
                }
            }
        } catch (error: RejectedExecutionException) {
            retainedOutputReaders.remove(token)?.close()
            pipe[1].close()
            throw error
        }
        return pipe[0]
    }

    override fun onDestroy() {
        retainedOutputReaders.values.forEach { runCatching { it.close() } }
        retainedOutputReaders.clear()
        outputWriter.shutdownNow()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun response(
        token: Long,
        generation: Long,
        status: String,
        block: Bundle.() -> Unit = {},
    ): Bundle = Bundle().apply {
        putLong(RuntimeProtocol.KEY_TOKEN, token)
        putLong(RuntimeProtocol.KEY_GENERATION, generation)
        putString(RuntimeProtocol.KEY_STATUS, status)
        block()
    }

    private fun Bundle.parcelFileDescriptor(key: String): ParcelFileDescriptor? {
        @Suppress("DEPRECATION")
        return getParcelable(key)
    }

    private companion object {
        const val INLINE_PAYLOAD_BYTES = 48 * 1024
        const val MAX_MODULE_BYTES = 8 * 1024 * 1024
        const val MAX_INPUT_BYTES = 4 * 1024 * 1024
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
    val token: Long,
    val generation: Long,
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
            token = bundle.getLong(RuntimeProtocol.KEY_TOKEN),
            generation = bundle.getLong(RuntimeProtocol.KEY_GENERATION),
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
    const val DESCRIPTOR = "com.axiel7.anihyou.release.data.extension.IWasmtimeRuntime"
    const val TX_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    const val TX_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_IS_ACTIVE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_RELEASE_OUTPUT = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TX_KILL = IBinder.FIRST_CALL_TRANSACTION + 4

    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"
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

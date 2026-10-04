package com.axiel7.anihyou.release.data.extension

import android.os.Build
import com.axiel7.anihyou.release.core.log.AppLog
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
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
    private val lateResultRejections = AtomicLong(0)

    @Volatile private var session: Session? = null
    /** Why the isolated service could not be reached in the last call; null when it was reached. */
    @Volatile var lastStartFailure: String? = null
        private set
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
    ): ExtensionRuntimeResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return withContext(Dispatchers.IO) {
                executeOffMain(moduleDigest, moduleBytes, exportName, inputUtf8, limits)
            }
        }
        return executeOffMain(moduleDigest, moduleBytes, exportName, inputUtf8, limits)
    }

    private suspend fun executeOffMain(
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
        lastStartFailure = null
        AppLog.d("runtime") { "execute $exportName digest=${AppLog.short(moduleDigest)} module=${moduleBytes.size}B input=${inputUtf8.size}B deadline=${limits.deadlineMillis}ms fuel=${limits.fuel}" }
        val acquisition = try {
            acquireSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lastStartFailure = error.message ?: error.javaClass.simpleName
            AppLog.e("runtime", error) { "isolated service not reachable: $lastStartFailure" }
            logRecentProcessExits()
            return@withLock ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
        }
        AppLog.d("runtime") { "service ready bind=${acquisition.bindMicros} us generation=${acquisition.session.generation}" }
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
                    AppLog.d("runtime") { "execute ok $exportName output=${attempt.output.size}B total=${(System.nanoTime() - logicalStartedAt) / 1_000} us" }
                    return@withLock ExtensionRuntimeResult.Success(attempt.output)
                }
                RuntimeAttempt.ModuleMiss -> {
                    activeSession.knownDigests.remove(moduleDigest)
                    if (sendModule || attemptIndex != 0) {
                        return@withLock ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
                    }
                    sendModule = true
                }
                is RuntimeAttempt.Failure -> {
                    AppLog.w("runtime") { "execute failed $exportName result=${attempt.result} total=${(System.nanoTime() - logicalStartedAt) / 1_000} us" }
                    return@withLock attempt.result
                }
            }
        }
        ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
    }

    /**
     * One synchronous Binder request/reply per logical attempt. The transaction runs directly on
     * the caller thread to avoid an extra scheduling hop. The cancellable continuation is already
     * registered with the parent job while Binder is blocked, so timeout/cancellation can arrive
     * on another thread, cancel the guest and kill/fence the isolated process fail-closed.
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

                val request = RuntimeRequest(
                    token = token,
                    generation = generation,
                    moduleDigest = moduleDigest,
                    exportName = exportName,
                    maxOutputBytes = limits.maxOutputBytes.toLong(),
                    memoryBytes = limits.memoryBytes.toLong(),
                    fuel = limits.fuel,
                    deadlineMillis = limits.deadlineMillis,
                    inputInline = inputUtf8.takeIf { inputInline },
                    inputFd = inputPipe?.get(0),
                    moduleInline = moduleBytes.takeIf { moduleInline },
                    moduleFd = modulePipe?.get(0),
                )

                val reply = withTimeoutOrNull(limits.deadlineMillis + HOST_DEADLINE_GRACE_MILLIS) {
                    suspendCancellableCoroutine<RuntimeReply> { continuation ->
                        continuation.invokeOnCancellation {
                            abortInvocation(activeSession, token, generation)
                        }
                        try {
                            val response = transactExecute(activeSession.binder, request)
                            if (continuation.isActive) {
                                continuation.resume(response)
                            } else {
                                response.outputFd?.close()
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        } finally {
                            inputPipe?.get(0)?.close()
                            modulePipe?.get(0)?.close()
                        }
                    }
                }
                if (reply == null) {
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
            abortInvocation(activeSession, token, generation)
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

    suspend fun awaitActiveInvocationForTesting(timeoutMillis: Long = 5_000): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val token = activeToken
                val activeSession = session
                if (token != 0L && activeSession != null && activeSession.binder.isBinderAlive) {
                    val active = withContext(Dispatchers.IO) {
                        runCatching {
                            transactBoolean(activeSession.binder, RuntimeProtocol.TX_IS_ACTIVE, token)
                        }.getOrDefault(false)
                    }
                    if (active) return@withTimeoutOrNull true
                }
                delay(5)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

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
        AppLog.i("runtime") { "binding isolated service (timeout ${BIND_TIMEOUT_MILLIS} ms)" }
        val created = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { bind() }
            ?: throw IllegalStateException("isolated service did not connect within ${BIND_TIMEOUT_MILLIS} ms")
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
                AppLog.i("runtime") { "isolated service connected generation=$generation" }
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
                AppLog.w("runtime") { "isolated service disconnected" }
                session?.takeIf { it.connection === connection }?.let(::onBinderDeath)
            }

            override fun onBindingDied(name: ComponentName) {
                AppLog.w("runtime") { "isolated service binding died" }
                session?.takeIf { it.connection === connection }?.let(::onBinderDeath)
                // A binding that dies before it ever connected would otherwise leave the caller waiting forever.
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("isolated service binding died before it connected"))
                }
            }

            override fun onNullBinding(name: ComponentName) {
                AppLog.w("runtime") { "isolated service returned no binder" }
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("isolated service returned no binder"))
                }
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

    /** The isolated process cannot write to our logcat filter, so the system's own exit record is the evidence. */
    private fun logRecentProcessExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = appContext.getSystemService(android.app.ActivityManager::class.java) ?: return
            manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 6).forEach { exit ->
                AppLog.w("runtime") {
                    "process exit name=${exit.processName} reason=${exit.reason} status=${exit.status} " +
                        "importance=${exit.importance} at=${exit.timestamp} description=${exit.description}"
                }
            }
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

    private fun abortInvocation(activeSession: Session, token: Long, generation: Long) {
        fence(activeSession, token, generation)
        if (session?.binder === activeSession.binder) {
            sendCancel(activeSession, token)
            terminate(activeSession)
        }
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

    private fun transactExecute(binder: IBinder, request: RuntimeRequest): RuntimeReply {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RuntimeProtocol.DESCRIPTOR)
            request.writeTo(data)
            check(binder.transact(RuntimeProtocol.TX_EXECUTE, data, reply, 0)) {
                "isolated runtime rejected execute transaction"
            }
            reply.readException()
            return RuntimeReply.readFrom(reply)
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
        /** Starting an isolated process takes well under a second; a service that is not up by now will not come. */
        const val BIND_TIMEOUT_MILLIS = 15_000L
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
                        val request = RuntimeRequest.readFrom(data)
                        val response = executeRequest(request)
                        requireNotNull(reply).writeNoException()
                        response.writeTo(reply)
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

    private fun executeRequest(request: RuntimeRequest): RuntimeReply {
        executionLock.lock()
        try {
            require(request.token > 0 && request.generation > 0) {
                "invalid runtime invocation identity"
            }

            val readStarted = System.nanoTime()
            val module = request.moduleInline
                ?: request.moduleFd?.let { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        readBounded(input, MAX_MODULE_BYTES)
                    }
                } ?: ByteArray(0)
            val input = request.inputInline
                ?: request.inputFd?.let { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        readBounded(input, MAX_INPUT_BYTES)
                    }
                } ?: throw IllegalArgumentException("runtime input missing")
            val readMicros = (System.nanoTime() - readStarted) / 1_000

            require(SHA256.matches(request.moduleDigest)) { "invalid module digest" }
            if (module.isNotEmpty()) {
                require(sha256(module) == request.moduleDigest) { "module digest mismatch" }
            }

            activeToken.set(request.token)
            val output = try {
                WasmtimeNativeBridge.nativeExecute(
                    request.moduleDigest,
                    module,
                    request.exportName,
                    input,
                    request.maxOutputBytes,
                    request.memoryBytes,
                    request.fuel,
                    request.deadlineMillis,
                    request.token,
                )
            } finally {
                activeToken.compareAndSet(request.token, 0)
            }
            val nativeMetrics = WasmtimeNativeBridge.nativeMetrics()
            val outputFd = if (output.size > INLINE_PAYLOAD_BYTES) {
                retainOutputPipe(request.token, output)
            } else {
                null
            }
            return RuntimeReply(
                token = request.token,
                generation = request.generation,
                status = RuntimeProtocol.STATUS_OK,
                error = null,
                outputBytes = output.size,
                outputInline = output.takeIf { outputFd == null },
                outputFd = outputFd,
                nativeMetrics = nativeMetrics,
                readMicros = readMicros,
                servicePid = android.os.Process.myPid(),
                serviceUid = android.os.Process.myUid(),
                serviceInternetPermissionGranted =
                    checkSelfPermission(android.Manifest.permission.INTERNET) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        } catch (error: Throwable) {
            return RuntimeReply(
                token = request.token,
                generation = request.generation,
                status = RuntimeProtocol.STATUS_ERROR,
                error = error.message ?: error.javaClass.simpleName,
                outputBytes = 0,
                outputInline = null,
                outputFd = null,
                nativeMetrics = "{}",
                readMicros = 0,
                servicePid = android.os.Process.myPid(),
                serviceUid = android.os.Process.myUid(),
                serviceInternetPermissionGranted = false,
            )
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

private data class RuntimeRequest(
    val token: Long,
    val generation: Long,
    val moduleDigest: String,
    val exportName: String,
    val maxOutputBytes: Long,
    val memoryBytes: Long,
    val fuel: Long,
    val deadlineMillis: Long,
    val inputInline: ByteArray?,
    val inputFd: ParcelFileDescriptor?,
    val moduleInline: ByteArray?,
    val moduleFd: ParcelFileDescriptor?,
) {
    fun writeTo(parcel: Parcel) {
        parcel.writeLong(token)
        parcel.writeLong(generation)
        parcel.writeString(moduleDigest)
        parcel.writeString(exportName)
        parcel.writeLong(maxOutputBytes)
        parcel.writeLong(memoryBytes)
        parcel.writeLong(fuel)
        parcel.writeLong(deadlineMillis)
        writePayload(parcel, inputInline, inputFd, allowMissing = false)
        writePayload(parcel, moduleInline, moduleFd, allowMissing = true)
    }

    companion object {
        fun readFrom(parcel: Parcel): RuntimeRequest {
            val token = parcel.readLong()
            val generation = parcel.readLong()
            val moduleDigest = requireNotNull(parcel.readString()) { "module digest missing" }
            val exportName = requireNotNull(parcel.readString()) { "export name missing" }
            val maxOutputBytes = parcel.readLong()
            val memoryBytes = parcel.readLong()
            val fuel = parcel.readLong()
            val deadlineMillis = parcel.readLong()
            val input = readPayload(parcel, allowMissing = false)
            val module = readPayload(parcel, allowMissing = true)
            return RuntimeRequest(
                token = token,
                generation = generation,
                moduleDigest = moduleDigest,
                exportName = exportName,
                maxOutputBytes = maxOutputBytes,
                memoryBytes = memoryBytes,
                fuel = fuel,
                deadlineMillis = deadlineMillis,
                inputInline = input.first,
                inputFd = input.second,
                moduleInline = module.first,
                moduleFd = module.second,
            )
        }

        private fun readPayload(parcel: Parcel, allowMissing: Boolean): Pair<ByteArray?, ParcelFileDescriptor?> =
            when (val mode = parcel.readInt()) {
                RuntimeProtocol.PAYLOAD_NONE -> {
                    require(allowMissing) { "required runtime payload missing" }
                    null to null
                }
                RuntimeProtocol.PAYLOAD_INLINE -> requireNotNull(parcel.createByteArray()) {
                    "inline runtime payload missing"
                } to null
                RuntimeProtocol.PAYLOAD_FD -> null to ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
                else -> throw IllegalArgumentException("invalid runtime payload mode: $mode")
            }

        private fun writePayload(
            parcel: Parcel,
            inline: ByteArray?,
            fd: ParcelFileDescriptor?,
            allowMissing: Boolean,
        ) {
            when {
                inline != null -> {
                    require(fd == null) { "payload has both inline bytes and fd" }
                    parcel.writeInt(RuntimeProtocol.PAYLOAD_INLINE)
                    parcel.writeByteArray(inline)
                }
                fd != null -> {
                    parcel.writeInt(RuntimeProtocol.PAYLOAD_FD)
                    fd.writeToParcel(parcel, 0)
                }
                allowMissing -> parcel.writeInt(RuntimeProtocol.PAYLOAD_NONE)
                else -> error("required runtime payload missing")
            }
        }
    }
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
    fun writeTo(parcel: Parcel) {
        parcel.writeLong(token)
        parcel.writeLong(generation)
        parcel.writeString(status)
        parcel.writeString(error)
        parcel.writeInt(outputBytes)
        when {
            outputInline != null -> {
                require(outputFd == null)
                parcel.writeInt(RuntimeProtocol.PAYLOAD_INLINE)
                parcel.writeByteArray(outputInline)
            }
            outputFd != null -> {
                parcel.writeInt(RuntimeProtocol.PAYLOAD_FD)
                outputFd.writeToParcel(parcel, 0)
            }
            else -> parcel.writeInt(RuntimeProtocol.PAYLOAD_NONE)
        }
        parcel.writeString(nativeMetrics)
        parcel.writeLong(readMicros)
        parcel.writeInt(servicePid)
        parcel.writeInt(serviceUid)
        parcel.writeInt(if (serviceInternetPermissionGranted) 1 else 0)
    }

    companion object {
        fun readFrom(parcel: Parcel): RuntimeReply {
            val token = parcel.readLong()
            val generation = parcel.readLong()
            val status = requireNotNull(parcel.readString()) { "runtime status missing" }
            val error = parcel.readString()
            val outputBytes = parcel.readInt()
            var outputInline: ByteArray? = null
            var outputFd: ParcelFileDescriptor? = null
            when (val mode = parcel.readInt()) {
                RuntimeProtocol.PAYLOAD_NONE -> Unit
                RuntimeProtocol.PAYLOAD_INLINE ->
                    outputInline = requireNotNull(parcel.createByteArray()) { "runtime output missing" }
                RuntimeProtocol.PAYLOAD_FD ->
                    outputFd = ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
                else -> throw IllegalArgumentException("invalid runtime output mode: $mode")
            }
            return RuntimeReply(
                token = token,
                generation = generation,
                status = status,
                error = error,
                outputBytes = outputBytes,
                outputInline = outputInline,
                outputFd = outputFd,
                nativeMetrics = requireNotNull(parcel.readString()) { "runtime metrics missing" },
                readMicros = parcel.readLong(),
                servicePid = parcel.readInt(),
                serviceUid = parcel.readInt(),
                serviceInternetPermissionGranted = parcel.readInt() != 0,
            )
        }
    }
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

    const val PAYLOAD_NONE = 0
    const val PAYLOAD_INLINE = 1
    const val PAYLOAD_FD = 2

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

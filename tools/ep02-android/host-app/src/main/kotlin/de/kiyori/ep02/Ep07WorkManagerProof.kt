package de.kiyori.ep02

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.axiel7.anihyou.feature.worker.ExtensionReleaseRefreshWorker
import com.axiel7.anihyou.feature.worker.WorkManagerExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.data.extension.FileExtensionRefreshScheduleStore
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** TEST-only injection; real WorkManager database, scheduling and product CoroutineWorker. */
internal object Ep07WorkManagerProof {
    private lateinit var manager: WorkManager
    private lateinit var schedules: FileExtensionRefreshScheduleStore
    private lateinit var scheduler: WorkManagerExtensionReleaseRefreshScheduler
    @Volatile private var coordinator: ExtensionReleaseRefreshCoordinator? = null
    val completions = AtomicInteger()
    @Volatile var lastOutcome: ShadowRefreshOutcome? = null
    /** Diagnostics only: how often WorkManager created the product worker, and what the coordinator last threw. */
    private val workersCreated = AtomicInteger()
    @Volatile private var lastError: String? = null

    private fun initialize(context: Context) {
        if (::manager.isInitialized) return
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != ExtensionReleaseRefreshWorker::class.java.name) return null
                workersCreated.incrementAndGet()
                return ExtensionReleaseRefreshWorker(appContext, parameters, object : ExtensionReleaseRefreshCoordinator {
                    override suspend fun refresh(workId: String, trigger: ExtensionRefreshTrigger): ShadowRefreshOutcome {
                        val outcome = try { requireNotNull(coordinator).refresh(workId, trigger) } catch (error: Throwable) {
                            lastError = error.javaClass.name + ": " + error.message + " @ " +
                                error.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                            throw error
                        }
                        lastOutcome = outcome
                        completions.incrementAndGet()
                        return outcome
                    }
                }, scheduler, schedules)
            }
        }
        WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(factory).build())
        manager = WorkManager.getInstance(context)
        schedules = FileExtensionRefreshScheduleStore(File(context.filesDir, "ep07-proof-refresh-schedule"))
        scheduler = WorkManagerExtensionReleaseRefreshScheduler(manager, schedules)
    }

    /**
     * Process start through the real scheduler and the real product CoroutineWorker: the automatic run of a
     * fresh process and the slot chain it leaves behind. The slot work itself is hours away and never runs here.
     */
    suspend fun due(context: Context, actual: ExtensionReleaseRefreshCoordinator, twice: Boolean = false,
        leaveSlotForRestart: Boolean = false): ShadowRefreshOutcome {
        if (Build.VERSION.SDK_INT >= 26) awaitValidatedNetwork(context)
        coordinator = actual
        initialize(context)
        withContext(Dispatchers.IO) {
            manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG).result.get(10, TimeUnit.SECONDS)
        }
        lastOutcome = null
        val before = completions.get()
        scheduler.scheduleDue()
        if (twice) scheduler.scheduleDue()
        var waitStage = "first product worker completion"
        try {
            withTimeout(30_000) {
                while (completions.get() == before) delay(100)
                waitStage = "automatic work terminal state"
                // Wait for the automatic work, so a second startup check cannot escape into a later proof.
                while (withContext(Dispatchers.IO) {
                    manager.getWorkInfosForUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.AUTO)
                        .get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
                }) delay(100)
                waitStage = "future slot queued"
                // The slot chain is enqueued asynchronously: one future slot, no periodic job.
                while (withContext(Dispatchers.IO) { pendingSlots().isEmpty() }) delay(100)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val workSnapshot = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                val auto = runCatching {
                    manager.getWorkInfosForUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.AUTO)
                        .get(2, TimeUnit.SECONDS)
                        .joinToString(prefix = "[", postfix = "]") { "${it.id}:${it.state}:tags=${it.tags}" }
                }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
                val slots = runCatching {
                    manager.getWorkInfosByTag(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG)
                        .get(2, TimeUnit.SECONDS)
                        .joinToString(prefix = "[", postfix = "]") { "${it.id}:${it.state}:tags=${it.tags}" }
                }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
                "auto=$auto slots=$slots schedule=${schedules.schedule.value} scheduling=${describeScheduling(context)} workersCreated=${workersCreated.get()} lastError=$lastError"
            }
            throw IllegalStateException(
                "EP07 WorkManager proof timed out at '$waitStage'; completions=${completions.get()} " +
                    "before=$before outcome=$lastOutcome; $workSnapshot",
                timeout,
            )
        }
        check(withContext(Dispatchers.IO) {
            manager.getWorkInfosForUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.LEGACY_PERIODIC)
                .get(10, TimeUnit.SECONDS).none { !it.state.isFinished }
        }) { "the hourly periodic work must not exist" }
        if (!leaveSlotForRestart) {
            scheduler.cancel()
            withContext(Dispatchers.IO) {
                manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG).result.get(10, TimeUnit.SECONDS)
            }
        }
        return requireNotNull(lastOutcome)
    }

    /**
     * The product worker keeps its CONNECTED constraint, so this proof needs a network the OS itself validated.
     * The validation probe is asynchronous and can be re-evaluated, so a single snapshot is racy. Wait a bounded
     * time for the real capability, hint once through the public reportNetworkConnectivity API that the network
     * works (this only asks the OS to re-evaluate, it cannot set the capability), and fail with the observed
     * state. Nothing here fakes or bypasses VALIDATED.
     */
    private suspend fun awaitValidatedNetwork(context: Context, timeoutMillis: Long = 30_000) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val started = SystemClock.elapsedRealtime()
        val deadline = started + timeoutMillis
        var hinted = false
        while (true) {
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                // The app process's own view of the default network, for the stability evidence of the EP02 series.
                Log.i("EP02NETWORK", "appDefaultNetwork ${describe(capabilities)} waitedMs=${SystemClock.elapsedRealtime() - started} " +
                    "hinted=$hinted all=${describeAll(connectivity)}")
                return
            }
            if (!hinted && network != null) {
                connectivity.reportNetworkConnectivity(network, true)
                hinted = true
            }
            check(SystemClock.elapsedRealtime() < deadline) {
                "hermetic network was not Android-validated within $timeoutMillis ms before product WorkManager " +
                    "scheduling: network=$network capabilities=$capabilities all=${describeAll(connectivity)}"
            }
            delay(500)
        }
    }

    /**
     * Why a work stays ENQUEUED: what the OS job scheduler holds for this package, what the OS thinks of the
     * process and the network, and whether the main thread (which delivers constraint callbacks) is blocked.
     * Observation only; nothing here changes scheduling.
     */
    @Suppress("DEPRECATION")
    private fun describeScheduling(context: Context): String = runCatching {
        val jobs = runCatching {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
            jobScheduler.allPendingJobs.joinToString(";", "[", "]") { job ->
                "id=${job.id} net=${job.networkType} delayMs=${job.minLatencyMillis} idle=${job.isRequireDeviceIdle} " +
                    "charging=${job.isRequireCharging} svc=${job.service.shortClassName}"
            }
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val info = connectivity.activeNetworkInfo
        val bucket = if (Build.VERSION.SDK_INT >= 28) runCatching {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager).appStandbyBucket
        }.getOrNull() else null
        val main = android.os.Looper.getMainLooper().thread
        val workThreads = Thread.getAllStackTraces().keys.filter { it.name.contains("WM.") || it.name.contains("WorkManager") }
            .joinToString(";", "[", "]") { "${it.name}:${it.state}" }
        "pid=${android.os.Process.myPid()} jobs=$jobs netInfo=${info?.type}/${info?.isConnected}/${info?.state} " +
            "standbyBucket=$bucket main=${main.state}@${main.stackTrace.take(4).joinToString("<") { it.methodName }} " +
            "workThreads=$workThreads"
    }.getOrElse { "unavailable:${it.javaClass.simpleName}" }

    private fun describe(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "capabilities=none"
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "WIFI", NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET", NetworkCapabilities.TRANSPORT_VPN to "VPN",
        ).filter { (transport, _) -> capabilities.hasTransport(transport) }.joinToString("|") { it.second }
        return "transports=${transports.ifEmpty { "none" }} " +
            "internet=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    @Suppress("DEPRECATION")
    private fun describeAll(connectivity: ConnectivityManager): String = runCatching {
        connectivity.allNetworks.joinToString(";", "[", "]") { network ->
            "$network ${describe(connectivity.getNetworkCapabilities(network))}"
        }
    }.getOrDefault("[unavailable]")

    private fun pendingSlots() = manager.getWorkInfosByTag(WorkManagerExtensionReleaseRefreshScheduler.SLOT_TAG)
        .get(10, TimeUnit.SECONDS).filter { it.state == androidx.work.WorkInfo.State.ENQUEUED }

    suspend fun retainedSlotId(): String = withContext(Dispatchers.IO) { pendingSlots().single().id.toString() }

    suspend fun verifyRetainedSlot(context: Context, actual: ExtensionReleaseRefreshCoordinator, id: String) {
        coordinator = actual
        initialize(context)
        val retained = withContext(Dispatchers.IO) {
            manager.getWorkInfoById(java.util.UUID.fromString(id)).get(10, TimeUnit.SECONDS)
        }
        check(retained != null && !retained.state.isFinished &&
            WorkManagerExtensionReleaseRefreshScheduler.TAG in retained.tags) {
            "scheduled product slot work did not survive the external process kill: $retained"
        }
    }
}

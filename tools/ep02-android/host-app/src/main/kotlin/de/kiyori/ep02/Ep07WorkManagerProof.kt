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
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** TEST-only injection; real WorkManager database, scheduling and product CoroutineWorker. */
internal object Ep07WorkManagerProof {
    private lateinit var manager: WorkManager
    @Volatile private var coordinator: ExtensionReleaseRefreshCoordinator? = null
    val completions = AtomicInteger()
    @Volatile var lastOutcome: ShadowRefreshOutcome? = null

    private fun initialize(context: Context) {
        if (::manager.isInitialized) return
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != ExtensionReleaseRefreshWorker::class.java.name) return null
                return ExtensionReleaseRefreshWorker(appContext, parameters, object : ExtensionReleaseRefreshCoordinator {
                    override suspend fun refresh(workId: String, force: Boolean): ShadowRefreshOutcome {
                        val outcome = requireNotNull(coordinator).refresh(workId, force)
                        lastOutcome = outcome
                        completions.incrementAndGet()
                        return outcome
                    }
                })
            }
        }
        WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(factory).build())
        manager = WorkManager.getInstance(context)
    }

    suspend fun due(context: Context, actual: ExtensionReleaseRefreshCoordinator, twice: Boolean = false,
        leavePeriodicForRestart: Boolean = false): ShadowRefreshOutcome {
        if (Build.VERSION.SDK_INT >= 26) awaitValidatedNetwork(context)
        coordinator = actual
        initialize(context)
        withContext(Dispatchers.IO) {
            manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG).result.get(10, TimeUnit.SECONDS)
        }
        lastOutcome = null
        val before = completions.get()
        val scheduler = WorkManagerExtensionReleaseRefreshScheduler(manager)
        scheduler.scheduleDue()
        if (twice) scheduler.scheduleDue()
        withTimeout(30_000) {
            while (completions.get() == before) delay(100)
            // Wait for the due chain, so a second startup check cannot escape into a later proof.
            while (withContext(Dispatchers.IO) {
                manager.getWorkInfosForUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.NOW)
                    .get(10, TimeUnit.SECONDS).any { !it.state.isFinished }
            }) delay(100)
        }
        if (!leavePeriodicForRestart) {
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

    suspend fun retainedPeriodicId(): String = withContext(Dispatchers.IO) {
        manager.getWorkInfosForUniqueWork(WorkManagerExtensionReleaseRefreshScheduler.PERIODIC)
            .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }.id.toString()
    }

    suspend fun verifyRetainedPeriodic(context: Context, actual: ExtensionReleaseRefreshCoordinator, id: String) {
        coordinator = actual
        initialize(context)
        val retained = withContext(Dispatchers.IO) {
            manager.getWorkInfoById(java.util.UUID.fromString(id)).get(10, TimeUnit.SECONDS)
        }
        check(retained != null && !retained.state.isFinished &&
            WorkManagerExtensionReleaseRefreshScheduler.TAG in retained.tags) {
            "scheduled product periodic work did not survive the external process kill: $retained"
        }
    }
}

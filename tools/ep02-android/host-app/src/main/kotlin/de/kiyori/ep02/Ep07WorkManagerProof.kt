package de.kiyori.ep02

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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

    suspend fun due(context: Context, actual: ExtensionReleaseRefreshCoordinator, twice: Boolean = false): ShadowRefreshOutcome {
        if (Build.VERSION.SDK_INT >= 26) {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            check(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                "hermetic network must be Android-validated before product WorkManager scheduling: $capabilities"
            }
        }
        initialize(context)
        withContext(Dispatchers.IO) {
            manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG).result.get(10, TimeUnit.SECONDS)
        }
        coordinator = actual
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
        scheduler.cancel()
        withContext(Dispatchers.IO) {
            manager.cancelAllWorkByTag(WorkManagerExtensionReleaseRefreshScheduler.TAG).result.get(10, TimeUnit.SECONDS)
        }
        return requireNotNull(lastOutcome)
    }
}

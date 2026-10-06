package com.axiel7.anihyou

import android.app.Application
import android.os.Build.VERSION.SDK_INT
import androidx.core.performance.DefaultDevicePerformance
import androidx.core.performance.DevicePerformance
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.axiel7.anihyou.core.domain.dataStoreModule
import com.axiel7.anihyou.core.domain.databaseModule
import com.axiel7.anihyou.core.domain.repositoryModule
import com.axiel7.anihyou.core.network.apiModule
import com.axiel7.anihyou.core.network.networkModule
import com.axiel7.anihyou.feature.worker.workerModule
import com.axiel7.anihyou.release.core.api.AniWorldShadowScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceScheduler
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.usableExtension
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

class App : Application(), SingletonImageLoader.Factory {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The Wasm runtime service runs in an isolated process that has no access to the app's files, accounts or
        // network. Starting the whole app there (Koin, stores, schedulers) crashed that process on its first
        // uncaught exception, so the service never connected and an install waited on it indefinitely.
        if (isIsolatedProcess()) return
        if (BuildConfig.DEBUG || BuildConfig.PERFORMANCE_LOGGING) installDiagnosticLog()

        val koinApplication = startKoin {
            if (BuildConfig.DEBUG || BuildConfig.PERFORMANCE_LOGGING) {
                androidLogger()
            }
            androidContext(this@App)
            workManagerFactory()

            val coreModule = module {
                single<DevicePerformance> { DefaultDevicePerformance() }
            }


            modules(
                coreModule,
                animetrackerReleaseModule,
                dataStoreModule,
                databaseModule,
                networkModule,
                apiModule,
                repositoryModule,
                viewModelModule,
                workerModule,
            )
        }
        if (AniWorldShadowDebugActivation.enabledForInternalTest) {
            koinApplication.koin.get<AniWorldShadowScheduler>().scheduleCanaryNow()
        }
        startupScope.launch {
            // Registry IO and scheduling never block startup. An empty install schedules no work.
            runCatching {
                if (koinApplication.koin.get<ExtensionSourceRepository>().sources.value.any { it.enabled }) {
                    koinApplication.koin.get<ExtensionSourceScheduler>().scheduleRefresh()
                }
            }
        }
        startupScope.launch {
            val sources = koinApplication.koin.get<ExtensionSourceRepository>()
            sources.restoreInstalled()
            val policy = koinApplication.koin.get<ExtensionProductPolicyRepository>()
            val scheduler = koinApplication.koin.get<ExtensionReleaseRefreshScheduler>()
            combine(policy.policy, sources.sources) { selection, catalog ->
                selection.activeReleaseSource?.let { key ->
                    catalog.usableExtension(key)?.let { entry ->
                        listOf(key, selection.releaseGeneration, entry.installedDigest, entry.packageGeneration)
                    }
                }
            }.distinctUntilChanged().collect { current ->
                if (current == null) scheduler.cancel() else scheduler.scheduleDue()
            }
        }
        // A real foreground transition (not a rotation) asks the same unique work as process start, so the
        // first start of the process is one automatic run, and the hourly window is kept by the ledger.
        registerActivityLifecycleCallbacks(ForegroundTransitions {
            startupScope.launch {
                runCatching {
                    val policy = koinApplication.koin.get<ExtensionProductPolicyRepository>()
                    if (policy.policy.value.activeReleaseSource != null) {
                        koinApplication.koin.get<ExtensionReleaseRefreshScheduler>().scheduleForeground()
                    }
                }
            }
        }.callbacks())
    }

    override fun newImageLoader(context: PlatformContext) =
        ImageLoader.Builder(this)
            .components {
                if (SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.15)
                    .build()
            }
            .crossfade(true)
            .build()
}

/** Internal instrumentation/debug hook; no product setting enables shadow traffic. */
internal object AniWorldShadowDebugActivation {
    val enabledForInternalTest: Boolean =
        isEnabled(BuildConfig.DEBUG, BuildConfig.ANIWORLD_SHADOW_CANARY)

    internal fun isEnabled(debugBuild: Boolean, canaryProperty: Boolean): Boolean =
        debugBuild && canaryProperty

}

/** Isolated processes run under a uid in the 99000..99999 range of their user. */
private fun isIsolatedProcess(): Boolean = (android.os.Process.myUid() % 100000) in 99000..99999

/** Debug and performance tests print every data decision; the ordinary release has no log sink. */
private fun installDiagnosticLog() {
    com.axiel7.anihyou.release.core.log.AppLog.sink =
        com.axiel7.anihyou.release.core.log.AppLog.Sink { level, area, message, error ->
            val tag = "AniHyou.$area"
            // Logcat truncates a line near 4 KB; longer messages are split so no decision is cut off.
            message.chunked(3500).forEach { part ->
                when (level) {
                    com.axiel7.anihyou.release.core.log.AppLog.Level.DEBUG -> android.util.Log.d(tag, part)
                    com.axiel7.anihyou.release.core.log.AppLog.Level.INFO -> android.util.Log.i(tag, part)
                    com.axiel7.anihyou.release.core.log.AppLog.Level.WARN -> android.util.Log.w(tag, part, error)
                    com.axiel7.anihyou.release.core.log.AppLog.Level.ERROR -> android.util.Log.e(tag, part, error)
                }
            }
        }
    com.axiel7.anihyou.release.core.log.AppLog.i("app") {
        "diagnostic log on, profile=${BuildConfig.BUILD_TYPE} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} sdk=${android.os.Build.VERSION.SDK_INT}"
    }
}

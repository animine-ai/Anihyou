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

        val koinApplication = startKoin {
            if (BuildConfig.DEBUG) {
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

package com.axiel7.anihyou.release.data.extension

import android.content.Context
import android.os.Build
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceScheduler
import java.time.Clock
import kotlinx.coroutines.runBlocking

/** Production has no generic authenticated bootstrap yet. No build property enables test anchors. */
object ProductionExtensionSources {
    fun create(context: Context, scheduler: ExtensionSourceScheduler, clock: Clock): ExtensionSourceRepository =
        FileExtensionSourceRepository(
            directory = context.filesDir.resolve("user-extension-sources"),
            bootstrap = UnavailableExtensionSourceTrustBootstrap,
            transport = ProductionExtensionRepositoryTransport(),
            storeFactory = ExtensionSourceStoreFactory { directory, anchor ->
                val runtime = AndroidIsolatedExtensionRuntime(context)
                ExtensionInstallStore(directory, anchor.pin,
                    ExtensionPackageVerifier(CombinedWasmModuleProfileVerifier(WasmtimeNativeModuleProfileVerifier())),
                    SourceRole.entries.toSet(), anchor.allowedHosts, 1, "48.0.3", smoke = { extension ->
                        val extensionContext = ExtensionContextV1(extension.extensionId, extension.providerId,
                            extension.grantedRoles.sortedBy(SourceRole::ordinal), clock.instant().toString(), emptyList())
                        val input = ExtensionWireCodec.encodePlanInput(PlanInputV1(1, extensionContext))
                        val result = runBlocking(requireNotNull(ExtensionSourceRuntimeCancellation.job.get()) {
                            "source smoke requires a cancellable operation"
                        }) {
                            runtime.execute(extension.moduleDigest, extension.moduleBytes, "plan_requests", input,
                                ExtensionExecutionLimits(256 * 1024, 64 * 1024, 32 * 1024 * 1024, 10_000_000, 2_000))
                        }
                        val success = result as? ExtensionRuntimeResult.Success ?: error("isolated extension smoke failed")
                        ExtensionWireCodec.decodePlanOutput(success.outputUtf8, extensionContext)
                    })
            },
            scheduler = scheduler,
            clock = clock,
            runtimeSupported = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" },
        )
}

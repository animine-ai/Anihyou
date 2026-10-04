package com.axiel7.anihyou.release.data.extension

import android.content.Context
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** Public pins and origins are supplied by the reviewed app build, never by a catalog. */
data class ProductionExtensionHostConfiguration(
    val repositoryId: String,
    val initialRootSha256: String,
    val distributionOrigins: Set<String>,
    val allowedHosts: Set<String>,
    val approvedAuthority: Set<ApprovedExtensionAuthorityTuple>,
    val parseFuelByExtensionId: Map<ExtensionId, Long> = emptyMap(),
) {
    init {
        AppTrustPin(repositoryId, initialRootSha256, distributionOrigins)
        require(allowedHosts.isNotEmpty() && allowedHosts.all { host ->
            host.length <= 253 && host.split('.').all { label ->
                label.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            }
        }) { "Invalid allowed extension hosts" }
        require(parseFuelByExtensionId.values.all { it > 0L })
    }
}

/**
 * With no complete build-time configuration, V3 is inert. There is no debug root, TOFU, or
 * permissive fallback. R2 remains independent of this optional extension-only Shadow path.
 */
class ProductionExtensionHostBoundary private constructor(
    val release: ExtensionHostCoordinator?,
    val navigation: ProductionNavigationDispatcher?,
    val authority: ExtensionEvidenceAuthorityAdapter,
    private val store: ExtensionInstallStore?,
) {
    val provisioned: Boolean get() = store != null

    fun acceptRoot(envelope: ByteArray, now: Instant) {
        requireNotNull(store) { "production extension pins not provisioned" }.acceptRoot(envelope, now)
    }

    fun acceptIndex(envelope: ByteArray, now: Instant) {
        requireNotNull(store) { "production extension pins not provisioned" }.acceptIndex(envelope, now)
    }

    fun install(archive: File, extensionId: String, now: Instant) {
        requireNotNull(store) { "production extension pins not provisioned" }.install(archive, extensionId, now)
    }

    fun promoteHealthy(now: Instant) {
        requireNotNull(store) { "production extension pins not provisioned" }.promoteHealthy(now)
    }

    fun rollback(now: Instant) {
        requireNotNull(store) { "production extension pins not provisioned" }.quarantineAndRollback(now)
    }

    companion object {
        fun create(context: Context, configuration: ProductionExtensionHostConfiguration?,
            manualTrust: ManualExtensionTrustStore? = null): ProductionExtensionHostBoundary {
            val authority = ExtensionEvidenceAuthorityAdapter(configuration?.approvedAuthority.orEmpty(),
                manualTrust?.let { store -> { store.approvedAuthority() } } ?: { emptySet() })
            if (configuration == null) return ProductionExtensionHostBoundary(null, null, authority, null)
            val pin = AppTrustPin(configuration.repositoryId, configuration.initialRootSha256,
                configuration.distributionOrigins)
            require(configuration.allowedHosts.isNotEmpty() &&
                configuration.allowedHosts.all { it.matches(Regex("[a-z0-9.-]{1,253}")) })
            val runtime = AndroidIsolatedExtensionRuntime(context)
            val verifier = ExtensionPackageVerifier(
                CombinedWasmModuleProfileVerifier(WasmtimeNativeModuleProfileVerifier()))
            val store = ExtensionInstallStore(
                File(context.filesDir, "release-extensions"), pin, verifier,
                setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT, SourceRole.DIRECT),
                configuration.allowedHosts, 1, "48.0.3",
                smoke = { extension ->
                    // The package profile and real isolated runtime must both accept the module
                    // before its active pointer may be swapped. No network or test TLS hooks.
                    val contextV1 = com.axiel7.anihyou.release.core.extension.ExtensionContextV1(
                        extension.extensionId, extension.providerId,
                        extension.grantedRoles.sortedBy(SourceRole::ordinal), Instant.now().toString(),
                        emptyList())
                    val input = ExtensionWireCodec.encodePlanInput(
                        com.axiel7.anihyou.release.core.extension.PlanInputV1(1, contextV1))
                    val limits = com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits(
                        256 * 1024, 64 * 1024, 32 * 1024 * 1024, 10_000_000, 2_000)
                    val result = runBlocking {
                        runtime.execute(extension.moduleDigest, extension.moduleBytes, "plan_requests", input, limits)
                    }
                    val output = result as? com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult.Success
                        ?: error("isolated extension smoke failed")
                    ExtensionWireCodec.decodePlanOutput(output.outputUtf8, contextV1)
                })
            val dispatch = ProductionExtensionDispatches.create(store, runtime,
                File(context.filesDir, "release-extension-network"), authority.observationPolicy(),
                configuration.parseFuelByExtensionId)
            return ProductionExtensionHostBoundary(dispatch.release, dispatch.navigation, authority, store)
        }
    }
}

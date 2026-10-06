package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationResponseEnvelopeV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class NavigationDispatchResult(
    val target: ProviderNavigationTargetV1,
    val responseProvenance: List<ExtensionResponseProvenance>,
)

/** Navigation shares the release transport and never creates a release observation. */
class ProductionNavigationDispatcher(
    private val repository: VerifiedExtensionRepository,
    private val runtime: ExtensionRuntime,
    private val transport: DestinationBoundExtensionTransport,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun navigate(request: NavigationContextV1, generation: String): ProviderNavigationTargetV1? =
        navigateWithProvenance(request, generation)?.target

    suspend fun navigateWithProvenance(request: NavigationContextV1, generation: String): NavigationDispatchResult? {
        require(generation.isNotBlank() && generation.length <= 128)
        NavigationWireCodecV1.encodeContext(request)
        if (!ExtensionExecutionSlot.active.compareAndSet(false, true)) return null
        try {
        val extension = repository.loadUsable(request.providerId) ?: return null
        val capability = when (request.targetKind) {
            NavigationTargetKind.OVERVIEW -> NavigationCapability.OVERVIEW_NAVIGATION
            NavigationTargetKind.EPISODE -> NavigationCapability.EPISODE_NAVIGATION
        }
        require(capability in extension.navigationCapabilities &&
            extension.extensionId == request.extensionId && extension.providerId == request.providerId &&
            extension.grantedHosts.isNotEmpty() && extension.abiVersion == 1 &&
            extension.packageDigest.matches(Regex("[0-9a-f]{64}")) &&
            extension.moduleDigest == sha256(extension.moduleBytes))
        val pinnedModule = extension.moduleBytes
        val context = request.copy(observedAt = clock.instant().toString())
        val plan = NavigationWireCodecV1.decodePlan(
            invoke(extension.moduleDigest, pinnedModule, "plan_navigation",
                NavigationWireCodecV1.encodeContext(context)), context, extension.grantedHosts)
        val network = transport as? ProductionExtensionHttpTransport
            ?: error("navigation requires the production bound transport")
        val session = network.open(extension, generation)
        try {
            val responses = plan.requests.map { planned ->
                coroutineContext.ensureActive()
                val fetched = session.fetch(planned.requestId, "NAVIGATION", planned.url)
                NavigationResponseEnvelopeV1(fetched.requestId, fetched.status, fetched.httpStatus,
                    fetched.finalUrl, fetched.bodyUtf8, fetched.sourceHash)
            }
            coroutineContext.ensureActive()
            val input = NavigationWireCodecV1.encodeParseInput(context, responses, extension.grantedHosts)
            val targets = NavigationWireCodecV1.decodeTargets(
                invoke(extension.moduleDigest, pinnedModule, "parse_navigation", input),
                context, responses, extension.grantedHosts).targets
            coroutineContext.ensureActive()
            return targets.singleOrNull()?.let { NavigationDispatchResult(it, session.provenance.toList()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            session.close()
        }
        } finally {
            ExtensionExecutionSlot.active.set(false)
        }
    }

    private suspend fun invoke(digest: String, module: ByteArray, export: String, input: ByteArray): ByteArray {
        val limits = if (export == "plan_navigation") PLAN_LIMITS else PARSE_LIMITS
        require(input.size <= limits.maxInputBytes)
        return when (val result = runtime.execute(digest, module, export, input, limits)) {
            is ExtensionRuntimeResult.Success -> result.outputUtf8.also {
                require(it.size <= limits.maxOutputBytes)
            }
            is ExtensionRuntimeResult.Failure -> error("navigation runtime failed: ${result.code}")
        }
    }

    private companion object {
        val PLAN_LIMITS = ExtensionExecutionLimits(256 * 1024, 64 * 1024, 32 * 1024 * 1024, 10_000_000, 2_000)
        val PARSE_LIMITS = ExtensionExecutionLimits(4 * 1024 * 1024, 64 * 1024, 32 * 1024 * 1024, 10_000_000, 2_000)
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

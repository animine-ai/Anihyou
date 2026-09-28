package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionContextV1
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionLimits
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionReceipt
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionMethod
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import com.axiel7.anihyou.release.core.extension.ExtensionRuntimeResult
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import com.axiel7.anihyou.release.core.extension.ParseInputV1
import com.axiel7.anihyou.release.core.extension.ParseOutputV1
import com.axiel7.anihyou.release.core.extension.PlanInputV1
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderObservationV1
import com.axiel7.anihyou.release.core.extension.RequestSpec
import com.axiel7.anihyou.release.core.extension.ResponseEnvelope
import com.axiel7.anihyou.release.core.extension.ResponseReportV1
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class ExtensionHostFailureCode {
    BUSY,
    DISABLED,
    HOST_VALIDATION_FAILED,
    NO_TRUSTED_EXTENSION,
    OBSERVATION_POLICY_REJECTED,
    RUNTIME_FAILURE,
    TRANSPORT_NOT_READY,
}

/** Metadata and module bytes are returned only by a verifier-backed repository. */
class VerifiedExtensionPackage internal constructor(
    val extensionId: ExtensionId,
    val providerId: ProviderId,
    val displayName: String,
    val publisherId: String,
    val signingKeyId: String,
    val trustRootVersion: Long,
    val releaseSequence: Long,
    val abiVersion: Int,
    val policyVersion: Int,
    val packageDigest: String,
    val manifestDigest: String,
    val moduleDigest: String,
    moduleBytes: ByteArray,
    val grantedRoles: Set<SourceRole>,
    val navigationCapabilities: Set<NavigationCapability>,
    /** Host-policy intersection with signed manifest grants; exact DNS names only. */
    val grantedHosts: Set<String>,
    val runtimeVersion: String,
) {
    private val moduleContent = moduleBytes.copyOf()
    val moduleBytes: ByteArray get() = moduleContent.copyOf()
}

/**
 * The repository must return an already signature- and digest-verified package,
 * apply expiry/high-water/revocation rules, and select only a still-trusted LKG.
 * It must return null rather than expose an unsigned, revoked or expired new install.
 */
interface VerifiedExtensionRepository {
    suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage?
}

/**
 * Production implementations must bind validated DNS answers to the actual socket,
 * revalidate each redirect, reserve every wire attempt durably and enforce body/time
 * ceilings. An unproven implementation is refused before any network call.
 */
interface DestinationBoundExtensionTransport {
    val dnsDestinationBindingVerified: Boolean

    suspend fun execute(extension: VerifiedExtensionPackage, request: RequestSpec): ResponseEnvelope
}

/** Host-owned policy gate. It can grant Authority only for an explicitly approved signed tuple. */
fun interface ExtensionObservationPolicy {
    fun allows(extension: VerifiedExtensionPackage, observation: ProviderObservationV1): Boolean
}

data class ExtensionRunRequest(
    val providerId: ProviderId,
    val generationId: String,
    val sourceRoles: Set<SourceRole>,
    val targets: List<ExtensionTargetV1>,
)

sealed interface ExtensionHostResult {
    data class Completed(
        val receipt: ExtensionExecutionReceipt,
        val observations: List<ProviderObservationV1>,
        val reports: List<ResponseReportV1>,
        val responseProvenance: List<ExtensionResponseProvenance> = emptyList(),
    ) : ExtensionHostResult

    data class Failed(val code: ExtensionHostFailureCode) : ExtensionHostResult
}

/** Generic V3 seam. It has no references to embedded providers, parsers, Room or R2. */
class ExtensionHostCoordinator(
    private val repository: VerifiedExtensionRepository,
    private val runtime: ExtensionRuntime,
    private val transport: DestinationBoundExtensionTransport?,
    private val observationPolicy: ExtensionObservationPolicy,
    private val clock: Clock = Clock.systemUTC(),
    /** False by default; production execution remains disabled until trust/runtime gates close. */
    private val enabled: () -> Boolean = { false },
) {
    suspend fun execute(request: ExtensionRunRequest): ExtensionHostResult {
        if (!enabled()) return ExtensionHostResult.Failed(ExtensionHostFailureCode.DISABLED)
        if (request.generationId.isBlank() || request.generationId.length > 128 || request.sourceRoles.isEmpty()) {
            return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
        }
        if (!ExtensionExecutionSlot.active.compareAndSet(false, true)) {
            return ExtensionHostResult.Failed(ExtensionHostFailureCode.BUSY)
        }
        try {
            val packageInfo = repository.loadUsable(request.providerId)
                ?: return ExtensionHostResult.Failed(ExtensionHostFailureCode.NO_TRUSTED_EXTENSION)
            val moduleBytes = packageInfo.moduleBytes
            if (moduleBytes.size !in MIN_MODULE_BYTES..MAX_MODULE_BYTES) {
                return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
            }
            if (!validPackage(packageInfo, request, moduleBytes)) {
                return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
            }
            val networkTransport = transport
                ?: return ExtensionHostResult.Failed(ExtensionHostFailureCode.TRANSPORT_NOT_READY)
            if (!networkTransport.dnsDestinationBindingVerified) {
                return ExtensionHostResult.Failed(ExtensionHostFailureCode.TRANSPORT_NOT_READY)
            }

            val startedAt = clock.instant()
            val context = ExtensionContextV1(
                extensionId = packageInfo.extensionId,
                providerId = packageInfo.providerId,
                sourceRoles = request.sourceRoles.sortedBy(SourceRole::ordinal),
                observedAt = startedAt.toString(),
                targets = request.targets,
            )
            val planBytes = executeRuntime(
                packageInfo,
                moduleBytes,
                "plan_requests",
                ExtensionWireCodec.encodePlanInput(PlanInputV1(schemaVersion = 1, context = context)),
            )
            val plan = ExtensionWireCodec.decodePlanOutput(planBytes, context)

            val allObservations = ArrayList<ProviderObservationV1>()
            val allReports = ArrayList<ResponseReportV1>()
            val fetchedResponses = HashMap<PhysicalRequestKey, ResponseEnvelope>()
            val networkSession = (networkTransport as? ProductionExtensionHttpTransport)
                ?.open(packageInfo, request.generationId)
            var hostProvenance: List<ExtensionResponseProvenance> = emptyList()
            try {
            for (planned in plan.requests) {
                validateRequestUrl(planned, packageInfo.grantedHosts)
                val requestKey = PhysicalRequestKey(planned.method, normalizeUrl(planned.url))
                val physicalRequest = planned.copy(url = requestKey.normalizedUrl)
                val response = fetchedResponses[requestKey]?.copy(
                    requestId = planned.requestId,
                    sourceRole = planned.sourceRole,
                ) ?: (networkSession?.fetch(physicalRequest.requestId, physicalRequest.sourceRole.name,
                    physicalRequest.url)?.let { fetched ->
                    ResponseEnvelope(fetched.requestId, physicalRequest.sourceRole, fetched.status,
                        fetched.httpStatus, fetched.finalUrl, fetched.bodyUtf8, fetched.sourceHash)
                } ?: networkTransport.execute(packageInfo, physicalRequest)).also { fetched ->
                    validateResponse(planned, fetched, packageInfo.grantedHosts)
                    fetchedResponses[requestKey] = fetched
                }
                validateResponse(planned, response, packageInfo.grantedHosts)
                val parseInput = ParseInputV1(schemaVersion = 1, context = context, responses = listOf(response))
                val parsedBytes = executeRuntime(
                    packageInfo,
                    moduleBytes,
                    "parse_responses",
                    ExtensionWireCodec.encodeParseInput(parseInput),
                )
                val parsed: ParseOutputV1 = ExtensionWireCodec.decodeParseOutput(parsedBytes, parseInput)
                if (planned.sourceRole == SourceRole.DIRECT) {
                    val target = context.targets.singleOrNull { it.targetToken == planned.targetToken }
                        ?: return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
                    if (parsed.observations.any { it.providerSeriesKey != target.providerSeriesKey }) {
                        return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
                    }
                }
                allObservations += parsed.observations
                allReports += parsed.responseReports
                if (allObservations.size > 4096) {
                    return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
                }
            }
            } finally {
                hostProvenance = networkSession?.provenance?.toList() ?: emptyList()
                networkSession?.close()
            }

            if (allObservations.any { !observationPolicy.allows(packageInfo, it) }) {
                return ExtensionHostResult.Failed(ExtensionHostFailureCode.OBSERVATION_POLICY_REJECTED)
            }
            val completedAt = clock.instant()
            val receipt = ExtensionExecutionReceipt(
                receiptId = UUID.randomUUID().toString(),
                generationId = request.generationId,
                extensionId = packageInfo.extensionId,
                providerId = packageInfo.providerId,
                publisherId = packageInfo.publisherId,
                signingKeyId = packageInfo.signingKeyId,
                trustRootVersion = packageInfo.trustRootVersion,
                packageDigest = packageInfo.packageDigest,
                manifestDigest = packageInfo.manifestDigest,
                moduleDigest = packageInfo.moduleDigest,
                releaseSequence = packageInfo.releaseSequence,
                abiVersion = packageInfo.abiVersion,
                policyVersion = packageInfo.policyVersion,
                runtimeVersion = packageInfo.runtimeVersion,
                startedAt = startedAt.toString(),
                completedAt = completedAt.toString(),
            )
            return ExtensionHostResult.Completed(receipt, allObservations, allReports, hostProvenance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ExtensionWireException) {
            return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
        } catch (_: ExtensionGuestErrorException) {
            return ExtensionHostResult.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE)
        } catch (_: IllegalArgumentException) {
            return ExtensionHostResult.Failed(ExtensionHostFailureCode.HOST_VALIDATION_FAILED)
        } catch (_: Exception) {
            return ExtensionHostResult.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE)
        } finally {
            ExtensionExecutionSlot.active.set(false)
        }
    }

    private suspend fun executeRuntime(
        packageInfo: VerifiedExtensionPackage,
        moduleBytes: ByteArray,
        exportName: String,
        input: ByteArray,
    ): ByteArray {
        val limits = when (exportName) {
            "plan_requests" -> PLAN_LIMITS
            "parse_responses" -> PARSE_LIMITS
            else -> throw IllegalArgumentException("export is not allowlisted")
        }
        if (input.size > limits.maxInputBytes) {
            throw ExtensionWireException(ExtensionWireErrorCode.SIZE_LIMIT, "extension input exceeds its execution limit")
        }
        return when (val result = runtime.execute(
            moduleDigest = packageInfo.moduleDigest,
            moduleBytes = moduleBytes,
            exportName = exportName,
            inputUtf8 = input,
            limits = limits,
        )) {
            is ExtensionRuntimeResult.Success -> result.outputUtf8.also {
                if (it.size > limits.maxOutputBytes) {
                    throw ExtensionWireException(ExtensionWireErrorCode.SIZE_LIMIT, "extension output exceeds its execution limit")
                }
            }
            is ExtensionRuntimeResult.Failure -> throw RuntimeException(result.code.name)
        }
    }

    private fun validPackage(
        packageInfo: VerifiedExtensionPackage,
        request: ExtensionRunRequest,
        moduleBytes: ByteArray,
    ): Boolean =
        packageInfo.providerId == request.providerId &&
            packageInfo.releaseSequence > 0 &&
            packageInfo.trustRootVersion > 0 &&
            packageInfo.abiVersion == ABI_VERSION &&
            packageInfo.policyVersion > 0 &&
            packageInfo.publisherId.isNotBlank() &&
            packageInfo.signingKeyId.isNotBlank() &&
            packageInfo.runtimeVersion.isNotBlank() &&
            packageInfo.grantedRoles.containsAll(request.sourceRoles) &&
            moduleBytes.size <= MAX_MODULE_BYTES &&
            isSha256(packageInfo.packageDigest) &&
            isSha256(packageInfo.manifestDigest) &&
            isSha256(packageInfo.moduleDigest) &&
            sha256(moduleBytes) == packageInfo.moduleDigest &&
            request.targets.size <= MAX_TARGETS &&
            request.targets.map { it.targetToken }.distinct().size == request.targets.size

    private fun validateRequestUrl(request: RequestSpec, grantedHosts: Set<String>) {
        require(request.method == ExtensionMethod.GET && request.requestId.isNotBlank())
        require(request.url.toByteArray(Charsets.UTF_8).size <= MAX_URL_BYTES)
        val uri = URI(request.url)
        val host = uri.host ?: throw IllegalArgumentException("request host missing")
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port == 443)
        require(host == host.lowercase(Locale.ROOT) && host.matches(DNS_NAME) && !looksLikeIpLiteral(host))
        require(host in grantedHosts)
    }

    private fun validateResponse(request: RequestSpec, response: ResponseEnvelope, grantedHosts: Set<String>) {
        require(response.requestId == request.requestId && response.sourceRole == request.sourceRole)
        if (response.status == ExtensionResponseStatus.OK) {
            require(response.httpStatus != null && response.httpStatus in 200..299)
            val finalUrl = response.finalUrl ?: throw IllegalArgumentException("successful final URL missing")
            validateRequestUrl(request.copy(url = finalUrl), grantedHosts)
            val body = response.bodyUtf8 ?: throw IllegalArgumentException("successful response body missing")
            val bytes = body.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BODY_BYTES && response.sourceHash == sha256(bytes))
        } else {
            require(response.bodyUtf8 == null && response.sourceHash == null)
            response.finalUrl?.let { validateRequestUrl(request.copy(url = it), grantedHosts) }
        }
    }

    /** Canonicalizes only URL syntax covered by host policy; path/query meaning stays exact. */
    private fun normalizeUrl(value: String): String {
        val uri = URI(value).normalize()
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return buildString {
            append("https://")
            append(uri.host.lowercase(Locale.ROOT))
            append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun isSha256(value: String): Boolean = SHA256.matches(value)

    private fun looksLikeIpLiteral(host: String): Boolean =
        ':' in host || host.all { it.isDigit() || it == '.' }

    private companion object {
        const val ABI_VERSION = 1
        const val MAX_MODULE_BYTES = 8 * 1024 * 1024
        const val MIN_MODULE_BYTES = 8
        const val MAX_URL_BYTES = 2048
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_TARGETS = 256
        val PLAN_LIMITS = ExtensionExecutionLimits(
            maxInputBytes = 256 * 1024,
            maxOutputBytes = 64 * 1024,
            memoryBytes = 32 * 1024 * 1024,
            fuel = 10_000_000,
            deadlineMillis = 2_000,
        )
        val PARSE_LIMITS = ExtensionExecutionLimits(
            maxInputBytes = 4 * 1024 * 1024,
            maxOutputBytes = 1024 * 1024,
            memoryBytes = 32 * 1024 * 1024,
            fuel = 10_000_000,
            deadlineMillis = 2_000,
        )
        val SHA256 = Regex("[0-9a-f]{64}")
        val DNS_NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
    }
}

/** Singleton slot prevents multiple coordinator instances from running guests concurrently. */
internal object ExtensionExecutionSlot {
    val active = AtomicBoolean(false)
}

private data class PhysicalRequestKey(val method: ExtensionMethod, val normalizedUrl: String)

package com.axiel7.anihyou.release.core.extension

/** Optional exports in the same signed package and isolated runtime as release ingestion. */
enum class NavigationCapability { OVERVIEW_NAVIGATION, EPISODE_NAVIGATION }

/** Stable, provider-neutral identifiers used only by the extension host boundary. */
@JvmInline
value class ExtensionId private constructor(val value: String) {
    companion object {
        fun parse(value: String): ExtensionId = ExtensionId(value.requireExtensionIdentifier("extensionId"))
    }
}

@JvmInline
value class ProviderId private constructor(val value: String) {
    companion object {
        fun parse(value: String): ProviderId = ProviderId(value.requireExtensionIdentifier("providerId"))
    }
}

private fun String.requireExtensionIdentifier(field: String): String {
    require(length in 1..128) { "$field length is outside 1..128" }
    require(all { it.code in 0x21..0x7e }) { "$field must be printable ASCII" }
    require(matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "$field contains unsupported characters" }
    return this
}

enum class SourceRole { CALENDAR, RECENT, POSTPONEMENT, DIRECT }
enum class ExtensionMethod { GET }
enum class ExtensionResponseStatus { OK, TRANSPORT_FAILURE, BUDGET_DENIED, CANCELLED }
enum class ExtensionReportOutcome { SUCCESS, PARTIAL, FAILURE }
enum class ObservationInstallmentKind { EPISODE, FILM, SPECIAL, UNKNOWN }
enum class ObservationTrack { DE_SUB, DE_DUB, UNKNOWN }
enum class ObservationClaimKind { FORECAST, RELEASE_LISTING, CORRECTION, DIRECT_AVAILABILITY }
enum class ObservationScheduleMarker { NONE, POSTPONED, CANCELLED, RESCHEDULED, UNKNOWN }

data class InstallmentV1(
    val kind: ObservationInstallmentKind,
    val number: String?,
)

data class ExtensionTargetV1(
    val targetToken: String,
    val providerSeriesKey: String,
    val providerUrl: String?,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installment: InstallmentV1,
    val track: ObservationTrack,
)

data class ExtensionContextV1(
    val extensionId: ExtensionId,
    val providerId: ProviderId,
    val sourceRoles: List<SourceRole>,
    val observedAt: String,
    val targets: List<ExtensionTargetV1>,
)

data class PlanInputV1(val schemaVersion: Int, val context: ExtensionContextV1)

data class RequestSpec(
    val requestId: String,
    val sourceRole: SourceRole,
    val url: String,
    val method: ExtensionMethod,
    val targetToken: String?,
)

data class PlanOutputV1(val schemaVersion: Int, val requests: List<RequestSpec>)

data class ResponseEnvelope(
    val requestId: String,
    val sourceRole: SourceRole,
    val status: ExtensionResponseStatus,
    val httpStatus: Int?,
    val finalUrl: String?,
    val bodyUtf8: String?,
    val sourceHash: String?,
)

data class ParseInputV1(
    val schemaVersion: Int,
    val context: ExtensionContextV1,
    val responses: List<ResponseEnvelope>,
)

data class ObservationDiagnosticV1(val code: String, val message: String)

data class ProviderObservationV1(
    val schemaVersion: Int,
    val extensionId: ExtensionId,
    val providerId: ProviderId,
    val requestId: String,
    val sourceRole: SourceRole,
    val providerSeriesKey: String?,
    val rawTitle: String,
    val sourceSeason: Int?,
    val navigationSeason: Int?,
    val installment: InstallmentV1,
    val track: ObservationTrack,
    val claimKind: ObservationClaimKind,
    val sourceDateText: String?,
    val sourceTimeText: String?,
    val sourceRawText: String?,
    val parsedTimestamp: String?,
    val approximate: Boolean,
    val scheduleMarker: ObservationScheduleMarker,
    val correctionMarker: String?,
    val sourceUrl: String,
    val sourceHash: String,
    val diagnostics: List<ObservationDiagnosticV1>,
)

data class ResponseReportV1(
    val requestId: String,
    val outcome: ExtensionReportOutcome,
    val diagnostics: List<ObservationDiagnosticV1>,
)

data class ParseOutputV1(
    val schemaVersion: Int,
    val observations: List<ProviderObservationV1>,
    val responseReports: List<ResponseReportV1>,
)

enum class ExtensionGuestErrorCode { INVALID_INPUT, UNSUPPORTED_SCHEMA, PARSE_FAILED, UNSUPPORTED_ROLE }
data class ExtensionErrorOutputV1(val schemaVersion: Int, val code: ExtensionGuestErrorCode)

enum class ExtensionRuntimeErrorCode {
    ABI_MISMATCH,
    CANCELLED,
    DEADLINE,
    IMPORT_LIMIT,
    INVALID_INPUT,
    MEMORY_LIMIT,
    OUTPUT_LIMIT,
    TRAP,
    UNSUPPORTED_SCHEMA,
}

sealed interface ExtensionRuntimeResult {
    data class Success(val outputUtf8: ByteArray) : ExtensionRuntimeResult
    data class Failure(val code: ExtensionRuntimeErrorCode) : ExtensionRuntimeResult
}

data class ExtensionExecutionLimits(
    val maxInputBytes: Int,
    val maxOutputBytes: Int,
    val memoryBytes: Int,
    val fuel: Long,
    val deadlineMillis: Long,
) {
    init {
        require(maxInputBytes > 0 && maxOutputBytes > 0 && memoryBytes > 0 && fuel > 0 && deadlineMillis > 0)
    }
}

/**
 * Runtime boundary. Implementations must verify the wasm32 core-module profile,
 * exact imports/exports and resource ceilings, create a fresh Store/instance for
 * every call, execute off the UI thread, and fence late results after cancel/death.
 */
interface ExtensionRuntime {
    suspend fun execute(
        moduleDigest: String,
        moduleBytes: ByteArray,
        exportName: String,
        inputUtf8: ByteArray,
        limits: ExtensionExecutionLimits,
    ): ExtensionRuntimeResult
}

/** Host-owned receipt data. It deliberately contains no guest-selected identity or authority. */
data class ExtensionExecutionReceipt(
    val receiptId: String,
    val generationId: String,
    val extensionId: ExtensionId,
    val providerId: ProviderId,
    val publisherId: String,
    val signingKeyId: String,
    val trustRootVersion: Long,
    val packageDigest: String,
    val manifestDigest: String,
    val moduleDigest: String,
    val releaseSequence: Long,
    val abiVersion: Int,
    val policyVersion: Int,
    val runtimeVersion: String,
    val startedAt: String,
    val completedAt: String,
)

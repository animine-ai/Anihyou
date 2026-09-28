package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionContextV1
import com.axiel7.anihyou.release.core.extension.ExtensionErrorOutputV1
import com.axiel7.anihyou.release.core.extension.ExtensionGuestErrorCode
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionMethod
import com.axiel7.anihyou.release.core.extension.ExtensionReportOutcome
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import com.axiel7.anihyou.release.core.extension.InstallmentV1
import com.axiel7.anihyou.release.core.extension.ObservationClaimKind
import com.axiel7.anihyou.release.core.extension.ObservationDiagnosticV1
import com.axiel7.anihyou.release.core.extension.ParseInputV1
import com.axiel7.anihyou.release.core.extension.ParseOutputV1
import com.axiel7.anihyou.release.core.extension.PlanInputV1
import com.axiel7.anihyou.release.core.extension.PlanOutputV1
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderObservationV1
import com.axiel7.anihyou.release.core.extension.RequestSpec
import com.axiel7.anihyou.release.core.extension.ResponseEnvelope
import com.axiel7.anihyou.release.core.extension.ResponseReportV1
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ExtensionWireErrorCode {
    INVALID_UTF8,
    INVALID_JSON,
    DUPLICATE_KEY,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    INVALID_FIELD,
    UNSUPPORTED_SCHEMA,
    SIZE_LIMIT,
}

class ExtensionWireException(
    val code: ExtensionWireErrorCode,
    message: String,
) : IllegalArgumentException(message)

class ExtensionGuestErrorException(val guestCode: ExtensionGuestErrorCode) :
    IllegalArgumentException("extension returned $guestCode")

/** Strict v1 decoder. It checks duplicate keys before a tree parser can collapse them. */
object ExtensionWireCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_DEPTH = 16
    private const val MAX_PLAN_BYTES = 256 * 1024
    private const val MAX_PLAN_OUTPUT_BYTES = 64 * 1024
    private const val MAX_PARSE_INPUT_BYTES = 4 * 1024 * 1024
    private const val MAX_PARSE_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_TARGETS = 256
    private const val MAX_REQUESTS = 7
    private const val MAX_LIST_REQUESTS = 3
    private const val MAX_DIRECT_REQUESTS = 4
    private const val MAX_RESPONSES = 7
    private const val MAX_OBSERVATIONS = 512
    private const val MAX_DIAGNOSTICS = 16
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
        ignoreUnknownKeys = false
    }

    fun encodePlanInput(input: PlanInputV1): ByteArray {
        requireSchema(input.schemaVersion, "PlanInputV1")
        validateContextForEncoding(input.context)
        return encode(
            JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(input.schemaVersion),
            "context" to encodeContext(input.context),
            )),
            MAX_PLAN_BYTES,
        )
    }

    fun encodeParseInput(input: ParseInputV1): ByteArray {
        requireSchema(input.schemaVersion, "ParseInputV1")
        validateContextForEncoding(input.context)
        ensure(input.responses.size <= MAX_RESPONSES) {
            wireError(ExtensionWireErrorCode.SIZE_LIMIT, "too many parse responses")
        }
        ensure(input.responses.map { it.requestId }.distinct().size == input.responses.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "parse response requestId values must be unique")
        }
        ensure(input.responses.all { it.sourceRole in input.context.sourceRoles }) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "parse response role was not requested by the host")
        }
        input.responses.forEach(::validateResponseForEncoding)
        return encode(
            JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(input.schemaVersion),
            "context" to encodeContext(input.context),
            "responses" to JsonArray(input.responses.map(::encodeResponse)),
            )),
            MAX_PARSE_INPUT_BYTES,
        )
    }

    fun decodePlanInput(bytes: ByteArray): PlanInputV1 =
        decode(bytes, MAX_PLAN_BYTES).obj("PlanInputV1").exactFields(setOf("schemaVersion", "context"), "PlanInputV1")
            .let { PlanInputV1(it.getValue("schemaVersion").int("schemaVersion"), decodeContext(it.getValue("context"))) }
            .also { requireSchema(it.schemaVersion, "PlanInputV1") }

    fun decodePlanOutput(bytes: ByteArray, context: ExtensionContextV1): PlanOutputV1 {
        val element = decode(bytes, MAX_PLAN_OUTPUT_BYTES)
        decodeGuestError(element)?.let { throw ExtensionGuestErrorException(it.code) }
        val fields = element.obj("PlanOutputV1")
            .exactFields(setOf("schemaVersion", "requests"), "PlanOutputV1")
        val version = fields.getValue("schemaVersion").int("schemaVersion")
        requireSchema(version, "PlanOutputV1")
        val requests = fields.getValue("requests").array("requests", MAX_REQUESTS)
            .mapIndexed { index, element -> decodeRequest(element, "requests[$index]") }
        ensure(requests.map { it.requestId }.toSet().size == requests.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "requestId values must be unique")
        }
        ensure(requests.count { it.sourceRole == SourceRole.DIRECT } <= MAX_DIRECT_REQUESTS) {
            wireError(ExtensionWireErrorCode.SIZE_LIMIT, "plan exceeds the DIRECT request ceiling")
        }
        ensure(requests.count { it.sourceRole != SourceRole.DIRECT } <= MAX_LIST_REQUESTS) {
            wireError(ExtensionWireErrorCode.SIZE_LIMIT, "plan exceeds the list request ceiling")
        }
        val targetTokens = context.targets.map { it.targetToken }.toSet()
        requests.forEach { request ->
            ensure(request.sourceRole in context.sourceRoles) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "request role was not requested by the host")
            }
            if (request.sourceRole == SourceRole.DIRECT) {
                ensure(request.targetToken != null && request.targetToken in targetTokens) {
                    wireError(ExtensionWireErrorCode.INVALID_FIELD, "DIRECT request needs an eligible target token")
                }
            } else {
                ensure(request.targetToken == null) {
                    wireError(ExtensionWireErrorCode.INVALID_FIELD, "list request cannot name a target token")
                }
            }
        }
        return PlanOutputV1(version, requests)
    }

    fun decodeParseInput(bytes: ByteArray): ParseInputV1 {
        val fields = decode(bytes, MAX_PARSE_INPUT_BYTES).obj("ParseInputV1")
            .exactFields(setOf("schemaVersion", "context", "responses"), "ParseInputV1")
        val version = fields.getValue("schemaVersion").int("schemaVersion")
        requireSchema(version, "ParseInputV1")
        val responses = fields.getValue("responses").array("responses", MAX_RESPONSES)
            .mapIndexed { index, element -> decodeResponse(element, "responses[$index]") }
        ensure(responses.map { it.requestId }.toSet().size == responses.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "response requestId values must be unique")
        }
        val context = decodeContext(fields.getValue("context"))
        ensure(responses.all { it.sourceRole in context.sourceRoles }) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "parse response role was not requested by the host")
        }
        return ParseInputV1(version, context, responses)
    }

    fun decodeParseOutput(bytes: ByteArray, input: ParseInputV1): ParseOutputV1 {
        val element = decode(bytes, MAX_PARSE_OUTPUT_BYTES)
        decodeGuestError(element)?.let { throw ExtensionGuestErrorException(it.code) }
        val fields = element.obj("ParseOutputV1")
            .exactFields(setOf("schemaVersion", "observations", "responseReports"), "ParseOutputV1")
        val version = fields.getValue("schemaVersion").int("schemaVersion")
        requireSchema(version, "ParseOutputV1")
        val observations = fields.getValue("observations").array("observations", MAX_OBSERVATIONS)
            .mapIndexed { index, element -> decodeObservation(element, "observations[$index]") }
        val reports = fields.getValue("responseReports").array("responseReports", MAX_RESPONSES)
            .mapIndexed { index, element -> decodeReport(element, "responseReports[$index]") }
        ensure(reports.map { it.requestId }.toSet().size == reports.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "response report requestId values must be unique")
        }
        validateParseOutput(input, observations, reports)
        return ParseOutputV1(version, observations, reports)
    }

    fun decodeErrorOutput(bytes: ByteArray): ExtensionErrorOutputV1 =
        decode(bytes, MAX_PARSE_OUTPUT_BYTES).let { element ->
            decodeGuestError(element)
                ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "ErrorOutputV1 shape was expected")
        }

    private fun decodeGuestError(element: JsonElement): ExtensionErrorOutputV1? {
        val objectValue = element as? JsonObject ?: return null
        if ("error" !in objectValue.keys) return null
        val fields = objectValue.exactFields(setOf("schemaVersion", "error"), "ErrorOutputV1")
        val version = fields.getValue("schemaVersion").int("ErrorOutputV1.schemaVersion")
        requireSchema(version, "ErrorOutputV1")
        val error = fields.getValue("error").obj("ErrorOutputV1.error")
            .exactFields(setOf("code"), "ErrorOutputV1.error")
        return ExtensionErrorOutputV1(
            schemaVersion = version,
            code = error.getValue("code").enum("ErrorOutputV1.error.code"),
        )
    }

    private fun validateParseOutput(
        input: ParseInputV1,
        observations: List<ProviderObservationV1>,
        reports: List<ResponseReportV1>,
    ) {
        val responses = input.responses.associateBy { it.requestId }
        val reportsByRequest = reports.associateBy { it.requestId }
        ensure(reports.map { it.requestId }.toSet() == responses.keys) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "every input response needs exactly one report")
        }
        reports.forEach { report ->
            val response = responses.getValue(report.requestId)
            ensure(response.status == ExtensionResponseStatus.OK || report.outcome != ExtensionReportOutcome.SUCCESS) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "guest cannot upgrade a host transport failure to success")
            }
        }
        observations.forEach { observation ->
            val response = responses[observation.requestId]
                ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "observation references an unknown requestId")
            ensure(reportsByRequest.getValue(observation.requestId).outcome != ExtensionReportOutcome.FAILURE) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "failed response report cannot carry observations")
            }
            ensure(response.status == ExtensionResponseStatus.OK && response.finalUrl != null && response.sourceHash != null) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "observation must reference a successful host response")
            }
            ensure(observation.extensionId == input.context.extensionId && observation.providerId == input.context.providerId) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "observation identity does not match the verified invocation")
            }
            ensure(observation.sourceRole == response.sourceRole) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "observation cannot relabel its planned source role")
            }
            ensure(observation.sourceUrl == response.finalUrl && observation.sourceHash == response.sourceHash) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "observation provenance differs from the host response")
            }
            val expectedClaim = when (response.sourceRole) {
                SourceRole.CALENDAR -> ObservationClaimKind.FORECAST
                SourceRole.RECENT -> ObservationClaimKind.RELEASE_LISTING
                SourceRole.POSTPONEMENT -> ObservationClaimKind.CORRECTION
                SourceRole.DIRECT -> ObservationClaimKind.DIRECT_AVAILABILITY
            }
            ensure(observation.claimKind == expectedClaim) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "claim kind is not allowed for this source role")
            }
        }
    }

    private fun decodeContext(element: JsonElement): ExtensionContextV1 {
        val fields = element.obj("context").exactFields(
            setOf("extensionId", "providerId", "sourceRoles", "observedAt", "targets"), "context",
        )
        val roles = fields.getValue("sourceRoles").array("sourceRoles", SourceRole.entries.size)
            .mapIndexed { index, value -> value.enum<SourceRole>("sourceRoles[$index]") }
        ensure(roles.distinct().size == roles.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "sourceRoles cannot contain duplicates")
        }
        ensure(roles.isNotEmpty()) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "context must include at least one requested source role")
        }
        val targets = fields.getValue("targets").array("targets", MAX_TARGETS)
            .mapIndexed { index, value -> decodeTarget(value, "targets[$index]") }
        ensure(targets.map { it.targetToken }.toSet().size == targets.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "targetToken values must be unique")
        }
        return ExtensionContextV1(
            extensionId = parseExtensionId(fields.getValue("extensionId").string("extensionId", 128)),
            providerId = parseProviderId(fields.getValue("providerId").string("providerId", 128)),
            sourceRoles = roles,
            observedAt = validateObservedAt(fields.getValue("observedAt").timestamp("observedAt")),
            targets = targets,
        )
    }

    private fun decodeTarget(element: JsonElement, path: String): ExtensionTargetV1 {
        val fields = element.obj(path).exactFields(
            setOf("targetToken", "providerSeriesKey", "providerUrl", "sourceSeason", "navigationSeason", "installment", "track"),
            path,
        )
        val targetToken = fields.getValue("targetToken").string("$path.targetToken", 128)
        val providerSeriesKey = fields.getValue("providerSeriesKey").string("$path.providerSeriesKey", 512)
        ensure(targetToken.isNotBlank() && providerSeriesKey.isNotBlank()) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path target identity fields cannot be blank")
        }
        return ExtensionTargetV1(
            targetToken = targetToken,
            providerSeriesKey = providerSeriesKey,
            providerUrl = fields.getValue("providerUrl").nullableString("$path.providerUrl", 2048),
            sourceSeason = fields.getValue("sourceSeason").nullableInt("$path.sourceSeason", 0..9999),
            navigationSeason = fields.getValue("navigationSeason").nullableInt("$path.navigationSeason", 0..9999),
            installment = decodeInstallment(fields.getValue("installment"), "$path.installment"),
            track = fields.getValue("track").enum("$path.track"),
        )
    }

    private fun decodeRequest(element: JsonElement, path: String): RequestSpec {
        val fields = element.obj(path).exactFields(setOf("requestId", "sourceRole", "url", "method", "targetToken"), path)
        return RequestSpec(
            requestId = validateRequestId(fields.getValue("requestId").string("$path.requestId", 64), "$path.requestId"),
            sourceRole = fields.getValue("sourceRole").enum("$path.sourceRole"),
            url = fields.getValue("url").string("$path.url", 2048).also {
                ensure(it.isNotBlank()) { wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path.url is blank") }
            },
            method = fields.getValue("method").enum("$path.method"),
            targetToken = fields.getValue("targetToken").nullableString("$path.targetToken", 128),
        )
    }

    private fun decodeResponse(element: JsonElement, path: String): ResponseEnvelope {
        val fields = element.obj(path).exactFields(
            setOf("requestId", "sourceRole", "status", "httpStatus", "finalUrl", "bodyUtf8", "sourceHash"), path,
        )
        val status = fields.getValue("status").enum<ExtensionResponseStatus>("$path.status")
        val httpStatus = fields.getValue("httpStatus").nullableInt("$path.httpStatus", 100..599)
        val finalUrl = fields.getValue("finalUrl").nullableString("$path.finalUrl", 2048)
        val body = fields.getValue("bodyUtf8").nullableString("$path.bodyUtf8", MAX_BODY_BYTES)
        val sourceHash = fields.getValue("sourceHash").nullableString("$path.sourceHash", 64)
        val requestId = validateRequestId(fields.getValue("requestId").string("$path.requestId", 64), "$path.requestId")
        ensure(finalUrl == null || isHttpsUrl(finalUrl)) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path.finalUrl must be HTTPS when present")
        }
        if (status == ExtensionResponseStatus.OK) {
            ensure(httpStatus != null && httpStatus in 200..299 && finalUrl != null && body != null && isSha256(sourceHash)) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path successful response is incomplete")
            }
            ensure(isHttpsUrl(finalUrl!!)) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path finalUrl must be a bounded HTTPS URL")
            }
            ensure(sourceHash == sha256(body!!.toByteArray(Charsets.UTF_8))) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path sourceHash differs from exact response bytes")
            }
        } else {
            ensure(body == null && sourceHash == null) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path failed response cannot contain body or hash")
            }
        }
        return ResponseEnvelope(
            requestId = requestId,
            sourceRole = fields.getValue("sourceRole").enum("$path.sourceRole"),
            status = status,
            httpStatus = httpStatus,
            finalUrl = finalUrl,
            bodyUtf8 = body,
            sourceHash = sourceHash,
        )
    }

    private fun decodeObservation(element: JsonElement, path: String): ProviderObservationV1 {
        val fields = element.obj(path).exactFields(
            setOf(
                "schemaVersion", "extensionId", "providerId", "requestId", "sourceRole", "providerSeriesKey",
                "rawTitle", "sourceSeason", "navigationSeason", "installment", "track", "claimKind",
                "sourceDateText", "sourceTimeText", "sourceRawText", "parsedTimestamp", "approximate",
                "scheduleMarker", "correctionMarker", "sourceUrl", "sourceHash", "diagnostics",
            ), path,
        )
        val version = fields.getValue("schemaVersion").int("$path.schemaVersion")
        requireSchema(version, path)
        val diagnostics = fields.getValue("diagnostics").array("$path.diagnostics", MAX_DIAGNOSTICS)
            .mapIndexed { index, value -> decodeDiagnostic(value, "$path.diagnostics[$index]") }
        val observation = ProviderObservationV1(
            schemaVersion = version,
            extensionId = parseExtensionId(fields.getValue("extensionId").string("$path.extensionId", 128)),
            providerId = parseProviderId(fields.getValue("providerId").string("$path.providerId", 128)),
            requestId = validateRequestId(fields.getValue("requestId").string("$path.requestId", 64), "$path.requestId"),
            sourceRole = fields.getValue("sourceRole").enum("$path.sourceRole"),
            providerSeriesKey = fields.getValue("providerSeriesKey").nullableString("$path.providerSeriesKey", 512),
            rawTitle = fields.getValue("rawTitle").string("$path.rawTitle", 1024),
            sourceSeason = fields.getValue("sourceSeason").nullableInt("$path.sourceSeason", 0..9999),
            navigationSeason = fields.getValue("navigationSeason").nullableInt("$path.navigationSeason", 0..9999),
            installment = decodeInstallment(fields.getValue("installment"), "$path.installment"),
            track = fields.getValue("track").enum("$path.track"),
            claimKind = fields.getValue("claimKind").enum("$path.claimKind"),
            sourceDateText = fields.getValue("sourceDateText").nullableString("$path.sourceDateText", 256),
            sourceTimeText = fields.getValue("sourceTimeText").nullableString("$path.sourceTimeText", 256),
            sourceRawText = fields.getValue("sourceRawText").nullableString("$path.sourceRawText", 2048),
            parsedTimestamp = fields.getValue("parsedTimestamp").nullableTimestamp("$path.parsedTimestamp"),
            approximate = fields.getValue("approximate").boolean("$path.approximate"),
            scheduleMarker = fields.getValue("scheduleMarker").enum("$path.scheduleMarker"),
            correctionMarker = fields.getValue("correctionMarker").nullableString("$path.correctionMarker", 512),
            sourceUrl = fields.getValue("sourceUrl").string("$path.sourceUrl", 2048),
            sourceHash = fields.getValue("sourceHash").string("$path.sourceHash", 64).also {
                ensure(isSha256(it)) { wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path.sourceHash must be lowercase SHA-256") }
            },
            diagnostics = diagnostics,
        )
        ensure(observation.installment.number == null || observation.installment.number.matches(DECIMAL_EPISODE)) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path.installment.number is not a plain nonnegative decimal")
        }
        return observation
    }

    private fun decodeInstallment(element: JsonElement, path: String): InstallmentV1 {
        val fields = element.obj(path).exactFields(setOf("kind", "number"), path)
        val number = fields.getValue("number").nullableString("$path.number", 32)
        ensure(number == null || DECIMAL_EPISODE.matches(number)) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path.number is not a plain nonnegative decimal")
        }
        return InstallmentV1(
            kind = fields.getValue("kind").enum("$path.kind"),
            number = number,
        )
    }

    private fun decodeDiagnostic(element: JsonElement, path: String): ObservationDiagnosticV1 {
        val fields = element.obj(path).exactFields(setOf("code", "message"), path)
        val code = fields.getValue("code").string("$path.code", 64)
        ensure(code.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path.code has invalid syntax")
        }
        return ObservationDiagnosticV1(code, fields.getValue("message").string("$path.message", 256))
    }

    private fun decodeReport(element: JsonElement, path: String): ResponseReportV1 {
        val fields = element.obj(path).exactFields(setOf("requestId", "outcome", "diagnostics"), path)
        val diagnostics = fields.getValue("diagnostics").array("$path.diagnostics", MAX_DIAGNOSTICS)
            .mapIndexed { index, value -> decodeDiagnostic(value, "$path.diagnostics[$index]") }
        return ResponseReportV1(
            requestId = validateRequestId(fields.getValue("requestId").string("$path.requestId", 64), "$path.requestId"),
            outcome = fields.getValue("outcome").enum<ExtensionReportOutcome>("$path.outcome"),
            diagnostics = diagnostics,
        )
    }

    private fun encodeContext(context: ExtensionContextV1): JsonObject = JsonObject(linkedMapOf(
        "extensionId" to JsonPrimitive(context.extensionId.value),
        "providerId" to JsonPrimitive(context.providerId.value),
        "sourceRoles" to JsonArray(context.sourceRoles.map { JsonPrimitive(it.name) }),
        "observedAt" to JsonPrimitive(context.observedAt),
        "targets" to JsonArray(context.targets.map(::encodeTarget)),
    ))

    private fun encodeTarget(target: ExtensionTargetV1): JsonObject = JsonObject(linkedMapOf(
        "targetToken" to JsonPrimitive(target.targetToken),
        "providerSeriesKey" to JsonPrimitive(target.providerSeriesKey),
        "providerUrl" to nullableJson(target.providerUrl),
        "sourceSeason" to nullableJson(target.sourceSeason),
        "navigationSeason" to nullableJson(target.navigationSeason),
        "installment" to encodeInstallment(target.installment),
        "track" to JsonPrimitive(target.track.name),
    ))

    private fun encodeInstallment(value: InstallmentV1): JsonObject = JsonObject(linkedMapOf(
        "kind" to JsonPrimitive(value.kind.name),
        "number" to nullableJson(value.number),
    ))

    private fun encodeResponse(response: ResponseEnvelope): JsonObject = JsonObject(linkedMapOf(
        "requestId" to JsonPrimitive(response.requestId),
        "sourceRole" to JsonPrimitive(response.sourceRole.name),
        "status" to JsonPrimitive(response.status.name),
        "httpStatus" to nullableJson(response.httpStatus),
        "finalUrl" to nullableJson(response.finalUrl),
        "bodyUtf8" to nullableJson(response.bodyUtf8),
        "sourceHash" to nullableJson(response.sourceHash),
    ))

    private fun nullableJson(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    private fun nullableJson(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun encode(element: JsonElement, maxBytes: Int): ByteArray = element.toString().toByteArray(Charsets.UTF_8).also {
        if (it.size > maxBytes) throw wireError(ExtensionWireErrorCode.SIZE_LIMIT, "encoded JSON input exceeds its byte limit")
    }

    private fun validateContextForEncoding(context: ExtensionContextV1) {
        ensure(context.sourceRoles.isNotEmpty()) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "host context must request at least one source role")
        }
        ensure(context.sourceRoles.size <= SourceRole.entries.size && context.sourceRoles.distinct().size == context.sourceRoles.size) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "invalid sourceRoles in host context")
        }
        ensure(context.targets.size <= MAX_TARGETS && context.targets.map { it.targetToken }.distinct().size == context.targets.size) {
            wireError(ExtensionWireErrorCode.SIZE_LIMIT, "invalid target count or duplicate target token")
        }
        validateObservedAt(context.observedAt)
        context.targets.forEach { target ->
            boundedUtf8(target.targetToken, 128, "targetToken")
            ensure(target.targetToken.isNotBlank()) { wireError(ExtensionWireErrorCode.INVALID_FIELD, "targetToken is blank") }
            boundedUtf8(target.providerSeriesKey, 512, "providerSeriesKey")
            ensure(target.providerSeriesKey.isNotBlank()) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "providerSeriesKey is blank")
            }
            target.providerUrl?.let { boundedUtf8(it, 2048, "providerUrl") }
            ensure(target.sourceSeason == null || target.sourceSeason in 0..9999)
            ensure(target.navigationSeason == null || target.navigationSeason in 0..9999)
            target.installment.number?.let {
                boundedUtf8(it, 32, "installment.number")
                ensure(DECIMAL_EPISODE.matches(it)) {
                    wireError(ExtensionWireErrorCode.INVALID_FIELD, "installment.number is not a plain decimal")
                }
            }
        }
    }

    private fun validateResponseForEncoding(response: ResponseEnvelope) {
        validateRequestId(boundedUtf8(response.requestId, 64, "requestId").toString(Charsets.UTF_8), "requestId")
        ensure(response.httpStatus == null || response.httpStatus in 100..599) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "httpStatus is out of range")
        }
        response.finalUrl?.let {
            boundedUtf8(it, 2048, "finalUrl")
            ensure(isHttpsUrl(it)) { wireError(ExtensionWireErrorCode.INVALID_FIELD, "finalUrl must be HTTPS") }
        }
        if (response.status == ExtensionResponseStatus.OK) {
            ensure(response.httpStatus != null && response.httpStatus in 200..299) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "successful httpStatus must be 2xx")
            }
            val finalUrl = response.finalUrl ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "successful finalUrl is missing")
            val body = response.bodyUtf8 ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "successful body is missing")
            val bodyBytes = boundedUtf8(body, MAX_BODY_BYTES, "bodyUtf8")
            ensure(response.sourceHash == sha256(bodyBytes)) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "sourceHash differs from exact response bytes")
            }
        } else {
            ensure(response.bodyUtf8 == null && response.sourceHash == null) {
                wireError(ExtensionWireErrorCode.INVALID_FIELD, "failed response cannot contain body or hash")
            }
        }
    }

    private fun boundedUtf8(value: String, maxBytes: Int, field: String): ByteArray {
        ensure(hasWellFormedSurrogates(value)) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$field contains malformed Unicode")
        }
        if (value.length > maxBytes) throw wireError(ExtensionWireErrorCode.SIZE_LIMIT, "$field exceeds its byte limit")
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw wireError(ExtensionWireErrorCode.SIZE_LIMIT, "$field exceeds its UTF-8 byte limit")
        return bytes
    }

    private fun hasWellFormedSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(char) -> return false
                else -> index++
            }
        }
        return true
    }

    private fun decode(bytes: ByteArray, maxBytes: Int): JsonElement {
        if (bytes.size > maxBytes) throw wireError(ExtensionWireErrorCode.SIZE_LIMIT, "JSON input exceeds its byte limit")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw wireError(ExtensionWireErrorCode.INVALID_UTF8, "JSON input is not valid UTF-8")
        }
        JsonDuplicateKeyGuard(text, MAX_DEPTH).validate()
        return try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            throw wireError(ExtensionWireErrorCode.INVALID_JSON, "JSON syntax is invalid")
        }
    }

    private fun JsonElement.obj(path: String): JsonObject = this as? JsonObject
        ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path must be an object")

    private fun JsonElement.array(path: String, maxItems: Int): JsonArray {
        val values = this as? JsonArray ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path must be an array")
        if (values.size > maxItems) throw wireError(ExtensionWireErrorCode.SIZE_LIMIT, "$path has too many entries")
        return values
    }

    private fun JsonObject.exactFields(expected: Set<String>, path: String): Map<String, JsonElement> {
        val unknown = keys - expected
        if (unknown.isNotEmpty()) throw wireError(ExtensionWireErrorCode.UNKNOWN_FIELD, "$path contains an unknown field")
        val missing = expected - keys
        if (missing.isNotEmpty()) throw wireError(ExtensionWireErrorCode.MISSING_FIELD, "$path is missing a required field")
        return this
    }

    private fun JsonElement.string(path: String, maxUtf8Bytes: Int): String {
        val value = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path must be a string")
        if (value.toByteArray(Charsets.UTF_8).size > maxUtf8Bytes) {
            throw wireError(ExtensionWireErrorCode.SIZE_LIMIT, "$path exceeds its UTF-8 byte limit")
        }
        return value
    }

    private fun JsonElement.nullableString(path: String, maxUtf8Bytes: Int): String? =
        if (this === JsonNull) null else string(path, maxUtf8Bytes)

    private fun JsonElement.boolean(path: String): Boolean = (this as? JsonPrimitive)?.let {
        if (it.isString) null else it.content.toBooleanStrictOrNull()
    } ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path must be a boolean")

    private fun JsonElement.int(path: String): Int {
        val primitive = this as? JsonPrimitive
        val raw = primitive?.takeIf { !it.isString }?.content
        if (raw == null || !raw.matches(INTEGER)) throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path must be an integer")
        val value = raw.toLongOrNull()?.takeIf {
            it in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER &&
                it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong()
        }
            ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path is outside the safe integer range")
        return value.toInt()
    }

    private fun JsonElement.nullableInt(path: String, range: IntRange): Int? = if (this === JsonNull) null else {
        int(path).also { value ->
            ensure(value in range) { wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path is out of range") }
        }
    }

    private inline fun <reified T : Enum<T>> JsonElement.enum(path: String): T {
        val value = string(path, 64)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path has an unsupported enum value")
    }

    private fun JsonElement.timestamp(path: String): String = string(path, 64).also { value ->
        try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path must be RFC3339 with an explicit offset")
        }
    }

    private fun JsonElement.nullableTimestamp(path: String): String? =
        if (this === JsonNull) null else timestamp(path)

    private fun requireSchema(version: Int, path: String) {
        if (version != SCHEMA_VERSION) throw wireError(ExtensionWireErrorCode.UNSUPPORTED_SCHEMA, "$path schema is unsupported")
    }

    private fun parseExtensionId(value: String): ExtensionId = try {
        ExtensionId.parse(value)
    } catch (_: IllegalArgumentException) {
        throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "extensionId has invalid syntax")
    }

    private fun parseProviderId(value: String): ProviderId = try {
        ProviderId.parse(value)
    } catch (_: IllegalArgumentException) {
        throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "providerId has invalid syntax")
    }

    private fun validateRequestId(value: String, path: String): String = value.also {
        ensure(it.matches(REQUEST_ID)) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "$path has invalid syntax")
        }
    }

    private fun validateObservedAt(value: String): String = value.also {
        boundedUtf8(it, 64, "observedAt")
        val parsed = try {
            OffsetDateTime.parse(it)
        } catch (_: Exception) {
            throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "observedAt must be UTC RFC3339")
        }
        ensure(parsed.offset.totalSeconds == 0) {
            wireError(ExtensionWireErrorCode.INVALID_FIELD, "observedAt must use the UTC offset")
        }
    }

    private inline fun ensure(condition: Boolean, lazyError: () -> ExtensionWireException) {
        if (!condition) throw lazyError()
    }

    private fun ensure(condition: Boolean) {
        if (!condition) throw wireError(ExtensionWireErrorCode.INVALID_FIELD, "envelope validation failed")
    }

    private fun wireError(code: ExtensionWireErrorCode, message: String) = ExtensionWireException(code, message)

    private fun isSha256(value: String?): Boolean = value != null && SHA256.matches(value)

    private fun isHttpsUrl(value: String): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.host != null && (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null && uri.rawFragment == null
    } catch (_: Exception) {
        false
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,64}")
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    private val DECIMAL_EPISODE = Regex("(0|[1-9][0-9]*)(\\.[0-9]+)?")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}

/** Syntax pass that rejects duplicate decoded keys and malformed surrogate pairs. */
private class JsonDuplicateKeyGuard(
    private val source: String,
    private val maxDepth: Int,
) {
    private var offset = 0
    private var nodes = 0

    fun validate() {
        whitespace()
        value(0)
        whitespace()
        if (offset != source.length) invalid("trailing JSON content")
    }

    private fun value(depth: Int) {
        if (depth > maxDepth) invalid("JSON nesting is too deep")
        nodes += 1
        if (nodes > 100_000) invalid("JSON contains too many values")
        whitespace()
        when (peek()) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> numberValue()
            else -> invalid("invalid JSON value")
        }
    }

    private fun objectValue(depth: Int) {
        offset++
        whitespace()
        if (consume('}')) return
        val names = HashSet<String>()
        var count = 0
        while (true) {
            whitespace()
            if (peek() != '"') invalid("object member name must be a string")
            val name = stringValue()
            if (!names.add(name)) throw ExtensionWireException(ExtensionWireErrorCode.DUPLICATE_KEY, "JSON object contains a duplicate key")
            count++
            if (count > 256) invalid("JSON object has too many fields")
            whitespace()
            expect(':')
            value(depth)
            whitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun arrayValue(depth: Int) {
        offset++
        whitespace()
        if (consume(']')) return
        var count = 0
        while (true) {
            value(depth)
            count++
            if (count > 4096) invalid("JSON array has too many entries")
            whitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun stringValue(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < source.length) {
            val char = source[offset++]
            when {
                char == '"' -> return result.toString()
                char == '\\' -> escaped(result)
                char.code < 0x20 -> invalid("unescaped control character in JSON string")
                Character.isHighSurrogate(char) -> {
                    if (offset >= source.length || !Character.isLowSurrogate(source[offset])) invalid("unpaired high surrogate")
                    result.append(char).append(source[offset++])
                }
                Character.isLowSurrogate(char) -> invalid("unpaired low surrogate")
                else -> result.append(char)
            }
        }
        invalid("unterminated JSON string")
    }

    private fun escaped(result: StringBuilder) {
        if (offset >= source.length) invalid("truncated JSON escape")
        when (val char = source[offset++]) {
            '"', '\\', '/' -> result.append(char)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val first = hexUnit()
                when {
                    Character.isHighSurrogate(first) -> {
                        if (offset + 1 >= source.length || source[offset] != '\\' || source[offset + 1] != 'u') {
                            invalid("unpaired escaped high surrogate")
                        }
                        offset += 2
                        val second = hexUnit()
                        if (!Character.isLowSurrogate(second)) invalid("invalid escaped surrogate pair")
                        result.append(first).append(second)
                    }
                    Character.isLowSurrogate(first) -> invalid("unpaired escaped low surrogate")
                    else -> result.append(first)
                }
            }
            else -> invalid("invalid JSON escape")
        }
    }

    private fun hexUnit(): Char {
        if (offset + 4 > source.length) invalid("truncated unicode escape")
        var value = 0
        repeat(4) {
            val digit = source[offset++].digitToIntOrNull(16) ?: invalid("invalid unicode escape")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun numberValue() {
        consume('-')
        when {
            consume('0') -> if (peek() in '0'..'9') invalid("leading zero in JSON number")
            peek() in '1'..'9' -> while (peek() in '0'..'9') offset++
            else -> invalid("invalid JSON number")
        }
        if (consume('.')) {
            if (peek() !in '0'..'9') invalid("invalid JSON fraction")
            while (peek() in '0'..'9') offset++
        }
        if (peek() == 'e' || peek() == 'E') {
            offset++
            if (peek() == '+' || peek() == '-') offset++
            if (peek() !in '0'..'9') invalid("invalid JSON exponent")
            while (peek() in '0'..'9') offset++
        }
    }

    private fun literal(value: String) {
        if (!source.startsWith(value, offset)) invalid("invalid JSON literal")
        offset += value.length
    }

    private fun whitespace() {
        while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') offset++
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) invalid("expected '$expected'")
    }

    private fun consume(expected: Char): Boolean {
        if (peek() != expected) return false
        offset++
        return true
    }

    private fun peek(): Char = source.getOrNull(offset) ?: '\u0000'

    private fun invalid(message: String): Nothing =
        throw ExtensionWireException(ExtensionWireErrorCode.INVALID_JSON, message)
}

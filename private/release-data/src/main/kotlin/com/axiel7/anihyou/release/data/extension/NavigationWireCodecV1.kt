package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationPlanOutputV1
import com.axiel7.anihyou.release.core.extension.NavigationParseOutputV1
import com.axiel7.anihyou.release.core.extension.NavigationRequestSpecV1
import com.axiel7.anihyou.release.core.extension.NavigationResponseEnvelopeV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationDiagnosticV1
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Strict sibling capability codec. Release observations never carry navigation URLs. */
object NavigationWireCodecV1 {
    private const val MAX_OUTPUT = 64 * 1024
    private const val MAX_REQUESTS = 7
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_PARSE_INPUT = 4 * 1024 * 1024
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val HOST = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
    private val DECIMAL = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]*[1-9])?")

    fun encodeContext(context: NavigationContextV1): ByteArray {
        checkContext(context)
        return JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "extensionId" to JsonPrimitive(context.extensionId.value),
            "providerId" to JsonPrimitive(context.providerId.value),
            "observedAt" to JsonPrimitive(context.observedAt),
            "targetKind" to JsonPrimitive(context.targetKind.name),
            "targetToken" to JsonPrimitive(context.targetToken),
            "providerSeriesKey" to JsonPrimitive(context.providerSeriesKey),
            "providerRouteHint" to nullable(context.providerRouteHint),
            "sourceSeason" to (context.sourceSeason?.let(::JsonPrimitive) ?: JsonNull),
            "providerEpisode" to nullable(context.providerEpisode),
            "track" to nullable(context.track?.name),
        )).toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_OUTPUT) { "navigation context exceeds byte bound" }
        }
    }

    fun encodeParseInput(
        context: NavigationContextV1,
        responses: List<NavigationResponseEnvelopeV1>,
        allowedHosts: Set<String>,
    ): ByteArray {
        checkContext(context)
        require(responses.size <= MAX_REQUESTS && responses.map { it.requestId }.distinct().size == responses.size)
        val encodedResponses = responses.map { response ->
            require(ID.matches(response.requestId))
            if (response.status == ExtensionResponseStatus.OK) {
                require(response.httpStatus?.let { it in 200..299 } == true)
                val finalUrl = requireNotNull(response.finalUrl)
                validateUrl(finalUrl, allowedHosts)
                val body = requireNotNull(response.bodyUtf8)
                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                require(bodyBytes.size <= MAX_BODY_BYTES)
                require(response.sourceHash == sha256(bodyBytes))
            } else {
                require(response.bodyUtf8 == null && response.sourceHash == null)
                response.finalUrl?.let { validateUrl(it, allowedHosts) }
            }
            JsonObject(linkedMapOf(
                "requestId" to JsonPrimitive(response.requestId),
                "status" to JsonPrimitive(response.status.name),
                "httpStatus" to (response.httpStatus?.let(::JsonPrimitive) ?: JsonNull),
                "finalUrl" to nullable(response.finalUrl),
                "bodyUtf8" to nullable(response.bodyUtf8),
                "sourceHash" to nullable(response.sourceHash),
            ))
        }
        val contextJson = ExtensionWireCodec.parseStrictJson(encodeContext(context), MAX_OUTPUT)
        return JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "context" to contextJson,
            "responses" to JsonArray(encodedResponses),
        )).toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_PARSE_INPUT) { "navigation parse input exceeds byte bound" }
        }
    }

    fun decodePlan(bytes: ByteArray, context: NavigationContextV1, allowedHosts: Set<String>): NavigationPlanOutputV1 {
        checkContext(context)
        val root = ExtensionWireCodec.parseStrictJson(bytes, MAX_OUTPUT).obj("plan")
        root.exact(setOf("schemaVersion", "requests"), "plan")
        root.version()
        val requests = root.getValue("requests").array("requests", MAX_REQUESTS).map { item ->
            val value = item.obj("request").exact(setOf("requestId", "url"), "request")
            NavigationRequestSpecV1(value.getValue("requestId").text("requestId", 64).also { require(ID.matches(it)) },
                validateUrl(value.getValue("url").text("url", 2048), allowedHosts))
        }
        require(requests.map { it.requestId }.distinct().size == requests.size) { "duplicate navigation request" }
        return NavigationPlanOutputV1(1, requests)
    }

    fun decodeTargets(
        bytes: ByteArray,
        context: NavigationContextV1,
        responses: List<NavigationResponseEnvelopeV1>,
        allowedHosts: Set<String>,
    ): NavigationParseOutputV1 {
        checkContext(context)
        require(responses.size <= MAX_REQUESTS && responses.map { it.requestId }.distinct().size == responses.size)
        val root = ExtensionWireCodec.parseStrictJson(bytes, MAX_OUTPUT).obj("navigation output")
        root.exact(setOf("schemaVersion", "targets"), "navigation output")
        root.version()
        val targets = root.getValue("targets").array("targets", 1).map { item ->
            val value = item.obj("target").exact(setOf(
                "schemaVersion", "extensionId", "providerId", "targetKind", "providerSeriesKey",
                "sourceSeason", "providerEpisode", "track", "url", "requestId", "sourceHash", "diagnostics",
            ), "target")
            value.version()
            val extensionId = ExtensionId.parse(value.getValue("extensionId").text("extensionId", 128))
            val providerId = ProviderId.parse(value.getValue("providerId").text("providerId", 128))
            val kind = enumValue<NavigationTargetKind>(value.getValue("targetKind"), "targetKind")
            val key = value.getValue("providerSeriesKey").text("providerSeriesKey", 512)
            val season = value.getValue("sourceSeason").season()
            val episode = value.getValue("providerEpisode").nullableText("providerEpisode", 32)
            val track = value.getValue("track").takeUnless { it == JsonNull }?.let {
                enumValue<ObservationTrack>(it, "track")
            }
            require(extensionId == context.extensionId && providerId == context.providerId &&
                kind == context.targetKind && key == context.providerSeriesKey && season == context.sourceSeason &&
                episode == context.providerEpisode && track == context.track) { "navigation coordinate mismatch" }
            val url = validateUrl(value.getValue("url").text("url", 2048), allowedHosts)
            val requestId = value.getValue("requestId").nullableText("requestId", 64)
            val sourceHash = value.getValue("sourceHash").nullableText("sourceHash", 64)
            if (requestId == null) require(sourceHash == null) { "unbound navigation provenance" }
            else {
                require(ID.matches(requestId) && sourceHash != null && SHA256.matches(sourceHash))
                val source = responses.singleOrNull { it.requestId == requestId }
                require(source != null && source.status == ExtensionResponseStatus.OK &&
                    source.sourceHash == sourceHash && source.finalUrl != null &&
                    source.httpStatus?.let { it in 200..299 } == true) { "navigation source provenance mismatch" }
                validateUrl(source.finalUrl!!, allowedHosts)
            }
            val diagnostics = value.getValue("diagnostics").array("diagnostics", 16).map { diagnostic ->
                val detail = diagnostic.obj("diagnostic").exact(setOf("code", "message"), "diagnostic")
                ObservationDiagnosticV1(detail.getValue("code").text("code", 64),
                    detail.getValue("message").text("message", 256))
            }
            ProviderNavigationTargetV1(1, extensionId, providerId, kind, key, season, episode, track,
                url, requestId, sourceHash, diagnostics)
        }
        return NavigationParseOutputV1(1, targets)
    }

    private fun checkContext(context: NavigationContextV1) {
        require(context.schemaVersion == 1 && context.targetToken.matches(ID) &&
            context.providerSeriesKey.isNotBlank() && context.providerSeriesKey.toByteArray().size <= 512 &&
            context.providerRouteHint?.toByteArray()?.size?.let { it <= 2048 } != false &&
            context.sourceSeason?.let { it in 0..9999 } != false)
        OffsetDateTime.parse(context.observedAt)
        when (context.targetKind) {
            NavigationTargetKind.OVERVIEW -> require(context.providerEpisode == null && context.track == null)
            NavigationTargetKind.EPISODE -> {
                val episode = context.providerEpisode
                require(episode != null && episode.matches(DECIMAL) && episode.length <= 32)
            }
        }
    }

    private fun validateUrl(value: String, allowedHosts: Set<String>): String {
        val uri = URI(value)
        val host = uri.host ?: throw IllegalArgumentException("navigation host missing")
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) && host == host.lowercase(Locale.ROOT) &&
            HOST.matches(host) && !host.all { it.isDigit() || it == '.' } && host in allowedHosts)
        return value
    }

    private fun JsonElement.obj(field: String): JsonObject = this as? JsonObject
        ?: throw IllegalArgumentException("$field must be object")
    private fun JsonObject.exact(keys: Set<String>, field: String): JsonObject = also {
        require(this.keys == keys) { "$field has missing or unknown fields" }
    }
    private fun JsonObject.version() {
        val raw = (getValue("schemaVersion") as? JsonPrimitive)?.takeUnless { it.isString }?.content
        require(raw == "1") { "unsupported navigation schema" }
    }
    private fun JsonElement.array(field: String, max: Int): JsonArray = (this as? JsonArray)
        ?.also { require(it.size <= max) { "$field exceeds item bound" } }
        ?: throw IllegalArgumentException("$field must be array")
    private fun JsonElement.text(field: String, max: Int): String = (this as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.also { require(it.isNotEmpty() && it.toByteArray().size <= max) }
        ?: throw IllegalArgumentException("$field must be bounded string")
    private fun JsonElement.nullableText(field: String, max: Int): String? =
        if (this == JsonNull) null else text(field, max)
    private fun JsonElement.season(): Int? = if (this == JsonNull) null else {
        val raw = (this as? JsonPrimitive)?.takeUnless { it.isString }?.content
        require(raw != null && raw.matches(Regex("0|[1-9][0-9]*")))
        raw.toIntOrNull()?.also { require(it in 0..9999) }
            ?: throw IllegalArgumentException("invalid season")
    }
    private inline fun <reified T : Enum<T>> enumValue(value: JsonElement, field: String): T =
        enumValues<T>().singleOrNull { it.name == value.text(field, 64) }
            ?: throw IllegalArgumentException("unknown $field")
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

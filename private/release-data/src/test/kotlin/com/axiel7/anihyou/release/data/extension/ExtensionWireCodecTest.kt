package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionGuestErrorCode
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtensionWireCodecTest {
    @Test
    fun `plan input accepts explicit nullable target fields and exact v1 roles`() {
        val input = ExtensionWireCodec.decodePlanInput(PLAN_INPUT.toByteArray())

        assertEquals(1, input.schemaVersion)
        assertEquals(ExtensionId.parse("de.aniworld"), input.context.extensionId)
        assertEquals(ProviderId.parse("de.aniworld"), input.context.providerId)
        assertEquals(listOf(SourceRole.CALENDAR), input.context.sourceRoles)
        assertEquals(null, input.context.targets.single().providerUrl)
        assertEquals(input, ExtensionWireCodec.decodePlanInput(ExtensionWireCodec.encodePlanInput(input)))
    }

    @Test
    fun `plan output validates roles request ids and host target tokens`() {
        val context = ExtensionWireCodec.decodePlanInput(PLAN_INPUT.toByteArray()).context
        val decoded = ExtensionWireCodec.decodePlanOutput(PLAN_OUTPUT.toByteArray(), context)

        assertEquals("calendar", decoded.requests.single().requestId)
        assertEquals(SourceRole.CALENDAR, decoded.requests.single().sourceRole)
        assertEquals(null, decoded.requests.single().targetToken)
    }

    @Test
    fun `duplicate keys are rejected before JSON tree parsing`() {
        val duplicate = PLAN_INPUT.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"\\u0073chemaVersion\":1")

        assertWireError(ExtensionWireErrorCode.DUPLICATE_KEY) {
            ExtensionWireCodec.decodePlanInput(duplicate.toByteArray())
        }
    }

    @Test
    fun `invalid UTF8 is rejected`() {
        assertWireError(ExtensionWireErrorCode.INVALID_UTF8) {
            ExtensionWireCodec.decodePlanInput(byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d))
        }
    }

    @Test
    fun `unpaired escaped surrogate is rejected before tree parsing`() {
        val malformed = PLAN_INPUT.replace("series-1", "series-\\uD800")

        assertWireError(ExtensionWireErrorCode.INVALID_JSON) {
            ExtensionWireCodec.decodePlanInput(malformed.toByteArray())
        }
    }

    @Test
    fun `unknown and forbidden identity fields are rejected`() {
        val forbidden = PLAN_INPUT.replace("\"observedAt\"", "\"anilistId\":42,\"observedAt\"")

        assertWireError(ExtensionWireErrorCode.UNKNOWN_FIELD) {
            ExtensionWireCodec.decodePlanInput(forbidden.toByteArray())
        }
    }

    @Test
    fun `unknown schema and excessive nesting fail closed`() {
        val unsupported = PLAN_INPUT.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertWireError(ExtensionWireErrorCode.UNSUPPORTED_SCHEMA) {
            ExtensionWireCodec.decodePlanInput(unsupported.toByteArray())
        }

        val tooDeep = "{\"schemaVersion\":1,\"context\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}"
        assertWireError(ExtensionWireErrorCode.INVALID_JSON) {
            ExtensionWireCodec.decodePlanInput(tooDeep.toByteArray())
        }
    }

    @Test
    fun `oversized envelopes are rejected before parsing`() {
        assertWireError(ExtensionWireErrorCode.SIZE_LIMIT) {
            ExtensionWireCodec.decodePlanInput(ByteArray(256 * 1024 + 1))
        }
    }

    @Test
    fun `versioned guest error envelopes have a closed shape`() {
        val error = ExtensionWireCodec.decodeErrorOutput(
            """{"schemaVersion":1,"error":{"code":"PARSE_FAILED"}}""".toByteArray(),
        )
        assertEquals(ExtensionGuestErrorCode.PARSE_FAILED, error.code)

        assertWireError(ExtensionWireErrorCode.UNKNOWN_FIELD) {
            ExtensionWireCodec.decodeErrorOutput(
                """{"schemaVersion":1,"error":{"code":"PARSE_FAILED","message":"ignored"}}""".toByteArray(),
            )
        }
    }

    @Test
    fun `required nullable fields cannot be omitted`() {
        val missingSeason = PLAN_INPUT.replace("\"sourceSeason\":null,", "")

        assertWireError(ExtensionWireErrorCode.MISSING_FIELD) {
            ExtensionWireCodec.decodePlanInput(missingSeason.toByteArray())
        }
    }

    @Test
    fun `parse output binds provenance and claim kind to successful host response`() {
        val input = ExtensionWireCodec.decodeParseInput(parseInput(ExtensionResponseStatus.OK))
        val parsed = ExtensionWireCodec.decodeParseOutput(validParseOutput(), input)

        assertEquals(1, parsed.observations.size)
        assertEquals("episode 1", parsed.observations.single().rawTitle)
        assertEquals("request-1", parsed.responseReports.single().requestId)
    }

    @Test
    fun `guest cannot relabel source role claim or host response provenance`() {
        val input = ExtensionWireCodec.decodeParseInput(parseInput(ExtensionResponseStatus.OK))

        val wrongClaim = validParseOutput().replace("\"claimKind\":\"FORECAST\"", "\"claimKind\":\"RELEASE_LISTING\"")
        assertWireError(ExtensionWireErrorCode.INVALID_FIELD) {
            ExtensionWireCodec.decodeParseOutput(wrongClaim, input)
        }

        val wrongHash = validParseOutput().replace(HASH, "${"a".repeat(64)}")
        assertWireError(ExtensionWireErrorCode.INVALID_FIELD) {
            ExtensionWireCodec.decodeParseOutput(wrongHash, input)
        }
    }

    @Test
    fun `guest cannot report transport failure as successful coverage`() {
        val input = ExtensionWireCodec.decodeParseInput(parseInput(ExtensionResponseStatus.TRANSPORT_FAILURE))
        val output = """{"schemaVersion":1,"observations":[],"responseReports":[{"requestId":"request-1","outcome":"SUCCESS","diagnostics":[] }]}"""

        assertWireError(ExtensionWireErrorCode.INVALID_FIELD) {
            ExtensionWireCodec.decodeParseOutput(output.toByteArray(), input)
        }
    }

    @Test
    fun `extensions never emit empty parse result without per-response report`() {
        val input = ExtensionWireCodec.decodeParseInput(parseInput(ExtensionResponseStatus.OK))
        val output = """{"schemaVersion":1,"observations":[],"responseReports":[]}"""

        assertWireError(ExtensionWireErrorCode.INVALID_FIELD) {
            ExtensionWireCodec.decodeParseOutput(output.toByteArray(), input)
        }
    }

    private fun assertWireError(code: ExtensionWireErrorCode, block: () -> Unit) {
        val error = assertThrows(ExtensionWireException::class.java, block)
        assertEquals(code, error.code)
    }

    private fun parseInput(status: ExtensionResponseStatus): ByteArray {
        val response = if (status == ExtensionResponseStatus.OK) {
            """{"requestId":"request-1","sourceRole":"CALENDAR","status":"OK","httpStatus":200,"finalUrl":"https://aniworld.to/calendar","bodyUtf8":"<html/>","sourceHash":"$HASH"}"""
        } else {
            """{"requestId":"request-1","sourceRole":"CALENDAR","status":"TRANSPORT_FAILURE","httpStatus":null,"finalUrl":null,"bodyUtf8":null,"sourceHash":null}"""
        }
        return """{"schemaVersion":1,"context":$CONTEXT,"responses":[$response]}""".toByteArray()
    }

    private fun validParseOutput(): ByteArray = """
        {"schemaVersion":1,"observations":[{
          "schemaVersion":1,"extensionId":"de.aniworld","providerId":"de.aniworld","requestId":"request-1",
          "sourceRole":"CALENDAR","providerSeriesKey":null,"rawTitle":"episode 1","sourceSeason":null,
          "navigationSeason":null,"installment":{"kind":"EPISODE","number":"1"},"track":"UNKNOWN",
          "claimKind":"FORECAST","sourceDateText":null,"sourceTimeText":null,"sourceRawText":null,
          "parsedTimestamp":null,"approximate":false,"scheduleMarker":"NONE","correctionMarker":null,
          "sourceUrl":"https://aniworld.to/calendar","sourceHash":"$HASH","diagnostics":[]
        }],"responseReports":[{"requestId":"request-1","outcome":"SUCCESS","diagnostics":[]}]}
    """.trimIndent().toByteArray()

    companion object {
        private val HASH = MessageDigest.getInstance("SHA-256").digest("<html/>".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private val CONTEXT = """{"extensionId":"de.aniworld","providerId":"de.aniworld","sourceRoles":["CALENDAR"],"observedAt":"2026-09-28T05:00:00Z","targets":[{"targetToken":"target-1","providerSeriesKey":"series-1","providerUrl":null,"sourceSeason":null,"navigationSeason":null,"installment":{"kind":"EPISODE","number":null},"track":"UNKNOWN"}]}"""
        private val PLAN_INPUT = """{"schemaVersion":1,"context":$CONTEXT}"""
        private val PLAN_OUTPUT = """{"schemaVersion":1,"requests":[{"requestId":"calendar","sourceRole":"CALENDAR","url":"https://aniworld.to/calendar","method":"GET","targetToken":null}]}"""
    }
}

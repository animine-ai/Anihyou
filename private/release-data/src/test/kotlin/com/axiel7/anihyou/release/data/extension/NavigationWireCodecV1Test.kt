package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.NavigationResponseEnvelopeV1
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NavigationWireCodecV1Test {
    private val hosts = setOf("example.org")
    private val overview = context(NavigationTargetKind.OVERVIEW, null)
    private val episode = context(NavigationTargetKind.EPISODE, "15")

    @Test fun `overview fixture roundtrip binds signed package and mapping coordinate`() {
        val bytes = NavigationWireCodecV1.encodeContext(overview)
        assertEquals("OVERVIEW", String(bytes).let { if (it.contains("OVERVIEW")) "OVERVIEW" else "missing" })
        val plan = NavigationWireCodecV1.decodePlan(
            """{"schemaVersion":1,"requests":[{"requestId":"lookup","url":"https://example.org/series"}]}""".toByteArray(), overview, hosts)
        assertEquals(1, plan.requests.size)
        val output = NavigationWireCodecV1.decodeTargets(
            targetJson(overview, "null", "null"), overview, emptyList(), hosts)
        assertEquals("https://example.org/target", output.targets.single().url)
    }

    @Test fun `episode fixture binds provider coordinate and response provenance`() {
        val digest = "a".repeat(64)
        val output = NavigationWireCodecV1.decodeTargets(
            targetJson(episode, "\"lookup\"", "\"$digest\""), episode,
            listOf(NavigationResponseEnvelopeV1("lookup", ExtensionResponseStatus.OK, 200,
                "https://example.org/source", "fixture", digest)), hosts)
        assertEquals("15", output.targets.single().providerEpisode)
        assertEquals(ObservationTrack.DE_SUB, output.targets.single().track)
    }

    @Test fun `mismatched episode and extension fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            NavigationWireCodecV1.decodeTargets(targetJson(episode, "null", "null")
                .toString(Charsets.UTF_8).replace("\"15\"", "\"16\"").toByteArray(), episode, emptyList(), hosts)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationWireCodecV1.decodeTargets(targetJson(overview, "null", "null")
                .toString(Charsets.UTF_8).replace("de.fixture", "de.other").toByteArray(), overview, emptyList(), hosts)
        }
    }

    @Test fun `unknown fields duplicate keys unsafe URLs and forged provenance fail closed`() {
        val good = targetJson(overview, "null", "null").toString(Charsets.UTF_8)
        for (bad in listOf(
            good.replace("\"targets\":", "\"extra\":true,\"targets\":"),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            good.replace("https://example.org/target", "https://127.0.0.1/target"),
            good.replace("https://example.org/target", "https://other.example/target"),
            good.replace("\"requestId\":null", "\"requestId\":\"lookup\""),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                NavigationWireCodecV1.decodeTargets(bad.toByteArray(), overview, emptyList(), hosts)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationWireCodecV1.decodePlan(
                """{"schemaVersion":1,"requests":[{"requestId":"x","url":"https://example.org/"},{"requestId":"x","url":"https://example.org/"}]}""".toByteArray(), overview, hosts)
        }
    }

    private fun context(kind: NavigationTargetKind, number: String?) = NavigationContextV1(
        1, ExtensionId.parse("de.fixture"), ProviderId.parse("fixture"), "2026-09-28T12:00:00Z",
        kind, "target-1", "series-1", null, 2, number,
        if (kind == NavigationTargetKind.EPISODE) ObservationTrack.DE_SUB else null)

    private fun targetJson(context: NavigationContextV1, requestId: String, hash: String): ByteArray =
        """{"schemaVersion":1,"targets":[{"schemaVersion":1,"extensionId":"${context.extensionId.value}","providerId":"${context.providerId.value}","targetKind":"${context.targetKind}","providerSeriesKey":"${context.providerSeriesKey}","sourceSeason":2,"providerEpisode":${context.providerEpisode?.let { "\"$it\"" } ?: "null"},"track":${context.track?.let { "\"$it\"" } ?: "null"},"url":"https://example.org/target","requestId":$requestId,"sourceHash":$hash,"diagnostics":[]}]}""".toByteArray()
}

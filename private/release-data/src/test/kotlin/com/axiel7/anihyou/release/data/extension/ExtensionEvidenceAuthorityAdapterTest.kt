package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionEvidenceAuthorityAdapterTest {
    private val approved = ApprovedExtensionAuthorityTuple(
        "publisher-a", "key-a", "de.aniworld", "aniworld", setOf(SourceRole.RECENT))
    private val adapter = ExtensionEvidenceAuthorityAdapter(setOf(approved))

    @Test fun approvedRecentUsesExistingFingerprintAndDeduplicatesAcrossPolls() {
        val first = adapter.project(completed())
        val second = adapter.project(completed(receipt().copy(generationId = "next-generation")))
        assertEquals(1, first.size)
        assertEquals(first.single().id, second.single().id)
        assertEquals(ReleaseSourceType.ANIWORLD_RECENT, first.single().sourceType)
        assertEquals(ReleaseEvidenceType.CONFIRMATION, first.single().evidenceType)
        assertEquals(Installment.Episode(12, 25), first.single().installment)
        assertTrue(first.single().id.startsWith("aniworld-v3:ANIWORLD_RECENT:"))
    }

    @Test fun launderingByPublisherKeyExtensionProviderOrRoleFailsClosed() {
        assertTrue(adapter.project(completed(receipt().copy(publisherId = "other"))).isEmpty())
        assertTrue(adapter.project(completed(receipt().copy(signingKeyId = "other"))).isEmpty())
        assertTrue(adapter.project(completed(receipt().copy(extensionId = ExtensionId.parse("other")))).isEmpty())
        assertTrue(adapter.project(completed(receipt().copy(providerId = ProviderId.parse("other")))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(sourceRole = SourceRole.DIRECT))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            providerId = ProviderId.parse("other")))).isEmpty())
        assertTrue(ExtensionEvidenceAuthorityAdapter(emptySet()).project(completed()).isEmpty())
        assertTrue(ExtensionEvidenceAuthorityAdapter(setOf(approved.copy(roles = setOf(SourceRole.CALENDAR))))
            .project(completed()).isEmpty())
    }

    @Test fun unboundOrForgedResponseCannotBecomeAuthority() {
        assertTrue(adapter.project(completed(provenance = emptyList())).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            sourceHash = "0".repeat(64)))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            providerSeriesKey = "../wrong"))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            track = ObservationTrack.UNKNOWN))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            sourceSeason = null))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            installment = InstallmentV1(ObservationInstallmentKind.EPISODE, "12.05")))).isEmpty())
        assertTrue(adapter.project(completed(observation = observation().copy(
            claimKind = ObservationClaimKind.FORECAST))).isEmpty())
    }

    private fun completed(
        receipt: ExtensionExecutionReceipt = receipt(),
        observation: ProviderObservationV1 = observation(),
        provenance: List<ExtensionResponseProvenance> = listOf(provenance()),
    ) = ExtensionHostResult.Completed(receipt, listOf(observation), emptyList(), provenance)

    private fun receipt() = ExtensionExecutionReceipt(
        "receipt", "generation", ExtensionId.parse("de.aniworld"), ProviderId.parse("aniworld"),
        "publisher-a", "key-a", 1, "a".repeat(64), "b".repeat(64), "c".repeat(64),
        1, 1, 1, "48.0.3", "2026-09-29T00:00:00Z", "2026-09-29T00:00:01Z")

    private fun observation() = ProviderObservationV1(
        1, ExtensionId.parse("de.aniworld"), ProviderId.parse("aniworld"), "recent-1",
        SourceRole.RECENT, "example-series", "Example", 2, 2,
        InstallmentV1(ObservationInstallmentKind.EPISODE, "12.25"), ObservationTrack.DE_SUB,
        ObservationClaimKind.RELEASE_LISTING, null, null, null, "2026-09-29T00:00:00Z",
        false, ObservationScheduleMarker.NONE, null, "https://aniworld.to/neue-episoden",
        "d".repeat(64), emptyList())

    private fun provenance() = ExtensionResponseProvenance("recent-1",
        "https://aniworld.to/neue-episoden", 200, emptyMap(), "d".repeat(64), 20, 0,
        "1.1.1.1", Instant.parse("2026-09-29T00:00:01Z"))
}

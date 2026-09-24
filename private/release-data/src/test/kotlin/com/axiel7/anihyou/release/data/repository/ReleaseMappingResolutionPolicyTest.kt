package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMappingResolutionPolicyTest {
    @Test
    fun unresolvedAndAmbiguousAutomaticMappingsRemainEligibleForFutureResolution() {
        assertTrue((null as ReleaseMapping?).needsAutomaticResolution())
        assertTrue(mapping(MappingConfidence.AMBIGUOUS, MappingOrigin.AUTO).needsAutomaticResolution())
        assertFalse(mapping(MappingConfidence.EXACT, MappingOrigin.AUTO).needsAutomaticResolution())
        assertFalse(mapping(MappingConfidence.HIGH, MappingOrigin.AUTO).needsAutomaticResolution())
    }

    @Test
    fun manualMappingNeverReentersAutomaticResolution() {
        assertFalse(mapping(MappingConfidence.AMBIGUOUS, MappingOrigin.MANUAL).needsAutomaticResolution())
        assertFalse(mapping(MappingConfidence.EXACT, MappingOrigin.MANUAL).needsAutomaticResolution())
    }

    @Test
    fun freshAutomaticResultReplacesAmbiguousAutomaticResultButNeverManualChoice() {
        val ambiguous = mapping(MappingConfidence.AMBIGUOUS, MappingOrigin.AUTO, mediaId = 11)
        val resolved = mapping(MappingConfidence.HIGH, MappingOrigin.AUTO, mediaId = 22)
        val manual = mapping(MappingConfidence.EXACT, MappingOrigin.MANUAL, mediaId = 33)

        assertEquals(resolved, chooseResolvedMapping(ambiguous, resolved))
        assertEquals(manual, chooseResolvedMapping(manual, resolved))
        assertEquals(ambiguous, chooseResolvedMapping(ambiguous, null))
    }

    private fun mapping(
        confidence: MappingConfidence,
        origin: MappingOrigin,
        mediaId: Int = 42,
    ) = ReleaseMapping(
        mediaId = mediaId,
        confidence = confidence,
        score = 0.9,
        runnerUpMargin = 0.1,
        evidence = "test",
        matcherVersion = "test-v1",
        origin = origin,
    )
}

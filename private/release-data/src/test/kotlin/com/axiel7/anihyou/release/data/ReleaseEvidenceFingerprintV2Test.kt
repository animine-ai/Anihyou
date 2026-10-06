package com.axiel7.anihyou.release.data

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import java.time.Instant
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseEvidenceFingerprintV2Test {
    @Test
    fun goldenVectorUsesUtf8ByteLengthsAndNullMarker() {
        val digest = ReleaseEvidenceFingerprintV2.compute(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceHash = "source-β-hash-full",
            identityKey = "aniworld:/anime/épisodes/東京/2/episode:1/DE_SUB",
            evidenceType = ReleaseEvidenceType.CONFIRMATION,
            sourceReportedAt = null,
            approximateTime = true,
            scheduleCondition = ScheduleCondition.DELAYED,
        )

        assertEquals("af2d5dc685b66c4543fac529e106b8be467026d4389901cb2e75fcd0b17a8396", digest)
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun nullAndEmptyFieldsUseDifferentLengthPrefixes() {
        assertArrayEquals(
            "-1:".toByteArray(StandardCharsets.US_ASCII),
            ReleaseEvidenceFingerprintV2.encodeField(null),
        )
        assertArrayEquals(
            "0:".toByteArray(StandardCharsets.US_ASCII),
            ReleaseEvidenceFingerprintV2.encodeField(""),
        )
        assertArrayEquals(
            "2:β".toByteArray(StandardCharsets.UTF_8),
            ReleaseEvidenceFingerprintV2.encodeField("β"),
        )
    }

    @Test
    fun nullTimestampAndPresentTimestampHaveDifferentDigests() {
        val common = fingerprint(sourceReportedAt = null)
        val present = fingerprint(sourceReportedAt = Instant.parse("2026-09-26T01:23:45Z"))
        assertEquals("e54e1268e35bfb86c2cc9899a842d505c4e360034d53129d761be4e4eb059ee3", present)
        assertNotEquals(common, present)
    }

    @Test
    fun idUsesCompleteHashAndSeparatesSeasonAndLanguageIdentity() {
        val prefix = "0123456789abcdef"
        val fullHashA = prefix + "a".repeat(48)
        val fullHashB = prefix + "b".repeat(48)
        val baseline = ReleaseEvidenceFingerprintV2.evidenceId(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceHash = fullHashA,
            identityKey = "series/season:1/episode:1/DE_SUB",
            evidenceType = ReleaseEvidenceType.CONFIRMATION,
            sourceReportedAt = null,
            approximateTime = false,
            scheduleCondition = ScheduleCondition.UNKNOWN,
        )

        assertNotEquals(
            baseline,
            ReleaseEvidenceFingerprintV2.evidenceId(
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                sourceHash = fullHashB,
                identityKey = "series/season:1/episode:1/DE_SUB",
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
                sourceReportedAt = null,
                approximateTime = false,
                scheduleCondition = ScheduleCondition.UNKNOWN,
            ),
        )
        assertNotEquals(
            baseline,
            ReleaseEvidenceFingerprintV2.evidenceId(
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                sourceHash = fullHashA,
                identityKey = "series/season:2/episode:1/DE_SUB",
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
                sourceReportedAt = null,
                approximateTime = false,
                scheduleCondition = ScheduleCondition.UNKNOWN,
            ),
        )
        val dub = ReleaseEvidenceFingerprintV2.evidenceId(
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceHash = fullHashA,
            identityKey = "series/season:1/episode:1/DE_DUB",
            evidenceType = ReleaseEvidenceType.CONFIRMATION,
            sourceReportedAt = null,
            approximateTime = false,
            scheduleCondition = ScheduleCondition.UNKNOWN,
        )
        assertNotEquals(baseline, dub)
        assertEquals(
            ReleaseEvidenceFingerprintV2.compute(
                sourceType = ReleaseSourceType.ANIWORLD_RECENT,
                sourceHash = fullHashA,
                identityKey = "series/season:1/episode:1/DE_SUB",
                evidenceType = ReleaseEvidenceType.CONFIRMATION,
                sourceReportedAt = null,
                approximateTime = false,
                scheduleCondition = ScheduleCondition.UNKNOWN,
            ),
            baseline.substringAfterLast(':'),
        )
    }

    @Test
    fun observationTimeDoesNotChangeFingerprint() {
        val original = sampleEvidence()
        val polledLater = original.copy(observedAt = original.observedAt.plusSeconds(3_600))
        assertEquals(
            ReleaseEvidenceFingerprintV2.compute(original),
            ReleaseEvidenceFingerprintV2.compute(polledLater),
        )
    }

    @Test
    fun emptyPayloadFieldsRemainDistinctFromOtherValues() {
        val emptyHash = fingerprint(sourceHash = "")
        val nonEmptyHash = fingerprint(sourceHash = "x")
        val nullTime = fingerprint(sourceReportedAt = null)
        val presentTime = fingerprint(sourceReportedAt = Instant.EPOCH)
        assertNotEquals(emptyHash, nonEmptyHash)
        assertNotEquals(nullTime, presentTime)
    }

    private fun fingerprint(
        sourceHash: String = "source-β-hash-full",
        sourceReportedAt: Instant? = null,
    ): String = ReleaseEvidenceFingerprintV2.compute(
        sourceType = ReleaseSourceType.ANIWORLD_RECENT,
        sourceHash = sourceHash,
        identityKey = "aniworld:/anime/épisodes/東京/2/episode:1/DE_SUB",
        evidenceType = ReleaseEvidenceType.CONFIRMATION,
        sourceReportedAt = sourceReportedAt,
        approximateTime = true,
        scheduleCondition = ScheduleCondition.DELAYED,
    )

    private fun sampleEvidence(): ReleaseEvidence {
        val observedAt = Instant.parse("2026-09-26T01:00:00Z")
        return ReleaseEvidence(
            id = "test-id",
            sourceType = ReleaseSourceType.ANIWORLD_RECENT,
            sourceUrl = "https://aniworld.to/anime/stream/codec",
            sourceHash = "codec-full-source-hash",
            parserVersion = "codec-test",
            observedAt = observedAt,
            sourceReportedAt = Instant.parse("2026-09-25T22:00:00Z"),
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier(
                slug = "codec",
                sourceType = AniWorldIdentitySourceType.RECENT,
                firstSeenAt = observedAt,
                lastValidatedAt = observedAt,
            ),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.CONFIRMATION,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
    }
}

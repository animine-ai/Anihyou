package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.AniWorldShadowPollStore
import com.axiel7.anihyou.release.core.api.ShadowGenerationManifest
import com.axiel7.anihyou.release.core.api.ShadowGenerationSnapshot
import com.axiel7.anihyou.release.core.api.ShadowGenerationToken
import com.axiel7.anihyou.release.core.api.ShadowRequestOutcome
import com.axiel7.anihyou.release.core.api.ShadowRequestReservation
import com.axiel7.anihyou.release.core.api.ShadowRunMetrics
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.state.ShadowComparison
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldExtensionTargetSourceTest {
    private val now = Instant.parse("2026-09-29T08:00:00Z")

    @Test
    fun eligibleCanonicalDirectTargetMapsToHostOwnedProtocolIdentity() = runBlocking {
        val evidence = ReleaseEvidence(
            id = "fixture-evidence",
            sourceType = ReleaseSourceType.ANIWORLD_CALENDAR,
            sourceUrl = "https://aniworld.to/animekalender",
            sourceHash = "fixture-hash",
            parserVersion = "aniworld-v3",
            observedAt = now,
            sourceReportedAt = now.plusSeconds(3600),
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier(
                slug = "v3-target-series",
                firstSeenAt = now,
                lastValidatedAt = now,
                sourceHash = "fixture-hash",
                parserVersion = "aniworld-v3",
            ),
            sourceSeason = 1,
            navigationSeason = 2,
            installment = Installment.Episode(5),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.FORECAST,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        val key = CanonicalReleaseIdentity.from(evidence)!!.key
        val url = "https://aniworld.to/anime/stream/v3-target-series/staffel-2/episode-5"
        val candidate = DirectTargetCandidate(
            canonicalUrl = url,
            exactTargetKey = key,
            tracks = setOf(LanguageTrack.DE_SUB.name),
            priority = 1,
            firstEligibleAt = now.minusSeconds(60),
            lastAttemptAt = null,
            nextEligibleAt = null,
        )
        val source = AniWorldExtensionTargetSource(
            pollStore = PollStore(candidate),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val actual = source.targets().single()
        assertEquals(key, actual.canonicalKey)
        assertEquals("v3-target-series", actual.target.providerSeriesKey)
        assertEquals(url, actual.target.providerUrl)
        assertEquals(1, actual.target.sourceSeason)
        assertEquals(2, actual.target.navigationSeason)
        assertEquals("5", actual.target.installment.number)
        assertEquals("EPISODE", actual.target.installment.kind.name)
        assertEquals("DE_SUB", actual.target.track.name)
        assertTrue(actual.target.targetToken.startsWith("aw-target-v1-"))
    }

    private class PollStore(private val candidate: DirectTargetCandidate) : AniWorldShadowPollStore {
        override suspend fun eligibleDirectTargets(now: Instant) = listOf(candidate)
        override suspend fun shadowComparison(now: Instant): ShadowComparison = error("unused")
        override suspend fun beginGeneration(manifest: ShadowGenerationManifest, candidateSnapshotDigest: String): Boolean = error("unused")
        override suspend fun currentGeneration(token: ShadowGenerationToken): ShadowGenerationSnapshot? = error("unused")
        override suspend fun reserveRequest(token: ShadowGenerationToken, role: String, rootUrl: String,
            requestUrl: String, now: Instant): ShadowRequestReservation? = error("unused")
        override suspend fun completeRequest(reservation: ShadowRequestReservation, outcome: ShadowRequestOutcome) = error("unused")
        override suspend fun commitGeneration(token: ShadowGenerationToken, cycle: CompletedObservationCycle,
            metrics: ShadowRunMetrics): Boolean = error("unused")
        override suspend fun abortGeneration(token: ShadowGenerationToken, reason: String, now: Instant,
            metrics: ShadowRunMetrics) = error("unused")
    }
}

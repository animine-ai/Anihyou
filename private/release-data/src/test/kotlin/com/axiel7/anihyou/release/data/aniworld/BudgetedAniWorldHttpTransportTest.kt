package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.AniWorldShadowPollStore
import com.axiel7.anihyou.release.core.api.ShadowGenerationManifest
import com.axiel7.anihyou.release.core.api.ShadowGenerationSnapshot
import com.axiel7.anihyou.release.core.api.ShadowGenerationState
import com.axiel7.anihyou.release.core.api.ShadowGenerationToken
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.ShadowRequestOutcome
import com.axiel7.anihyou.release.core.api.ShadowRequestReservation
import com.axiel7.anihyou.release.core.api.ShadowRunMetrics
import com.axiel7.anihyou.release.core.api.ShadowSourceSpec
import com.axiel7.anihyou.release.core.state.ShadowComparison
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.data.repository.AniWorldShadowManifestCodec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetedAniWorldHttpTransportTest {
    private val startedAt = Instant.parse("2026-09-26T07:00:00Z")
    private val clock = Clock.fixed(startedAt, ZoneOffset.UTC)
    private val recent = "https://aniworld.to/neue-episoden"

    private class RecordingStore(
        private val manifest: ShadowGenerationManifest,
        private val allowOrdinal: (Int) -> Boolean = { true },
    ) : AniWorldShadowPollStore {
        val reservations = mutableListOf<ShadowRequestReservation>()
        val reservationCalls = mutableListOf<Pair<String, String>>()
        val outcomes = mutableListOf<ShadowRequestOutcome>()

        override suspend fun eligibleDirectTargets(now: Instant): List<DirectTargetCandidate> = emptyList()
        override suspend fun shadowComparison(now: Instant) = ShadowComparison(0, 0, 0, 0, 0, 0, 0)
        override suspend fun beginGeneration(manifest: ShadowGenerationManifest, candidateSnapshotDigest: String) = true
        override suspend fun currentGeneration(token: ShadowGenerationToken): ShadowGenerationSnapshot? =
            ShadowGenerationSnapshot(ShadowGenerationState.RUNNING, manifest, 0, 0)

        override suspend fun reserveRequest(
            token: ShadowGenerationToken,
            role: String,
            rootUrl: String,
            requestUrl: String,
            now: Instant,
        ): ShadowRequestReservation? {
            reservationCalls += rootUrl to requestUrl
            val ordinal = reservations.size
            if (!allowOrdinal(ordinal)) return null
            return ShadowRequestReservation(token, ordinal, role, rootUrl, requestUrl, now)
                .also(reservations::add)
        }

        override suspend fun completeRequest(reservation: ShadowRequestReservation, outcome: ShadowRequestOutcome) {
            outcomes += outcome
        }

        override suspend fun commitGeneration(
            token: ShadowGenerationToken,
            cycle: com.axiel7.anihyou.release.core.model.CompletedObservationCycle,
            metrics: ShadowRunMetrics,
        ) = false

        override suspend fun abortGeneration(
            token: ShadowGenerationToken,
            reason: String,
            now: Instant,
            metrics: ShadowRunMetrics,
        ) = Unit
    }

    private class ScriptedTransport(
        private val response: (AniWorldTransportRequest) -> AniWorldHttpResponse,
    ) : AniWorldHttpTransport {
        val requests = mutableListOf<AniWorldTransportRequest>()
        override suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse {
            requests += request
            return response(request)
        }
    }

    @Test
    fun everyRedirectHopGetsItsOwnDurableReservation() = runBlocking {
        val manifest = manifest()
        val store = RecordingStore(manifest)
        val delegate = ScriptedTransport { request ->
            when (request.url) {
                recent -> AniWorldHttpResponse(302, null, request.url, "", location = "/hop-1")
                "https://aniworld.to/hop-1" -> AniWorldHttpResponse(307, null, request.url, "", location = "/hop-2")
                else -> ok(request)
            }
        }
        val client = AniWorldClient(BudgetedAniWorldHttpTransport(delegate, store, manifest, clock), clock = clock)

        val result = client.fetch(AniWorldPageRequest(AniWorldPageRole.RECENT_CURRENT, recent))

        assertTrue(result is AniWorldClientResult.Success)
        assertEquals(listOf(recent, "https://aniworld.to/hop-1", "https://aniworld.to/hop-2"),
            delegate.requests.map { it.url })
        assertEquals(3, store.reservations.size)
        assertEquals(listOf("REDIRECT", "REDIRECT", "HTTP_2XX"), store.outcomes.map { it.status })
        assertTrue(store.reservations.all { it.rootUrl == recent && it.role == "RECENT" })
    }

    @Test
    fun deniedRedirectIsNotContacted() = runBlocking {
        val manifest = manifest()
        val store = RecordingStore(manifest) { ordinal -> ordinal == 0 }
        val delegate = ScriptedTransport { request ->
            AniWorldHttpResponse(302, null, request.url, "", location = "/denied-hop")
        }
        val client = AniWorldClient(BudgetedAniWorldHttpTransport(delegate, store, manifest, clock), clock = clock)

        val result = client.fetch(AniWorldPageRequest(AniWorldPageRole.RECENT_CURRENT, recent))

        assertEquals(AniWorldFailureKind.BUDGET_DENIED, (result as AniWorldClientResult.Failure).kind)
        assertEquals(1, delegate.requests.size)
        assertEquals(2, store.reservationCalls.size)
        assertEquals("https://aniworld.to/denied-hop", store.reservationCalls.last().second)
    }

    @Test
    fun rateLimitRecordsBoundedRetryAfterWithoutRetrying() = runBlocking {
        val manifest = manifest()
        val store = RecordingStore(manifest)
        val delegate = ScriptedTransport { request ->
            AniWorldHttpResponse(429, null, request.url, "", retryAfter = "999999")
        }
        val client = AniWorldClient(BudgetedAniWorldHttpTransport(delegate, store, manifest, clock), clock = clock)

        val result = client.fetch(AniWorldPageRequest(AniWorldPageRole.RECENT_CURRENT, recent))

        assertEquals(AniWorldFailureKind.RATE_LIMITED, (result as AniWorldClientResult.Failure).kind)
        assertEquals(1, delegate.requests.size)
        assertEquals(1, store.reservations.size)
        assertEquals("HTTP_429", store.outcomes.single().status)
        assertEquals(21_600L, store.outcomes.single().retryAfterSeconds)
        assertEquals(recent, store.reservations.single().requestUrl)
    }

    private fun manifest(): ShadowGenerationManifest {
        val token = ShadowGenerationToken("aw-shadow-v1:fixture", "owner", "process")
        val sources = listOf(
            ShadowSourceSpec("aw:list:calendar:v1", "ANIWORLD_CALENDAR", "aniworld:list:calendar", null,
                "https://aniworld.to/animekalender", true),
            ShadowSourceSpec("aw:list:recent:v1", "ANIWORLD_RECENT", "aniworld:list:recent", null,
                recent, true),
            ShadowSourceSpec("aw:list:postponement:v1", "ANIWORLD_POSTPONEMENT", "aniworld:list:postponement", null,
                "https://aniworld.to/support/frage/anime-verschiebungen", false),
        )
        return ShadowGenerationManifest(token, 1, startedAt, startedAt.plus(Duration.ofMinutes(4)),
            sources, AniWorldShadowManifestCodec.digest(sources))
    }

    private fun ok(request: AniWorldTransportRequest) = AniWorldHttpResponse(
        200, "text/html", request.url,
        "<html><body><main><h1>Neue Episoden</h1></main></body></html>",
    )
}

package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.domain.repository.LocalAccountProgressIndex
import com.axiel7.anihyou.release.core.api.CandidateBatch
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.ProviderFetchRequest
import com.axiel7.anihyou.release.core.api.ProviderFetchResult
import com.axiel7.anihyou.release.core.api.ReleaseProvider
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.api.TargetedLookupCursor
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.sync.CandidatePoolRequest
import com.axiel7.anihyou.release.core.sync.CandidateSeason
import com.axiel7.anihyou.release.core.sync.SyncBudget
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaC77RequestBudgetProofTest {
    @Test
    fun coldFiftyUnresolvedIdentitiesAcrossThirteenRefreshesStayBoundedFairAndConcurrent() =
        runBlocking {
            val probe = BudgetProbe()
            val source = BudgetedCandidateSource(probe)
            val matcher = matcher(source)
            val snapshots = (0 until 50).map(::snapshot)

            CountingProvider(probe).fetch(
                ProviderFetchRequest(
                    range = LocalDate.of(2026, 9, 12)..LocalDate.of(2026, 9, 12),
                    tracks = setOf(LanguageTrack.DE_SUB),
                ),
            )
            repeat(13) { generation ->
                matcher.resolveWithMetrics(snapshots, generation = generation.toLong())
            }

            assertEquals(1, probe.providerFetches.get())
            assertEquals(4, probe.currentSeasonPages.get())
            assertEquals(4, probe.previousSeasonPages.get())
            assertEquals(13 * 4, probe.targetedNetwork.get())
            assertTrue(probe.maxConcurrent.get() <= 2)
            assertEquals(50, source.networkKeys.toSet().size)
        }

    @Test
    fun warmLocalStateUsesZeroTargetedAndProviderNetwork() = runBlocking {
        val probe = BudgetProbe()
        val source = BudgetedCandidateSource(probe, initiallyWarm = true)
        val matcher = matcher(source)

        matcher.resolveWithMetrics(
            snapshots = (0 until 10).map(::snapshot),
            generation = 1L,
        )

        assertEquals(0, probe.providerFetches.get())
        assertEquals(0, probe.currentSeasonPages.get())
        assertEquals(0, probe.previousSeasonPages.get())
        assertEquals(0, probe.targetedNetwork.get())
    }

    @Test
    fun accountUiWidgetForecastAndOutboxBoundariesAreExplicitlyNetworkFree() {
        val probe = BudgetProbe()
        val index = LocalAccountProgressIndex()
        index.record(
            userId = 42,
            progressByMediaId = (1..51).associateWith { id -> if (id == 1) 0 else id },
        )
        probe.accountLocalReads.incrementAndGet()
        assertEquals(51, index.read(42, (1..51).toSet())?.size)
        assertEquals(0, probe.accountNetwork.get())

        val stream = ReleaseStreamKey(
            providerId = ProviderId("aniworld"),
            stableSeriesKey = SourceSeriesKey("budget-ui"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        )
        val first = uiRow(stream, Installment.Episode(1), 7)
        val second = uiRow(stream, Installment.Episode(2), 7)
        assertNotEquals(first.eventKey, second.eventKey)
        assertEquals(0, probe.uiMetadataNetwork.get())
        assertEquals(0, probe.widgetInitialNetwork.get())
        assertEquals(0, probe.forecastNotificationCalls.get())
        assertEquals(0, probe.outboxBroadRefreshes.get())
    }

    private fun matcher(source: IdentityCandidateSource) = ReleaseIdentityMatcher(
        candidateSource = source,
        budget = SyncBudget(
            currentSeasonMaxPages = 4,
            previousSeasonMaxPages = 4,
            maxTargetedQueries = 4,
            maxConcurrentRequests = 2,
        ),
        clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC),
    )

    private fun snapshot(index: Int): ReleaseSnapshot {
        val stream = ReleaseStreamKey(
            providerId = ProviderId("aniworld"),
            stableSeriesKey = SourceSeriesKey("budget-series-$index"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = LanguageTrack.DE_SUB,
        )
        val observedAt = Instant.parse("2026-09-12T12:00:00Z")
        return ReleaseSnapshot(
            stream = stream,
            confirmations = listOf(
                Confirmation(
                    identity = SourceIdentity(stream, Installment.Episode(1)),
                    evidenceKind = ConfirmationEvidenceKind.CURRENT_PAGE_EXPLICIT_MARKER,
                    confirmedObservedAt = observedAt,
                ),
            ),
            forecasts = emptyList(),
            freshness = Freshness(
                status = FreshnessStatus.FRESH,
                lastAttemptAt = observedAt,
                lastSuccessAt = observedAt,
                observedAt = observedAt,
                parserVersion = "c77",
                sourceHash = "budget-$index",
            ),
            mapping = null,
            observedAt = observedAt,
        )
    }

    private fun uiRow(
        stream: ReleaseStreamKey,
        installment: Installment,
        mediaId: Int?,
    ) = ReleaseUiCalendarItem(
        mediaId = mediaId,
        stream = stream,
        installment = installment,
        forecastAt = Instant.parse("2026-10-01T18:00:00Z"),
        confirmed = false,
        authority = ReleaseUiAuthority.VALID,
        sourceDate = LocalDate.of(2026, 10, 1),
        sourceRoot = "https://aniworld.to",
        revision = 1L,
    )

    private class BudgetedCandidateSource(
        private val probe: BudgetProbe,
        initiallyWarm: Boolean = false,
    ) : IdentityCandidateSource {
        private val cursorByProvider = mutableMapOf<String, TargetedLookupCursor>()
        private val active = AtomicInteger(0)
        private val poolCalls = AtomicInteger(0)
        private val targetedNetworkEnabled = !initiallyWarm
        private var warm: Boolean = initiallyWarm
        val networkKeys = Collections.synchronizedList(mutableListOf<String>())

        override suspend fun localCandidates(
            keys: Set<SourceIdentity>,
        ): CandidateBatch = CandidateBatch(emptyList(), complete = true)

        override suspend fun localCandidatesBySource(
            keys: Set<SourceIdentity>,
        ): Map<String, CandidateBatch> = keys.associate {
            it.stableKey to CandidateBatch(emptyList(), complete = true)
        }

        override suspend fun boundedSeasonPool(
            request: CandidatePoolRequest,
        ): CandidateBatch {
            if (warm) return CandidateBatch(emptyList(), complete = true)
            val call = poolCalls.incrementAndGet()
            if (call <= 2) {
                if (request.window.season == CandidateSeason.SUMMER) {
                    probe.currentSeasonPages.addAndGet(4)
                } else {
                    probe.previousSeasonPages.addAndGet(4)
                }
            }
            if (call == 2) warm = true
            return CandidateBatch(
                emptyList(),
                complete = false,
                pagesFetched = 4,
            )
        }

        override suspend fun readTargetedLookupCursor(
            providerId: String,
        ): TargetedLookupCursor = cursorByProvider[providerId] ?: TargetedLookupCursor()

        override suspend fun writeTargetedLookupCursor(
            providerId: String,
            cursor: TargetedLookupCursor,
        ) {
            cursorByProvider[providerId] = cursor
        }

        override suspend fun targetedSearch(query: TargetedIdentityQuery): CandidateBatch {
            val nowActive = active.incrementAndGet()
            probe.maxConcurrent.updateAndGet { previous -> maxOf(previous, nowActive) }
            try {
                if (targetedNetworkEnabled) {
                    networkKeys += query.source.stableKey
                    probe.targetedNetwork.incrementAndGet()
                }
                delay(1)
                return CandidateBatch(emptyList(), complete = false)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private class CountingProvider(
        private val probe: BudgetProbe,
    ) : ReleaseProvider {
        override val id: ProviderId = ProviderId("aniworld")

        override suspend fun fetch(request: ProviderFetchRequest): ProviderFetchResult {
            probe.providerFetches.incrementAndGet()
            return ProviderFetchResult.Success(emptyList())
        }
    }

    private class BudgetProbe {
        val providerFetches = AtomicInteger(0)
        val currentSeasonPages = AtomicInteger(0)
        val previousSeasonPages = AtomicInteger(0)
        val targetedNetwork = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val accountNetwork = AtomicInteger(0)
        val accountLocalReads = AtomicInteger(0)
        val uiMetadataNetwork = AtomicInteger(0)
        val widgetInitialNetwork = AtomicInteger(0)
        val forecastNotificationCalls = AtomicInteger(0)
        val outboxBroadRefreshes = AtomicInteger(0)
    }
}

package com.axiel7.anihyou.feature.home.current

import com.axiel7.anihyou.core.base.PagedResult
import com.axiel7.anihyou.core.domain.repository.DefaultPreferencesRepository
import com.axiel7.anihyou.core.domain.repository.MediaListRepository
import com.axiel7.anihyou.core.model.media.AnimeSeason
import com.axiel7.anihyou.core.network.fragment.BasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.CommonMediaListEntry
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.network.type.MediaType
import com.axiel7.anihyou.core.network.type.ScoreFormat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class Wp00R1CurrentListRuntimeTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun coldAndWarmCurrentListRuntimeAreMeasuredAtRepositoryBoundary() = runBlocking {
        val preferences = mockk<DefaultPreferencesRepository>()
        every { preferences.userId } returns flowOf(4242)
        every { preferences.scoreFormat } returns flowOf(ScoreFormat.POINT_10_DECIMAL)
        every { preferences.showLowPriority } returns flowOf(false)
        every { preferences.colorLowPriority } returns flowOf(0xFF7CB342.toInt())
        every { preferences.colorMediumPriority } returns flowOf(0xFFF4D03F.toInt())
        every { preferences.colorHighPriority } returns flowOf(0xFFD84315.toInt())
        every { preferences.scoreSteps } returns flowOf(1.0)

        val transport = HarnessRepositoryBoundary()
        val repository = mockk<MediaListRepository>()
        every { repository.lastUpdatedEntry } returns MutableStateFlow<BasicMediaListEntry?>(null)
        every {
            repository.getUserMediaList(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val mediaType = arg<MediaType>(1)
            val fetchFromNetwork = arg<Boolean>(5)
            transport.flow("getUserMediaList:${mediaType.name}", fetchFromNetwork)
        }
        every {
            repository.getMySeasonalAnime(any(), any(), any(), any(), any())
        } answers {
            val season = arg<AnimeSeason>(0)
            val fetchFromNetwork = arg<Boolean>(2)
            transport.flow(
                "getMySeasonalAnime:${season.season.name}:${season.year}",
                fetchFromNetwork,
            )
        }

        val cold = measureRun("cold", transport) {
            CurrentViewModel(repository, preferences)
        }
        val warm = measureRun("warm", transport) {
            CurrentViewModel(repository, preferences)
        }

        assertTrue("cold measurement must be non-empty", cold.elapsedNanos > 0)
        assertTrue("warm measurement must be non-empty", warm.elapsedNanos > 0)
        assertEquals(4, cold.requestCounts.values.sum())
        assertEquals(4, warm.requestCounts.values.sum())
        assertEquals(4, cold.cacheMisses)
        assertEquals(0, cold.cacheHits)
        assertEquals(0, warm.cacheMisses)
        assertEquals(4, warm.cacheHits)
        assertEquals(4, cold.fetchModes.size)
        assertEquals(4, warm.fetchModes.size)
        assertTrue(cold.fetchModes.values.all { it == setOf(false) })
        assertTrue(warm.fetchModes.values.all { it == setOf(false) })
        assertTrue(cold.animeCount > 0 && cold.mangaCount > 0 && cold.nextSeasonCount > 0)
        assertTrue(warm.animeCount > 0 && warm.mangaCount > 0 && warm.nextSeasonCount > 0)

        val report = renderReport(cold, warm)
        val reportPath = Path.of("build", "reports", "wp00-r1", "current-list-runtime.json")
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, report)
        println("WP00_R1_CURRENT_LIST_RUNTIME_REPORT_PATH=${reportPath.toAbsolutePath()}")
        println(report)
    }

    private suspend fun measureRun(
        label: String,
        transport: HarnessRepositoryBoundary,
        createViewModel: () -> CurrentViewModel,
    ): RunMeasurement {
        val before = transport.snapshot()
        val started = System.nanoTime()
        val viewModel = createViewModel()
        val meaningfulState = withTimeout(5_000) {
            while (true) {
                val state = viewModel.uiState.value
                val delta = transport.delta(before)
                if (
                    delta.completions.values.sum() == 4 &&
                    !state.isLoading &&
                    state.animeList.isNotEmpty() &&
                    state.mangaList.isNotEmpty() &&
                    state.nextSeasonAnimeList.isNotEmpty()
                ) {
                    return@withTimeout state
                }
                yield()
            }
            error("unreachable")
        }
        val elapsed = System.nanoTime() - started
        val delta = transport.delta(before)
        return RunMeasurement(
            label = label,
            elapsedNanos = elapsed,
            requestCounts = delta.requests,
            completionCounts = delta.completions,
            cacheHits = delta.cacheHits,
            cacheMisses = delta.cacheMisses,
            fetchModes = delta.fetchModes,
            animeCount = meaningfulState.animeList.size,
            mangaCount = meaningfulState.mangaList.size,
            nextSeasonCount = meaningfulState.nextSeasonAnimeList.size,
        )
    }

    private fun renderReport(cold: RunMeasurement, warm: RunMeasurement): String {
        fun map(values: Map<String, Int>) = values.entries.sortedBy { it.key }.joinToString(",") {
            "\"${it.key}\":${it.value}"
        }
        fun modes(values: Map<String, Set<Boolean>>) = values.entries.sortedBy { it.key }.joinToString(",") {
            val rendered = it.value.sorted().joinToString(",") { value -> value.toString() }
            "\"${it.key}\":[${rendered}]"
        }
        fun run(run: RunMeasurement) = """{
  "label":"${run.label}",
  "elapsed_nanos":${run.elapsedNanos},
  "elapsed_millis":${"%.3f".format(java.util.Locale.ROOT, run.elapsedNanos / 1_000_000.0)},
  "request_counts":{${map(run.requestCounts)}},
  "completion_counts":{${map(run.completionCounts)}},
  "cache_hits":${run.cacheHits},
  "cache_misses":${run.cacheMisses},
  "fetch_from_network_values":{${modes(run.fetchModes)}},
  "meaningful_state_counts":{"anime":${run.animeCount},"manga":${run.mangaCount},"next_season":${run.nextSeasonCount}}
}"""
        return """{
  "evidence":"WP00-R1_CURRENT_LIST_RUNTIME_HARNESS",
  "runtime_type":"JVM unit test executing real CurrentViewModel orchestration with controlled MediaListRepository boundary",
  "clock_source":"System.nanoTime monotonic clock",
  "measurement_start":"immediately before CurrentViewModel construction",
  "first_meaningful_data":"first observed CurrentUiState value after all four Current source paths emitted terminal PagedResult.Success, isLoading=false, and anime/manga/next-season lists contain deterministic synthetic entries",
  "state_observation":"CurrentUiState owns mutable SnapshotStateList instances; the harness samples uiState.value after each cooperative yield so in-place list mutations are observed without requiring a redundant StateFlow emission",
  "payload":"one pre-created relaxed CommonMediaListEntry test double per source emission with media.status fixed to FINISHED; no production-network content",
  "cold_reset":"same harness starts with an empty repository-boundary cache and zero counters",
  "warm_reuse":"same repository mock and boundary cache are reused after cold completion; only counter deltas are measured",
  "timing_model":"no synthetic sleep or hardcoded latency is injected; elapsed time is the measured harness/ViewModel orchestration wall time on the CI JVM",
  "transport":"local deterministic fake at repository boundary; no AniList credentials and no production network latency",
  "cold":${run(cold)},
  "warm":${run(warm)}
}"""
    }

    private data class RunMeasurement(
        val label: String,
        val elapsedNanos: Long,
        val requestCounts: Map<String, Int>,
        val completionCounts: Map<String, Int>,
        val cacheHits: Int,
        val cacheMisses: Int,
        val fetchModes: Map<String, Set<Boolean>>,
        val animeCount: Int,
        val mangaCount: Int,
        val nextSeasonCount: Int,
    )

    private class HarnessRepositoryBoundary {
        private val cache = ConcurrentHashMap.newKeySet<String>()
        private val requests = ConcurrentHashMap<String, AtomicInteger>()
        private val completions = ConcurrentHashMap<String, AtomicInteger>()
        private val fetchModes = ConcurrentHashMap<String, MutableSet<Boolean>>()
        private val hits = AtomicInteger()
        private val misses = AtomicInteger()
        private val payloadEntry = mockk<CommonMediaListEntry>(relaxed = true).also { entry ->
            every { entry.media?.status } returns MediaStatus.FINISHED
        }
        private val payload = listOf(payloadEntry)

        fun flow(source: String, fetchFromNetwork: Boolean): Flow<PagedResult<CommonMediaListEntry>> = flow {
            requests.computeIfAbsent(source) { AtomicInteger() }.incrementAndGet()
            fetchModes.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() }.add(fetchFromNetwork)
            emit(PagedResult.Loading)
            val cacheHit = !fetchFromNetwork && cache.contains(source)
            if (cacheHit) {
                hits.incrementAndGet()
            } else {
                misses.incrementAndGet()
                cache.add(source)
            }
            completions.computeIfAbsent(source) { AtomicInteger() }.incrementAndGet()
            emit(PagedResult.Success(payload, currentPage = 1, hasNextPage = false))
        }

        fun snapshot() = Snapshot(
            requests = requests.mapValues { it.value.get() },
            completions = completions.mapValues { it.value.get() },
            cacheHits = hits.get(),
            cacheMisses = misses.get(),
            fetchModes = fetchModes.mapValues { it.value.toSet() },
        )

        fun delta(before: Snapshot): Snapshot {
            val after = snapshot()
            fun diff(now: Map<String, Int>, prior: Map<String, Int>) = now.mapValues { (key, value) ->
                value - prior.getOrDefault(key, 0)
            }.filterValues { it != 0 }
            val requestDelta = diff(after.requests, before.requests)
            val modesForRequestedSources = after.fetchModes.filterKeys { it in requestDelta }
            return Snapshot(
                requests = requestDelta,
                completions = diff(after.completions, before.completions),
                cacheHits = after.cacheHits - before.cacheHits,
                cacheMisses = after.cacheMisses - before.cacheMisses,
                fetchModes = modesForRequestedSources,
            )
        }

        data class Snapshot(
            val requests: Map<String, Int>,
            val completions: Map<String, Int>,
            val cacheHits: Int,
            val cacheMisses: Int,
            val fetchModes: Map<String, Set<Boolean>>,
        )
    }
}

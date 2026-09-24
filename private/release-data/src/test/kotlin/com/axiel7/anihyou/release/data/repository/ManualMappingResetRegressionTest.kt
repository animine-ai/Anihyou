package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.ProviderFetchRequest
import com.axiel7.anihyou.release.core.api.ProviderFetchResult
import com.axiel7.anihyou.release.core.api.RefreshOutcome
import com.axiel7.anihyou.release.core.api.RefreshReason
import com.axiel7.anihyou.release.core.api.ReleaseAccountContext
import com.axiel7.anihyou.release.core.api.ReleaseProvider
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.preferences.ReleasePreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualMappingResetRegressionTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val stream = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey("manual-reset"),
        releaseKind = ReleaseKind.EPISODE,
        sourceSeason = 2026,
        languageTrack = LanguageTrack.DE_SUB,
    )
    private lateinit var database: ReleaseDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun resetToAutomaticDoesNotResurrectManualMappingFromHistoricalSnapshot() = runBlocking {
        val manual = mapping(mediaId = 11, origin = MappingOrigin.MANUAL)
        val automatic = mapping(mediaId = 22, origin = MappingOrigin.AUTO)
        database.releaseDao().upsertProviderSnapshots(
            listOf(snapshot(manual).toEntity()),
        )
        val mappingRepository = RoomReleaseMappingRepository(database, clock)
        mappingRepository.setManualMapping(stream.stableKey, 11, "manual-test")
        assertTrue(mappingRepository.resetToAutomatic(stream.stableKey))

        val provider = object : ReleaseProvider {
            override val id = ProviderId("aniworld")
            override suspend fun fetch(request: ProviderFetchRequest): ProviderFetchResult =
                ProviderFetchResult.Success(listOf(snapshot(automatic)))
        }
        val coordinator = ReleaseSyncCoordinator(
            provider = provider,
            database = database,
            syncStore = RoomReleaseSyncStore(database, clock = clock),
            preferences = flowOf(
                ReleasePreferences(
                    selectedProvider = ProviderId("aniworld"),
                    preferredTrack = LanguageTrack.DE_SUB,
                    notificationsEnabled = true,
                ),
            ),
            accountContextProvider = {
                ReleaseAccountContext(accountId = 7L, progressByMediaId = mapOf(22 to 0))
            },
            clock = clock,
        )

        assertTrue(coordinator.refresh(RefreshReason.MANUAL) is RefreshOutcome.Applied)
        val persistedSnapshot = database.releaseDao().getProviderSnapshot(stream.stableKey)
            ?.toDomainOrNull()
            ?: error("snapshot missing")
        assertEquals(22, persistedSnapshot.mapping?.mediaId)
        assertEquals(MappingOrigin.AUTO, persistedSnapshot.mapping?.origin)
        val storedMapping = database.releaseDao().getMapping(stream.stableKey)
            ?.toDomainOrNull()
            ?: error("mapping missing")
        assertEquals(22, storedMapping.mediaId)
        assertEquals(MappingOrigin.AUTO, storedMapping.origin)
    }

    private fun snapshot(mapping: ReleaseMapping) = ReleaseSnapshot(
        stream = stream,
        confirmations = emptyList(),
        forecasts = emptyList(),
        freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = now,
            lastSuccessAt = now,
            observedAt = now,
            parserVersion = "sol-test",
            sourceHash = "manual-reset",
        ),
        mapping = mapping,
        sourcePresent = true,
        sourceRoot = "https://aniworld.to/anime/manual-reset",
        observedAt = now,
    )

    private fun mapping(
        mediaId: Int,
        origin: MappingOrigin,
    ) = ReleaseMapping(
        mediaId = mediaId,
        confidence = MappingConfidence.HIGH,
        score = 0.99,
        runnerUpMargin = 0.2,
        evidence = "test",
        matcherVersion = "test",
        origin = origin,
    )
}

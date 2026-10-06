package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.Forecast
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
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class ForecastSourcePresenceLifecycleTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val stream = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey("removed-stream"),
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
    fun retainedLastGoodForecastDoesNotKeepWorkAliveAfterSourceDisappears() = runBlocking {
        val store = RoomReleaseSyncStore(database)
        val forecast = Forecast(
            identity = SourceIdentity(stream, Installment.Episode(10)),
            forecastAt = now.plusSeconds(5L * 86_400L),
            sourceDate = LocalDate.of(2026, 9, 17),
            sourceTime = "20:00",
            sourceZone = ZoneId.of("Europe/Berlin"),
            approximate = true,
            observedAt = now,
        )
        val active = snapshot(forecast, sourcePresent = true)
        assertTrue(
            store.persistSnapshotsIfCurrent(
                generationKey = "presence",
                expectedGeneration = 0L,
                snapshots = listOf(active),
                observedAt = now,
            ),
        )
        assertEquals(1, database.releaseDao().getForecastRecheckWork(stream.stableKey).size)

        val removed = snapshot(forecast, sourcePresent = false).copy(
            freshness = active.freshness.copy(
                status = FreshnessStatus.STALE,
                observedAt = now.plusSeconds(60),
            ),
            observedAt = now.plusSeconds(60),
        )
        assertTrue(
            store.persistSnapshotsIfCurrent(
                generationKey = "presence",
                expectedGeneration = 0L,
                snapshots = listOf(removed),
                observedAt = removed.observedAt,
            ),
        )
        assertTrue(database.releaseDao().getForecastRecheckWork(stream.stableKey).isEmpty())
    }

    private fun snapshot(
        forecast: Forecast,
        sourcePresent: Boolean,
    ) = ReleaseSnapshot(
        stream = stream,
        confirmations = emptyList(),
        forecasts = listOf(forecast),
        freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = now,
            lastSuccessAt = now,
            observedAt = now,
            parserVersion = "sol-test",
            sourceHash = "source-presence",
        ),
        mapping = null,
        sourcePresent = sourcePresent,
        sourceRoot = "https://aniworld.to/anime/removed-stream",
        observedAt = now,
    )
}

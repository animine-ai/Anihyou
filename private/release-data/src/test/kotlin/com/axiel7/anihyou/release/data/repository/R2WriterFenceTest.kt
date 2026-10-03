package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Interleavings of an old automatic writer of the older release lane with a deliberate reset: the lane's provider
 * snapshots may only bring a mapping back if they began observing after the reset, and never with a claimed future time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class R2WriterFenceTest {
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val t0 = Instant.parse("2026-10-01T10:00:00Z")
    private val stream = ReleaseStreamKey(ProviderId("aniworld"), SourceSeriesKey("fence-series"),
        ReleaseKind.EPISODE, 1, LanguageTrack.DE_SUB)
    private val clock = TestClock(t0)
    private lateinit var database: ReleaseDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ReleaseDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { database.close() }

    private fun snapshot(at: Instant) = ReleaseSnapshot(
        stream = stream, confirmations = emptyList(), forecasts = emptyList(),
        freshness = Freshness(FreshnessStatus.FRESH, at, at, at, "fence-test", "fence"),
        mapping = ReleaseMapping(42, MappingConfidence.HIGH, 0.99, 0.2, "fence-test", "fence-test", MappingOrigin.AUTO),
        sourcePresent = true, sourceRoot = "https://aniworld.to/anime/fence-series", observedAt = at,
    )

    @Test fun anOldWriterCannotRestoreAResetMappingButALaterOneCan() = runBlocking {
        val projections = RoomReleaseProjectionRepository(database, clock = clock)
        val key = stream.stableKey
        projections.persistProviderSnapshots(listOf(snapshot(t0)), observedAt = t0)
        assertEquals(42, database.releaseDao().getMapping(key)?.mediaId)

        // The user resets the entry at t0+60 s (what the settings do in one transaction).
        clock.now = t0.plusSeconds(60)
        database.releaseDao().deleteMapping(key)
        MappingWriterFence(database, clock).bump(MappingEntryRef.FENCE_R2, key, clock.now)

        // A job that began observing before the reset finishes afterwards: it must not bring the binding back.
        projections.persistProviderSnapshots(listOf(snapshot(t0.plusSeconds(30))), observedAt = t0.plusSeconds(30))
        assertNull(database.releaseDao().getMapping(key))
        // Neither may a writer that claims to have observed in the future.
        projections.persistProviderSnapshots(listOf(snapshot(t0.plusSeconds(90_000))), observedAt = t0.plusSeconds(90_000))
        assertNull(database.releaseDao().getMapping(key))

        // A job that began after the reset is a new decision and may write.
        clock.now = t0.plusSeconds(120)
        projections.persistProviderSnapshots(listOf(snapshot(t0.plusSeconds(100))), observedAt = t0.plusSeconds(100))
        assertEquals(42, database.releaseDao().getMapping(key)?.mediaId)
    }

    @Test fun aManualBindingIsNeverOverwrittenWhateverTheFence() = runBlocking {
        val projections = RoomReleaseProjectionRepository(database, clock = clock)
        val key = stream.stableKey
        database.releaseDao().upsertMappings(listOf(com.axiel7.anihyou.release.data.db.ReleaseMappingEntity(key, 7,
            "EXACT", 1.0, null, "manual", "manual-v1", MappingOrigin.MANUAL.name, t0.toString())))
        projections.persistProviderSnapshots(listOf(snapshot(t0.plusSeconds(10))), observedAt = t0.plusSeconds(10))
        assertEquals(7, database.releaseDao().getMapping(key)?.mediaId)
    }
}

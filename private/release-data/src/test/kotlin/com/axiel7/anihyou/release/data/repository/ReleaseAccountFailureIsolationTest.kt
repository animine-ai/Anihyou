package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.ProviderFetchRequest
import com.axiel7.anihyou.release.core.api.ProviderFetchResult
import com.axiel7.anihyou.release.core.api.RefreshOutcome
import com.axiel7.anihyou.release.core.api.RefreshReason
import com.axiel7.anihyou.release.core.api.ReleaseProvider
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
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
class ReleaseAccountFailureIsolationTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val stream = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey("account-cache-failure"),
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
    fun accountCacheExceptionFailsClosedForAccountStateButKeepsProviderEvidence() = runBlocking {
        val snapshot = ReleaseSnapshot(
            stream = stream,
            confirmations = listOf(
                Confirmation(
                    identity = SourceIdentity(stream, Installment.Episode(9)),
                    evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
                    confirmedObservedAt = now,
                ),
            ),
            forecasts = emptyList(),
            freshness = Freshness(
                status = FreshnessStatus.FRESH,
                lastAttemptAt = now,
                lastSuccessAt = now,
                observedAt = now,
                parserVersion = "sol-test",
                sourceHash = "account-cache-failure",
            ),
            mapping = ReleaseMapping(
                mediaId = 42,
                confidence = MappingConfidence.HIGH,
                score = 0.99,
                runnerUpMargin = 0.2,
                evidence = "sol-test",
                matcherVersion = "sol-test",
                origin = MappingOrigin.AUTO,
            ),
            sourcePresent = true,
            sourceRoot = "https://aniworld.to/anime/account-cache-failure",
            observedAt = now,
        )
        val provider = object : ReleaseProvider {
            override val id = ProviderId("aniworld")
            override suspend fun fetch(request: ProviderFetchRequest): ProviderFetchResult =
                ProviderFetchResult.Success(listOf(snapshot))
        }
        val coordinator = ReleaseSyncCoordinator(
            provider = provider,
            database = database,
            syncStore = RoomReleaseSyncStore(database = database, clock = clock),
            preferences = flowOf(
                ReleasePreferences(
                    selectedProvider = ProviderId("aniworld"),
                    preferredTrack = LanguageTrack.DE_SUB,
                    notificationsEnabled = true,
                ),
            ),
            accountContextProvider = { error("local account cache unavailable") },
            clock = clock,
        )

        val outcome = coordinator.refresh(RefreshReason.WORKER)
        assertTrue(outcome is RefreshOutcome.Skipped)
        val persisted = database.releaseDao().getProviderSnapshot(stream.stableKey)
            ?.toDomainOrNull()
            ?: error("provider snapshot missing")
        assertEquals(9, (persisted.confirmations.single().identity.installment as Installment.Episode).number)
        assertEquals(0, database.releaseDao().mediaProjectionCount())
        assertEquals(0, database.releaseDao().notificationOutboxCount())
    }
}

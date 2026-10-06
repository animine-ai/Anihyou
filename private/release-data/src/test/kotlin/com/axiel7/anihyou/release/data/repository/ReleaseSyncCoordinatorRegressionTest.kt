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
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Forecast
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
import java.time.LocalDate
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
class ReleaseSyncCoordinatorRegressionTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val stream = ReleaseStreamKey(
        providerId = ProviderId("aniworld"),
        stableSeriesKey = SourceSeriesKey("sol-history-regression"),
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
    fun reducedConfirmationHistorySurvivesLaterProviderSnapshotsThatNoLongerRepeatIt() =
        runBlocking {
            val provider = QueueProvider(
                mutableListOf(
                    snapshot(
                        confirmations = listOf(confirmation(9)),
                        forecasts = listOf(forecast(10)),
                    ),
                    snapshot(
                        confirmations = emptyList(),
                        forecasts = listOf(forecast(10, observedAt = now.plusSeconds(60))),
                    ),
                ),
            )
            val coordinator = coordinator(
                provider = provider,
                accountContext = ReleaseAccountContext(
                    accountId = 7L,
                    progressByMediaId = mapOf(MEDIA_ID to 7),
                ),
            )

            assertTrue(coordinator.refresh(RefreshReason.MANUAL) is RefreshOutcome.Applied)
            assertTrue(coordinator.refresh(RefreshReason.MANUAL) is RefreshOutcome.Applied)

            val persisted = database.releaseDao()
                .getProviderSnapshot(stream.stableKey)
                ?.toDomainOrNull()
                ?: error("provider snapshot missing")
            assertEquals(listOf(9), persisted.confirmations.mapNotNull {
                (it.identity.installment as? Installment.Episode)?.number
            })

            val projection = database.releaseDao()
                .getValidMediaProjectionForAccount(7L, MEDIA_ID)
                ?.toDomainOrNull()
                ?: error("projection missing")
            assertEquals(9, projection.confirmedThroughEpisode)
            assertEquals(2, projection.pendingCount)
            assertEquals(Installment.Episode(10), projection.nextForecast?.identity?.installment)
        }

    @Test
    fun unknownAccountProgressStillPersistsProviderEvidenceButCreatesNoAccountProjection() =
        runBlocking {
            val provider = QueueProvider(
                mutableListOf(
                    snapshot(
                        confirmations = listOf(confirmation(9)),
                        forecasts = listOf(forecast(10)),
                    ),
                ),
            )
            val coordinator = coordinator(
                provider = provider,
                accountContext = ReleaseAccountContext(accountId = null),
            )

            val outcome = coordinator.refresh(RefreshReason.WORKER)
            assertTrue(outcome is RefreshOutcome.Skipped)
            assertEquals(
                9,
                database.releaseDao()
                    .getProviderSnapshot(stream.stableKey)
                    ?.toDomainOrNull()
                    ?.confirmations
                    ?.single()
                    ?.identity
                    ?.installment
                    ?.let { (it as Installment.Episode).number },
            )
            assertEquals(0, database.releaseDao().mediaProjectionCount())
            assertEquals(0, database.releaseDao().notificationOutboxCount())
        }

    private fun coordinator(
        provider: ReleaseProvider,
        accountContext: ReleaseAccountContext,
    ) = ReleaseSyncCoordinator(
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
        accountContextProvider = { accountContext },
        clock = clock,
    )

    private fun snapshot(
        confirmations: List<Confirmation>,
        forecasts: List<Forecast>,
    ) = ReleaseSnapshot(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = now,
            lastSuccessAt = now,
            observedAt = now,
            parserVersion = "sol-regression",
            sourceHash = "sol-history",
        ),
        mapping = ReleaseMapping(
            mediaId = MEDIA_ID,
            confidence = MappingConfidence.HIGH,
            score = 0.99,
            runnerUpMargin = 0.2,
            evidence = "sol-regression",
            matcherVersion = "sol-regression",
            origin = MappingOrigin.AUTO,
        ),
        sourcePresent = true,
        sourceRoot = "https://aniworld.to/anime/sol-history-regression",
        observedAt = now,
    )

    private fun confirmation(number: Int) = Confirmation(
        identity = SourceIdentity(stream, Installment.Episode(number)),
        evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
        confirmedObservedAt = now,
    )

    private fun forecast(
        number: Int,
        observedAt: Instant = now,
    ) = Forecast(
        identity = SourceIdentity(stream, Installment.Episode(number)),
        forecastAt = now.plusSeconds(5L * 24L * 60L * 60L),
        sourceDate = LocalDate.of(2026, 9, 17),
        sourceTime = "20:00",
        sourceZone = ZoneId.of("Europe/Berlin"),
        approximate = true,
        observedAt = observedAt,
    )

    private class QueueProvider(
        private val snapshots: MutableList<ReleaseSnapshot>,
    ) : ReleaseProvider {
        override val id = ProviderId("aniworld")

        override suspend fun fetch(request: ProviderFetchRequest): ProviderFetchResult =
            ProviderFetchResult.Success(listOf(snapshots.removeAt(0)))
    }

    private companion object {
        const val MEDIA_ID = 42
    }
}

package com.axiel7.anihyou

import androidx.room.Room
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.ReleaseAuthorityReducer
import com.axiel7.anihyou.release.core.api.ReleaseDecisionRepository
import com.axiel7.anihyou.release.core.api.ReleaseEvidenceRepository
import com.axiel7.anihyou.release.core.api.ReleaseForecastRevisionRepository
import com.axiel7.anihyou.release.core.api.SourceHealthRepository
import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseForecastRecheckScheduler
import com.axiel7.anihyou.release.core.api.ReleaseMappingRepository
import com.axiel7.anihyou.release.core.api.ReleaseNotificationGate
import com.axiel7.anihyou.release.core.api.ReleaseOutboxRepository
import com.axiel7.anihyou.release.core.api.ReleaseOutboxScheduler
import com.axiel7.anihyou.release.core.api.ReleasePreferencesRepository
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseRefreshCoordinator
import com.axiel7.anihyou.release.data.aniworld.AniWorldClient
import com.axiel7.anihyou.release.data.aniworld.AniWorldHttpTransport
import com.axiel7.anihyou.release.data.aniworld.AniWorldProvider
import com.axiel7.anihyou.release.data.aniworld.JdkAniWorldHttpTransport
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_1_2
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_2_3
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_3_4
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_4_5
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_5_6
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_6_7
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_7_8
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_8_9
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_9_10
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_10_11
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import com.axiel7.anihyou.release.data.preferences.ReleasePreferencesStore
import com.axiel7.anihyou.release.data.repository.AniListIdentityCandidateSource
import com.axiel7.anihyou.release.data.repository.AniListReleaseAccountContextProvider
import com.axiel7.anihyou.release.data.repository.ReleaseIdentityMatcher
import com.axiel7.anihyou.release.data.repository.ReleaseSyncCoordinator
import com.axiel7.anihyou.release.data.repository.RoomIdentityCandidateStore
import com.axiel7.anihyou.release.data.repository.RoomNotificationOutboxStore
import com.axiel7.anihyou.release.data.repository.RoomReleaseMappingRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseNotificationGate
import com.axiel7.anihyou.release.data.repository.RoomReleaseOutboxRepository
import com.axiel7.anihyou.release.data.repository.RoomReleasePreferencesRepository
import com.axiel7.anihyou.release.data.repository.RoomReleasePresentationRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseProjectionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseSyncStore
import com.axiel7.anihyou.release.data.repository.RoomReleaseDecisionRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseEvidenceRepository
import com.axiel7.anihyou.release.data.repository.RoomReleaseIntelligencePersistence
import com.axiel7.anihyou.release.data.repository.RoomSourceHealthRepository
import java.time.Clock
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val animetrackerReleaseModule = module {
    single<Clock> { Clock.systemUTC() }
    single<ReleaseDatabase> {
        Room.databaseBuilder(
            androidApplication(),
            ReleaseDatabase::class.java,
            "release-data.db",
        ).addMigrations(
            RELEASE_MIGRATION_1_2,
            RELEASE_MIGRATION_2_3,
            RELEASE_MIGRATION_3_4,
            RELEASE_MIGRATION_4_5,
            RELEASE_MIGRATION_5_6,
            RELEASE_MIGRATION_6_7,
            RELEASE_MIGRATION_7_8,
            RELEASE_MIGRATION_8_9,
            RELEASE_MIGRATION_9_10,
            RELEASE_MIGRATION_10_11,
        ).build()
    }
    single<AniWorldHttpTransport> { JdkAniWorldHttpTransport() }
    single<ReleaseAuthorityReducer> { AniWorldReleaseAuthorityReducer() }
    single { RoomReleaseEvidenceRepository(get()) }
    single<ReleaseEvidenceRepository> { get<RoomReleaseEvidenceRepository>() }
    single<ReleaseForecastRevisionRepository> { get<RoomReleaseEvidenceRepository>() }
    single<ReleaseDecisionRepository> { RoomReleaseDecisionRepository(get()) }
    single<SourceHealthRepository> { RoomSourceHealthRepository(get()) }
    single { RoomReleaseIntelligencePersistence(get(), get()) }
    single { AniWorldClient(get()) }
    single { AniWorldProvider(client = get(), clock = get()) }
    single { RoomIdentityCandidateStore(get(), get()) }
    single<IdentityCandidateSource> { AniListIdentityCandidateSource(get(), get(), get()) }
    single { ReleaseIdentityMatcher(candidateSource = get(), clock = get()) }
    single<ReleaseAccountContextProvider> {
        AniListReleaseAccountContextProvider(
            mediaListRepository = get(),
            defaultPreferencesRepository = get(),
        )
    }
    single { RoomReleaseSyncStore(get(), get<ReleaseForecastRecheckScheduler>(), get()) }
    single { ReleasePreferencesStore(androidApplication()) }
    single<ReleaseRefreshCoordinator> { get<ReleaseSyncCoordinator>() }
    single {
        ReleaseSyncCoordinator(
            provider = get<AniWorldProvider>(),
            database = get(),
            syncStore = get(),
            preferences = get<ReleasePreferencesStore>().preferences,
            accountContextProvider = get(),
            outboxScheduler = get<ReleaseOutboxScheduler>(),
            forecastScheduler = get<ReleaseForecastRecheckScheduler>(),
            clock = get(),
            identityMatcher = get(),
        )
    }
    single { RoomReleaseProjectionRepository(get(), get(), get()) }
    single { RoomReleasePresentationRepository(get()) }
    single<ReleasePresentationRepository> { get<RoomReleasePresentationRepository>() }
    single { RoomReleaseMappingRepository(get(), get()) }
    single<ReleaseMappingRepository> { get<RoomReleaseMappingRepository>() }
    single { RoomNotificationOutboxStore(get(), get()) }
    single<ReleaseOutboxRepository> { RoomReleaseOutboxRepository(get()) }
    single<ReleasePreferencesRepository> { RoomReleasePreferencesRepository(get()) }
    single<ReleaseNotificationGate> { RoomReleaseNotificationGate(get(), get(), get()) }
}

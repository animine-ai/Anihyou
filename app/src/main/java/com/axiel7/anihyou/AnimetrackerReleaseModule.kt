package com.axiel7.anihyou

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.room.Room
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.AniWorldShadowPollStore
import com.axiel7.anihyou.release.core.api.AniWorldShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.api.AniWorldShadowScheduler
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
import com.axiel7.anihyou.release.data.aniworld.AniWorldExtensionTargetSource
import com.axiel7.anihyou.release.data.aniworld.AniWorldHttpTransport
import com.axiel7.anihyou.release.data.aniworld.AniWorldProvider
import com.axiel7.anihyou.release.data.aniworld.JdkAniWorldHttpTransport
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.data.extension.ApprovedExtensionAuthorityTuple
import com.axiel7.anihyou.release.data.extension.ProductionExtensionHostBoundary
import com.axiel7.anihyou.release.data.extension.ProductionExtensionHostConfiguration
import com.axiel7.anihyou.release.data.extension.ExtensionTargetSource
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
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_11_12
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_12_13
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_13_14
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
import com.axiel7.anihyou.release.data.repository.RoomReleaseReconciliationRepository
import com.axiel7.anihyou.release.data.repository.RoomAniWorldPollStore
import com.axiel7.anihyou.release.data.repository.ExtensionShadowSyncOrchestrator
import com.axiel7.anihyou.release.data.repository.RoomExtensionShadowGenerationStore
import com.axiel7.anihyou.release.data.repository.RoomSourceHealthRepository
import java.time.Clock
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

val animetrackerReleaseModule = module {
    single<Clock> { Clock.systemUTC() }
    // Explicit first trust of a source the user accepted (private-test workaround). One store: the installer reads it, and
    // the release Authority follows only from it, narrowly.
    single {
        com.axiel7.anihyou.release.data.extension.ManualExtensionTrustStore(
            com.axiel7.anihyou.release.data.extension.ProductionExtensionSources.sourcesDirectory(androidApplication()))
    }
    single<com.axiel7.anihyou.release.core.source.ExtensionSourceRepository> {
        com.axiel7.anihyou.release.data.extension.ProductionExtensionSources.create(androidApplication(), get(), get(),
            reviewedExtensionConfiguration(), get<com.axiel7.anihyou.release.data.extension.ManualExtensionTrustStore>())
    }
    single<com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository> {
        requireNotNull(get<com.axiel7.anihyou.release.core.source.ExtensionSourceRepository>().productPolicy)
    }
    single<com.axiel7.anihyou.release.data.extension.InstalledExtensionAccess> {
        get<com.axiel7.anihyou.release.core.source.ExtensionSourceRepository>() as com.axiel7.anihyou.release.data.extension.InstalledExtensionAccess
    }
    single<com.axiel7.anihyou.release.core.extension.ExtensionRuntime> {
        com.axiel7.anihyou.release.data.extension.AndroidIsolatedExtensionRuntime(androidApplication())
    }
    single {
        com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore(
            androidApplication().filesDir.resolve("extension-product"))
    }
    single {
        com.axiel7.anihyou.release.data.extension.FileExtensionPostponementStore(
            androidApplication().filesDir.resolve("extension-product"), get())
    }
    single<com.axiel7.anihyou.release.core.api.ExtensionPostponementPresentationRepository> {
        get<com.axiel7.anihyou.release.data.extension.FileExtensionPostponementStore>()
    }
    single<com.axiel7.anihyou.release.core.source.ExtensionDiagnosticsRepository> {
        com.axiel7.anihyou.release.data.extension.ExtensionCenterDiagnosticsRepository(get(), get(), get())
    }
    single<com.axiel7.anihyou.release.core.navigation.ProviderNavigationGateway> {
        com.axiel7.anihyou.release.data.extension.InstalledProviderNavigationGateway(get(), get(), get(),
            androidApplication().filesDir.resolve("release-extension-network"))
    }
    single<com.axiel7.anihyou.release.core.navigation.ExternalNavigationLauncher> {
        com.axiel7.anihyou.release.data.extension.AndroidExternalNavigationLauncher(androidApplication())
    }
    single<com.axiel7.anihyou.release.core.navigation.ProviderNavigationProductRepository> {
        com.axiel7.anihyou.release.data.extension.ExtensionProviderNavigationProductRepository(
            get(), get(), get(), get(), get(), get(), get())
    }
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
            RELEASE_MIGRATION_11_12,
            RELEASE_MIGRATION_12_13,
            RELEASE_MIGRATION_13_14,
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
    single { RoomReleaseReconciliationRepository(get()) }
    single<AniWorldShadowPollStore> { RoomAniWorldPollStore(get(), get(), get()) }
    single<ExtensionTargetSource> { AniWorldExtensionTargetSource(get<AniWorldShadowPollStore>(), get()) }
    single { RoomExtensionShadowGenerationStore(get(), get(), get()) }
    single {
        ProductionExtensionHostBoundary.create(androidApplication(), reviewedExtensionConfiguration(),
            get<com.axiel7.anihyou.release.data.extension.ManualExtensionTrustStore>())
    }
    single<AniWorldShadowRefreshCoordinator> {
        com.axiel7.anihyou.release.data.repository.SingleSourceShadowRefreshCoordinator(
            policy = get(),
            installed = get(),
            runtime = get(),
            networkDirectory = androidApplication().filesDir.resolve("release-extension-network"),
            authority = get<ProductionExtensionHostBoundary>().authority,
            reconciliation = get(),
            generations = get(),
            targetSource = get(),
            clock = get(),
            navigationStore = get(),
            postponementStore = get(),
        )
    }
    single<com.axiel7.anihyou.release.data.repository.SingleSourceShadowRefreshCoordinator> {
        get<AniWorldShadowRefreshCoordinator>() as com.axiel7.anihyou.release.data.repository.SingleSourceShadowRefreshCoordinator
    }
    single<com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshCoordinator> {
        com.axiel7.anihyou.release.data.repository.ProductionExtensionReleaseRefreshCoordinator(
            sources = get(),
            delegate = get<com.axiel7.anihyou.release.data.repository.SingleSourceShadowRefreshCoordinator>(),
        )
    }
    single<com.axiel7.anihyou.release.core.api.ExtensionRefreshScheduleRepository> {
        com.axiel7.anihyou.release.data.extension.FileExtensionRefreshScheduleStore(
            androidApplication().filesDir.resolve("release-extension-refresh"))
    }
    single { AniWorldClient(get()) }
    single { AniWorldProvider(client = get(), clock = get()) }
    single { RoomIdentityCandidateStore(get(), get()) }
    single<IdentityCandidateSource> { AniListIdentityCandidateSource(get(), get(), get()) }
    // Matching management (Settings): one real Room-backed repository, one shared targeted matching service.
    single { com.axiel7.anihyou.release.data.repository.MappingWriterFence(get<ReleaseDatabase>(), get<Clock>()) }
    single {
        com.axiel7.anihyou.release.data.repository.SourceSeriesMatchingService(
            database = get<ReleaseDatabase>(),
            policy = get<com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository>(),
            navigation = get<com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore>(),
            candidates = get<IdentityCandidateSource>(),
            fence = get(),
            clock = get<Clock>(),
        )
    }
    single<com.axiel7.anihyou.release.core.api.MatchingManagementRepository> {
        com.axiel7.anihyou.release.data.repository.RoomMatchingManagementRepository(
            database = get<ReleaseDatabase>(),
            navigation = get<com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore>(),
            sources = get<com.axiel7.anihyou.release.core.source.ExtensionSourceRepository>(),
            service = get(),
            fence = get(),
            clock = get<Clock>(),
        )
    }
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
    // Extension rows are folded per exact release source (Room v14), so no receipt gate is needed here.
    single {
        RoomReleasePresentationRepository(get(), get(), get(), get(), get(),
            legacyLaneEnabled = get<ReleasePreferencesRepository>().releasePreferences
                .map { it.selectedProvider != null }.distinctUntilChanged())
    }
    single<ReleasePresentationRepository> { get<RoomReleasePresentationRepository>() }
    single { RoomReleaseMappingRepository(get(), get()) }
    single<ReleaseMappingRepository> { get<RoomReleaseMappingRepository>() }
    single { RoomNotificationOutboxStore(get(), get()) }
    single<ReleaseOutboxRepository> { RoomReleaseOutboxRepository(get()) }
    single<ReleasePreferencesRepository> { RoomReleasePreferencesRepository(get()) }
    single<ReleaseNotificationGate> { RoomReleaseNotificationGate(get(), get(), get()) }
}

/** Both the user-source installer and the release Authority boundary receive the same reviewed configuration. */
private fun reviewedExtensionConfiguration(): ProductionExtensionHostConfiguration? {
    if (BuildConfig.EXTENSION_BUILD_PROFILE == "unprovisioned") {
        check(listOf(BuildConfig.EXTENSION_REPOSITORY_ID, BuildConfig.EXTENSION_ROOT_SHA256,
            BuildConfig.EXTENSION_DISTRIBUTION_ORIGINS, BuildConfig.EXTENSION_ALLOWED_HOSTS,
            BuildConfig.EXTENSION_PUBLISHER_ID, BuildConfig.EXTENSION_SIGNING_KEY_ID).all(String::isBlank))
        return null
    }
    check(BuildConfig.EXTENSION_BUILD_PROFILE == "reviewed")
    return ProductionExtensionHostConfiguration(
        repositoryId = BuildConfig.EXTENSION_REPOSITORY_ID,
        initialRootSha256 = BuildConfig.EXTENSION_ROOT_SHA256,
        distributionOrigins = BuildConfig.EXTENSION_DISTRIBUTION_ORIGINS.split(',').toSet(),
        parseFuelByExtensionId = mapOf(
            com.axiel7.anihyou.release.core.extension.ExtensionId.parse("de.aniworld") to 100_000_000L),
        allowedHosts = BuildConfig.EXTENSION_ALLOWED_HOSTS.split(',').toSet(),
        approvedAuthority = setOf(ApprovedExtensionAuthorityTuple(
            BuildConfig.EXTENSION_PUBLISHER_ID, BuildConfig.EXTENSION_SIGNING_KEY_ID,
            "de.aniworld", "aniworld",
            setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT, SourceRole.DIRECT))),
    )
}

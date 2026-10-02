package com.axiel7.anihyou.release.data.extension

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationTargetKind
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import com.axiel7.anihyou.release.core.navigation.ExternalNavigationLauncher
import com.axiel7.anihyou.release.core.navigation.NavigationContextV1
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationGateway
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationResult
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.repository.RoomReleaseReconciliationRepository
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionProviderNavigationTargetCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: ReleaseDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun overviewCacheIsFencedByPackageGenerationAndOldTargetCannotLaunch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        val key = ExtensionSelectionKey("source-a", "fixture.extension", "publisher-a", "provider-a")
        val digest = "a".repeat(64)
        val gateway = CountingGateway(key, digest)
        val directory = Files.createTempDirectory("provider-target-cache").toFile()
        try {
            val sources = EmptySourceRepository()
            val policy = FileExtensionProductPolicyRepository(directory) { true }
            val stateStore = FileProviderNavigationStateStore(directory)
            stateStore.upsertSegment(ProviderEpisodeSegment(key, 42, "fixture-series", 1, 1, 1, 12))
            val launched = AtomicInteger()
            val product = ExtensionProviderNavigationProductRepository(
                sources = sources,
                policy = policy,
                gateway = gateway,
                store = stateStore,
                database = db,
                reconciliation = RoomReleaseReconciliationRepository(db),
                launcher = ExternalNavigationLauncher {
                    launched.incrementAndGet()
                    true
                },
            )

            val first = product.overview(42, key) as ProviderNavigationResult.Ready
            val repeated = product.overview(42, key) as ProviderNavigationResult.Ready
            assertSame(first.target, repeated.target)
            assertEquals(1, gateway.dispatchCount.get())

            gateway.packageGeneration = 9
            val upgraded = product.overview(42, key) as ProviderNavigationResult.Ready
            assertNotSame(first.target, upgraded.target)
            assertEquals(9L, upgraded.target.provider.packageGeneration)
            assertEquals(2, gateway.dispatchCount.get())
            assertEquals(listOf(8L, 9L), gateway.dispatchedGenerations)

            val oldLaunch = product.launch(first.target)
            assertEquals(
                ProviderNavigationResult.Unavailable(NavigationUnavailableReason.STALE_RESULT),
                oldLaunch,
            )
            assertEquals(0, launched.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class CountingGateway(
        private val key: ExtensionSelectionKey,
        private val digest: String,
    ) : ProviderNavigationGateway {
        var packageGeneration: Long = 8
        val dispatchCount = AtomicInteger()
        val dispatchedGenerations = mutableListOf<Long>()

        private fun provider() = NavigationProvider(
            key = key,
            displayName = "Fixture provider",
            packageDigest = digest,
            capabilities = setOf(NavigationCapability.OVERVIEW_NAVIGATION),
            allowedHosts = setOf("navigation.example"),
            supportedTracks = setOf("DE_SUB", "DE_DUB"),
            packageGeneration = packageGeneration,
        )

        override suspend fun providers(): List<NavigationProvider> = listOf(provider())

        override suspend fun dispatch(
            provider: NavigationProvider,
            request: NavigationContextV1,
            generation: String,
        ): ProviderNavigationTargetV1 {
            dispatchCount.incrementAndGet()
            dispatchedGenerations += provider.packageGeneration
            val path = "package-" + provider.packageGeneration + "/" + request.providerSeriesKey
            return ProviderNavigationTargetV1(
                schemaVersion = 1,
                extensionId = com.axiel7.anihyou.release.core.extension.ExtensionId.parse(key.extensionId),
                providerId = ProviderId.parse(key.providerId),
                targetKind = NavigationTargetKind.OVERVIEW,
                providerSeriesKey = request.providerSeriesKey,
                sourceSeason = request.sourceSeason,
                providerEpisode = null,
                track = null,
                url = "https://navigation.example/" + path,
                requestId = request.targetToken,
                sourceHash = null,
                diagnostics = emptyList(),
            )
        }
    }

    private class EmptySourceRepository : ExtensionSourceRepository {
        private val mutableSources = MutableStateFlow<List<ExtensionSource>>(emptyList())
        override val sources = mutableSources.asStateFlow()
        override val productPolicy: ExtensionProductPolicyRepository? = null
        override suspend fun add(url: String) = AddExtensionSourceResult.InvalidUrl
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun refresh(sourceId: String) = Unit
        override suspend fun refreshEnabled() = false
        override suspend fun activate(sourceId: String, extensionId: String) = Unit
    }
}

package com.axiel7.anihyou.release.data.extension

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.extension.NavigationCapability
import com.axiel7.anihyou.release.core.extension.NavigationContextV1
import com.axiel7.anihyou.release.core.extension.ProviderNavigationTargetV1
import com.axiel7.anihyou.release.core.navigation.NavigationProvider
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationGateway
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import com.axiel7.anihyou.release.core.navigation.NavigationUnavailableReason
import com.axiel7.anihyou.release.core.navigation.ProviderNavigationResult
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.repository.RoomReleaseReconciliationRepository
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionProviderNavigationDiagnosticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var database: ReleaseDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun watchNextRejectsReceiptFromPreviousPackageGeneration() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        val key = ExtensionSelectionKey("source-a", "fixture.extension", "publisher-a", "provider-a")
        val digest = "a".repeat(64)
        val directory = Files.createTempDirectory("navigation-stale-diagnostic").toFile()
        try {
            val policy = FileExtensionProductPolicyRepository(directory) { true }
            policy.selectActiveSource(key)
            val source = ExtensionSource(
                id = key.sourceId,
                url = "https://packages.example/source",
                origin = "fixture",
                enabled = true,
                status = ExtensionSourceStatus.CURRENT,
                extensions = listOf(
                    SourceExtension(
                        extensionId = key.extensionId,
                        displayName = "Fixture",
                        version = "1.0.0",
                        digest = digest,
                        releaseSequence = 1,
                        capabilities = listOf("OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION"),
                        installedVersion = "1.0.0",
                        installedDigest = digest,
                        activationAllowed = true,
                        providerId = key.providerId,
                        publisherId = key.publisherId,
                        installedUsable = true,
                        packageGeneration = 9,
                    ),
                ),
            )
            val sources = MutableSourceRepository(listOf(source))
            val gateway = NeverDispatchGateway(key, digest, packageGeneration = 9)
            val store = FileProviderNavigationStateStore(directory)
            store.record(
                source = key,
                releaseGeneration = policy.policy.value.releaseGeneration,
                packageDigest = digest,
                installments = emptyList(),
                packageGeneration = 8,
            )
            val product = ExtensionProviderNavigationProductRepository(
                sources = sources,
                policy = policy,
                gateway = gateway,
                store = store,
                database = db,
                reconciliation = RoomReleaseReconciliationRepository(db),
                launcher = { false },
            )

            val result = product.watchNext(mediaId = 42, watchedProgress = 0)

            assertEquals(
                ProviderNavigationResult.Unavailable(NavigationUnavailableReason.RELEASE_SOURCE_UNAVAILABLE),
                result,
            )
            assertEquals(0, gateway.dispatchCount.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class NeverDispatchGateway(
        private val key: ExtensionSelectionKey,
        private val digest: String,
        private val packageGeneration: Long,
    ) : ProviderNavigationGateway {
        val dispatchCount = AtomicInteger()

        override suspend fun providers() = listOf(
            NavigationProvider(
                key = key,
                displayName = "Fixture",
                packageDigest = digest,
                capabilities = setOf(NavigationCapability.OVERVIEW_NAVIGATION, NavigationCapability.EPISODE_NAVIGATION),
                allowedHosts = setOf("navigation.example"),
                supportedTracks = setOf("DE_SUB", "DE_DUB"),
                packageGeneration = packageGeneration,
            ),
        )

        override suspend fun dispatch(
            provider: NavigationProvider,
            request: NavigationContextV1,
            generation: String,
        ): ProviderNavigationTargetV1? {
            dispatchCount.incrementAndGet()
            return null
        }
    }

    private class MutableSourceRepository(
        initial: List<ExtensionSource>,
    ) : ExtensionSourceRepository {
        private val mutableSources = MutableStateFlow(initial)
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

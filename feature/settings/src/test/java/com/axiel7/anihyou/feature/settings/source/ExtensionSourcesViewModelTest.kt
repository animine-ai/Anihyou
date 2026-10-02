package com.axiel7.anihyou.feature.settings.source

import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.SourceExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionSourcesViewModelTest {
    private val repository = FakeExtensionSourceRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun trustAvailabilityIsTakenFromTheRepositoryAndDefaultsToAvailable() = runTest {
        assertTrue(ExtensionSourcesViewModel(repository).uiState.value.trustAvailable)
        repository.trustAvailable = false
        assertFalse(ExtensionSourcesViewModel(repository).uiState.value.trustAvailable)
    }

    @Test
    fun diagnosticsFromOldPackageOrSelectionCannotReappearAfterAnAsyncRefresh() = runTest {
        val key = usableKey()
        val sources = repositoryWithUsableExtension(key)
        val policy = FakeExtensionProductPolicyRepository(ExtensionProductPolicy(activeReleaseSource = key))
        var pending: kotlinx.coroutines.CompletableDeferred<Map<String, String>>? = null
        val diagnostics = object : com.axiel7.anihyou.release.core.source.ExtensionDiagnosticsRepository {
            override suspend fun inspect(key: ExtensionSelectionKey): Map<String, String> =
                pending?.await() ?: mapOf("Package SHA" to "package-a")
        }
        val viewModel = ExtensionSourcesViewModel(sources, policy, diagnostics)
        viewModel.refreshDiagnostics()
        assertEquals("package-a", viewModel.uiState.value.diagnostics[key]?.get("Package SHA"))

        pending = kotlinx.coroutines.CompletableDeferred()
        viewModel.refreshDiagnostics()
        sources.sources.value = sources.sources.value.map { source -> source.copy(extensions =
            source.extensions.map { it.copy(installedDigest = "package-b") }) }
        assertTrue(viewModel.uiState.value.diagnostics.isEmpty())
        pending!!.complete(mapOf("Package SHA" to "package-a"))
        assertTrue(viewModel.uiState.value.diagnostics.isEmpty())

        pending = null
        viewModel.refreshDiagnostics()
        assertTrue(viewModel.uiState.value.diagnostics.isNotEmpty())
        pending = kotlinx.coroutines.CompletableDeferred()
        viewModel.refreshDiagnostics()
        policy.policy.value = policy.policy.value.copy(generation = 1, activeReleaseSource = null)
        assertTrue(viewModel.uiState.value.diagnostics.isEmpty())
        pending!!.complete(mapOf("Last successful sync" to "stale-source-a"))
        assertTrue(viewModel.uiState.value.diagnostics.isEmpty())
    }

    @Test
    fun addForwardsTrimmedHttpsUrlAndClearsItAfterSuccess() = runTest {
        val viewModel = ExtensionSourcesViewModel(repository)
        viewModel.onUrlChanged("  https://example.test/repository.json  ")

        viewModel.addSource()

        assertEquals(listOf("https://example.test/repository.json"), repository.addedUrls)
        assertEquals(listOf("source-id"), repository.refreshedSourceIds)
        assertEquals("", viewModel.uiState.value.url)
        assertEquals(AddExtensionSourceResult.Added("source-id"), viewModel.uiState.value.addResult)
        assertFalse(viewModel.uiState.value.isAdding)
    }

    @Test
    fun rejectedOrDuplicateAddNeverRefreshesMetadata() = runTest {
        val viewModel = ExtensionSourcesViewModel(repository)
        viewModel.onUrlChanged("https://example.test/repository.json")
        for (result in listOf(AddExtensionSourceResult.InvalidUrl,
            AddExtensionSourceResult.Duplicate("existing-source"), AddExtensionSourceResult.LimitReached)) {
            repository.addResult = result
            viewModel.addSource()
            assertEquals(result, viewModel.uiState.value.addResult)
        }
        assertTrue(repository.refreshedSourceIds.isEmpty())
    }

    @Test
    fun observesPersistedProductPolicy() = runTest {
        val key = usableKey()
        val productPolicyRepository = FakeExtensionProductPolicyRepository(
            ExtensionProductPolicy(
                generation = 4,
                activeReleaseSource = key,
                preferredNavigationProvider = key,
            ),
        )
        val viewModel = ExtensionSourcesViewModel(repositoryWithUsableExtension(key), productPolicyRepository)

        assertTrue(viewModel.uiState.value.canEditProductPolicy)
        assertEquals(4L, viewModel.uiState.value.productPolicy.generation)
        assertEquals(key, viewModel.uiState.value.productPolicy.activeReleaseSource)
        assertEquals(key, viewModel.uiState.value.productPolicy.preferredNavigationProvider)
    }

    @Test
    fun selectionEventsPersistActiveSourceAndNavigationSeparately() = runTest {
        val activeKey = usableKey()
        val navigationKey = usableKey(
            sourceId = "navigation-source",
            extensionId = "navigation.extension",
            publisherId = "navigation.publisher",
            providerId = "navigation-provider",
        )
        val sourceRepository = repositoryWithUsableExtension(activeKey, navigationKey)
        val policyRepository = FakeExtensionProductPolicyRepository()
        val viewModel = ExtensionSourcesViewModel(sourceRepository, policyRepository)

        viewModel.selectActiveSource(activeKey)
        viewModel.selectNavigationProvider(navigationKey)

        assertEquals(activeKey, policyRepository.policy.value.activeReleaseSource)
        assertEquals(navigationKey, policyRepository.policy.value.preferredNavigationProvider)
        assertEquals(2L, policyRepository.policy.value.generation)
    }

    @Test
    fun preferencesPersistAgainstTheFullStableExtensionIdentity() = runTest {
        val key = usableKey()
        val viewModel = ExtensionSourcesViewModel(
            repositoryWithUsableExtension(key),
            FakeExtensionProductPolicyRepository(),
        )
        val preferences = ExtensionPreferences(
            enabledTracks = setOf("DE_SUB"),
            preferredTrackOrder = listOf("DE_SUB", "DE_DUB"),
            languageOrder = listOf("de", "en"),
            visibleInProviderField = false,
        )

        viewModel.setPreferences(key, preferences)

        assertEquals(preferences, viewModel.uiState.value.productPolicy.preferences[key])
        assertEquals(setOf(key), viewModel.uiState.value.productPolicy.preferences.keys)
    }

    @Test
    fun rejectsUnusableActiveSourceAndSurfacesPolicyFailure() = runTest {
        val key = usableKey()
        val revoked = SourceExtension(
            extensionId = key.extensionId,
            displayName = "Signed Provider",
            version = "1.0",
            digest = "digest",
            releaseSequence = 1,
            capabilities = listOf("CALENDAR", "OVERVIEW_NAVIGATION"),
            installedDigest = "digest",
            revoked = true,
            activationAllowed = true,
            providerId = key.providerId,
            publisherId = key.publisherId,
        )
        val viewModel = ExtensionSourcesViewModel(
            repositoryWithExtension(key, revoked),
            FakeExtensionProductPolicyRepository(),
        )

        viewModel.selectActiveSource(key)

        assertTrue(viewModel.uiState.value.actionFailed)
        assertNull(viewModel.uiState.value.productPolicy.activeReleaseSource)
    }

    @Test
    fun repositoryErrorsAreReportedAndCanBeDismissed() = runTest {
        val key = usableKey()
        val policyRepository = FakeExtensionProductPolicyRepository().apply { failWrites = true }
        val viewModel = ExtensionSourcesViewModel(repositoryWithUsableExtension(key), policyRepository)

        viewModel.selectActiveSource(key)

        assertTrue(viewModel.uiState.value.actionFailed)
        viewModel.clearActionFailure()
        assertFalse(viewModel.uiState.value.actionFailed)
        assertNull(policyRepository.policy.value.activeReleaseSource)
    }

    @Test
    fun optionalPolicyRepositoryKeepsLegacyViewModelConstructionReadOnly() = runTest {
        val viewModel = ExtensionSourcesViewModel(repository)

        assertFalse(viewModel.uiState.value.canEditProductPolicy)
        viewModel.selectActiveSource(usableKey())
        assertFalse(viewModel.uiState.value.actionFailed)
    }

    @Test
    fun sourceActionsClaimTheSourceSynchronouslyAndBlockSiblingActions() = runTest {
        val key = usableKey()
        val sources = repositoryWithUsableExtension(key)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        sources.refreshGate = gate
        val viewModel = ExtensionSourcesViewModel(sources)

        viewModel.refreshSource(key.sourceId)
        assertEquals(setOf(key.sourceId), viewModel.uiState.value.busySourceIds)
        viewModel.removeExtension(key.sourceId, "sibling.extension")
        viewModel.rollback(key.sourceId, "sibling.extension", 7, "previous-digest")

        assertEquals(listOf(key.sourceId), sources.refreshedSourceIds)
        assertTrue(sources.removedExtensions.isEmpty())
        assertTrue(sources.rollbacks.isEmpty())
        gate.complete(Unit)
        assertTrue(viewModel.uiState.value.busySourceIds.isEmpty())
    }

    @Test
    fun rollbackForwardsTheCapturedGenerationAndDigest() = runTest {
        val key = usableKey()
        val sources = repositoryWithUsableExtension(key)
        val viewModel = ExtensionSourcesViewModel(sources)

        viewModel.rollback(key.sourceId, key.extensionId, expectedGeneration = 17, targetDigest = "exact-good-digest")

        assertEquals(listOf(FakeExtensionSourceRepository.RollbackCall(
            key.sourceId, key.extensionId, 17, "exact-good-digest",
        )), sources.rollbacks)
    }

    @Test
    fun restoredOfflineInstalledPackageCanRemainSelectedForNavigation() = runTest {
        val key = usableKey()
        val offlineInstalled = SourceExtension(
            extensionId = key.extensionId,
            displayName = "Signed offline provider",
            version = "1.0",
            digest = "candidate-digest",
            releaseSequence = 1,
            capabilities = listOf("CALENDAR", "OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION"),
            installedVersion = "1.0",
            installedDigest = "verified-local-package",
            activationAllowed = false,
            providerId = key.providerId,
            publisherId = key.publisherId,
            installedUsable = true,
            installedStatus = com.axiel7.anihyou.release.core.source.InstalledPackageStatus.USABLE,
        )
        val sources = repositoryWithExtension(key, offlineInstalled, ExtensionSourceStatus.ERROR)
        val policy = FakeExtensionProductPolicyRepository()
        val viewModel = ExtensionSourcesViewModel(sources, policy)

        viewModel.selectActiveSource(key)
        viewModel.selectNavigationProvider(key)

        assertEquals(key, policy.policy.value.activeReleaseSource)
        assertEquals(key, policy.policy.value.preferredNavigationProvider)
    }

    @Test
    fun initializationRestoresOnlyTheLocalInstalledJournal() = runTest {
        val sources = FakeExtensionSourceRepository()

        ExtensionSourcesViewModel(sources)

        assertEquals(1, sources.restoreInstalledCalls)
        assertTrue(sources.refreshedSourceIds.isEmpty())
    }

    private fun usableKey(
        sourceId: String = "source-id",
        extensionId: String = "signed.extension",
        publisherId: String = "signed.publisher",
        providerId: String = "signed-provider",
    ) = ExtensionSelectionKey(sourceId, extensionId, publisherId, providerId)

    private fun repositoryWithUsableExtension(vararg keys: ExtensionSelectionKey): FakeExtensionSourceRepository {
        val sources = keys.map { key ->
            val extension = SourceExtension(
                extensionId = key.extensionId,
                displayName = "Signed Provider",
                version = "1.0",
                digest = "digest-${key.extensionId}",
                releaseSequence = 1,
                capabilities = listOf("CALENDAR", "OVERVIEW_NAVIGATION", "EPISODE_NAVIGATION"),
                installedVersion = "1.0",
                installedDigest = "installed-${key.extensionId}",
                activationAllowed = true,
                providerId = key.providerId,
                publisherId = key.publisherId,
            )
            ExtensionSource(
                id = key.sourceId,
                url = "https://${key.sourceId}.example.test/repository.json",
                origin = "test",
                enabled = true,
                status = ExtensionSourceStatus.CURRENT,
                extensions = listOf(extension),
            )
        }
        return FakeExtensionSourceRepository().apply { this.sources.value = sources }
    }

    private fun repositoryWithExtension(
        key: ExtensionSelectionKey,
        extension: SourceExtension,
        status: ExtensionSourceStatus = ExtensionSourceStatus.CURRENT,
    ) = FakeExtensionSourceRepository().apply {
        sources.value = listOf(
            ExtensionSource(
                id = key.sourceId,
                url = "https://${key.sourceId}.example.test/repository.json",
                origin = "test",
                enabled = true,
                status = status,
                extensions = listOf(extension),
            ),
        )
    }

    private class FakeExtensionSourceRepository : ExtensionSourceRepository {
        override val sources = MutableStateFlow<List<ExtensionSource>>(emptyList())
        override var trustAvailable: Boolean = true
        val addedUrls = mutableListOf<String>()
        val refreshedSourceIds = mutableListOf<String>()
        val removedExtensions = mutableListOf<Pair<String, String>>()
        val rollbacks = mutableListOf<RollbackCall>()
        var refreshGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var restoreInstalledCalls = 0
        var addResult: AddExtensionSourceResult = AddExtensionSourceResult.Added("source-id")

        override suspend fun add(url: String): AddExtensionSourceResult {
            addedUrls += url
            return addResult
        }

        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun refresh(sourceId: String) {
            refreshedSourceIds += sourceId
            refreshGate?.await()
        }
        override suspend fun refreshEnabled(): Boolean = false
        override suspend fun activate(sourceId: String, extensionId: String) = Unit
        override suspend fun removeExtension(sourceId: String, extensionId: String) {
            removedExtensions += sourceId to extensionId
        }
        override suspend fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) {
            rollbacks += RollbackCall(sourceId, extensionId, expectedGeneration, targetDigest)
        }
        override suspend fun restoreInstalled() { restoreInstalledCalls++ }

        data class RollbackCall(
            val sourceId: String,
            val extensionId: String,
            val expectedGeneration: Long,
            val targetDigest: String,
        )
    }

    private class FakeExtensionProductPolicyRepository(
        initial: ExtensionProductPolicy = ExtensionProductPolicy(),
    ) : ExtensionProductPolicyRepository {
        override val policy = MutableStateFlow(initial)
        var failWrites = false

        override suspend fun selectActiveSource(key: ExtensionSelectionKey?) {
            check(!failWrites)
            policy.value = policy.value.copy(
                generation = policy.value.generation + 1,
                activeReleaseSource = key,
            )
        }

        override suspend fun selectNavigationProvider(key: ExtensionSelectionKey?) {
            check(!failWrites)
            policy.value = policy.value.copy(
                generation = policy.value.generation + 1,
                preferredNavigationProvider = key,
            )
        }

        override suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) {
            check(!failWrites)
            policy.value = policy.value.copy(
                generation = policy.value.generation + 1,
                preferences = policy.value.preferences + (key to preferences),
            )
        }

        override suspend fun invalidateSource(sourceId: String) {
            val current = policy.value
            policy.value = current.copy(
                generation = current.generation + 1,
                activeReleaseSource = current.activeReleaseSource?.takeUnless { it.sourceId == sourceId },
                preferredNavigationProvider = current.preferredNavigationProvider?.takeUnless { it.sourceId == sourceId },
                preferences = current.preferences.filterKeys { it.sourceId != sourceId },
            )
        }

        override suspend fun <T> withCurrentSelection(
            snapshot: ExtensionProductPolicy,
            block: suspend () -> T,
        ): T? = if (snapshot.generation == policy.value.generation) block() else null
    }
}

package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.source.*
import java.io.File
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent authentication, never a root fetched from the user-entered URL. */
internal data class AuthenticatedExtensionSourceAnchor(val pin: AppTrustPin, val allowedHosts: Set<String>)
internal fun interface ExtensionSourceTrustBootstrap {
    suspend fun authenticate(source: NormalizedExtensionSource): AuthenticatedExtensionSourceAnchor?

    /** False only for a bootstrap that cannot authenticate any source, so the UI can say so up front. */
    val provisioned: Boolean get() = true
}
internal object UnavailableExtensionSourceTrustBootstrap : ExtensionSourceTrustBootstrap {
    override suspend fun authenticate(source: NormalizedExtensionSource): AuthenticatedExtensionSourceAnchor? = null
    override val provisioned: Boolean = false
}
internal fun interface ExtensionSourceStoreFactory {
    fun create(directory: File, anchor: AuthenticatedExtensionSourceAnchor): ExtensionInstallStore
}

/** Carries cancellation across the synchronous installer/native smoke boundary. */
internal object ExtensionSourceRuntimeCancellation {
    val job = ThreadLocal<Job?>()
}

/** Per-source operations share one trust journal. Explicit activation authorizes first install/update. */
internal class FileExtensionSourceRepository(
    private val directory: File,
    private val bootstrap: ExtensionSourceTrustBootstrap,
    private val transport: ExtensionRepositoryTransport,
    private val storeFactory: ExtensionSourceStoreFactory,
    private val scheduler: ExtensionSourceScheduler,
    private val clock: Clock,
    private val runtimeSupported: Boolean,
) : ExtensionSourceRepository, InstalledExtensionAccess {
    private val registry = ExtensionSourceRegistry(directory)
    private val monitor = Any()
    private val publicationLock = Any()
    private val locks = HashMap<String, Mutex>()
    private val jobs = HashMap<String, Job>()
    private val stores = HashMap<String, ExtensionInstallStore>()
    private val anchors = HashMap<String, AuthenticatedExtensionSourceAnchor>()
    private val lifecycleIntents = HashMap<String, Long>()
    private val phases = HashMap<String, ExtensionUpdateState>()
    private var lifecycleToken = 0L
    private val mutableSources = MutableStateFlow<List<ExtensionSource>>(emptyList())
    override val sources: StateFlow<List<ExtensionSource>> = mutableSources.asStateFlow()
    override val trustAvailable: Boolean get() = bootstrap.provisioned
    private fun eligible(key: ExtensionSelectionKey): Boolean =
        sources.value.usableExtension(key) != null && registry.find(key.sourceId)?.let { it.enabled && !it.removed } == true &&
            synchronized(monitor) { key.sourceId !in lifecycleIntents }
    override val productPolicy = FileExtensionProductPolicyRepository(directory, ::eligible,
        releaseEligible = { key -> eligible(key) && sources.value.usableExtension(key)?.capabilities.orEmpty().any { role ->
            com.axiel7.anihyou.release.core.extension.SourceRole.entries.any { it.name == role }
        } },
        navigationEligible = { key -> eligible(key) && sources.value.usableExtension(key)?.capabilities.orEmpty().any {
            it == "OVERVIEW_NAVIGATION" || it == "EPISODE_NAVIGATION"
        } })
    init { publish() }

    override suspend fun loadInstalled(key: ExtensionSelectionKey): VerifiedExtensionPackage? = withContext(Dispatchers.IO) {
        if (sources.value.usableExtension(key) == null || synchronized(monitor) { key.sourceId in lifecycleIntents }) return@withContext null
        val lifecycle = registry.find(key.sourceId)?.takeIf { it.enabled && !it.removed } ?: return@withContext null
        val store = synchronized(monitor) { stores[key.sourceId] } ?: return@withContext null
        val installed = store.loadUsableExtension(key.extensionId)?.takeIf {
            it.extensionId.value == key.extensionId && it.providerId.value == key.providerId && it.publisherId == key.publisherId
        }
        // Verification can take time; a disable/removal intent or disable-reenable ABA
        // during that read must not hand a package to a new dispatch.
        if (synchronized(monitor) { key.sourceId in lifecycleIntents } ||
            registry.find(key.sourceId)?.let { !it.enabled || it.removed || it.epoch != lifecycle.epoch } != false)
            return@withContext null
        val shown = sources.value.usableExtension(key)
        if (shown?.installedDigest != installed?.packageDigest || shown?.packageGeneration != installed?.packageGeneration) publish()
        installed
    }

    override suspend fun <T> withCurrentPackage(key: ExtensionSelectionKey, digest: String, block: suspend () -> T): T? =
        lock(key.sourceId).withLock {
            if (loadInstalled(key)?.packageDigest == digest) block() else null
        }

    override suspend fun <T> withCurrentGeneration(key: ExtensionSelectionKey, digest: String, generation: Long, block: suspend () -> T): T? =
        lock(key.sourceId).withLock {
            val current = loadInstalled(key)
            if (current?.packageDigest == digest && current.packageGeneration == generation) block() else null
        }

    override suspend fun restoreInstalled() = withContext(Dispatchers.IO) {
        registry.all().filter { it.enabled && !it.removed && File(directory, it.id).resolve("state.json").isFile }.forEach { source ->
            lock(source.id).withLock {
                if (synchronized(monitor) { source.id in lifecycleIntents || stores.containsKey(source.id) }) return@withLock
                val anchor = bootstrap.authenticate(source.address) ?: return@withLock
                fence(source)
                require(source.address.origin in anchor.pin.distributionOrigins)
                val store = storeFactory.create(File(directory, source.id), anchor)
                store.recoverInterrupted(clock.instant())
                // Existing installer policy performs only signed trusted LKG recovery.
                store.snapshot().generations.keys.forEach { store.loadUsableExtension(it) }
                currentCoroutineContext().ensureActive(); fence(source)
                synchronized(monitor) { anchors[source.id] = anchor; stores[source.id] = store }
            }
        }
        publish()
        reconcileSelections()
    }

    private suspend fun reconcileSelections() {
        val policy = productPolicy.policy.value
        listOfNotNull(policy.activeReleaseSource, policy.preferredNavigationProvider).distinct().forEach { key ->
            if (loadInstalled(key) == null) productPolicy.invalidateExtension(key)
        }
    }

    override suspend fun add(url: String): AddExtensionSourceResult = withContext(Dispatchers.IO) {
        val address = runCatching { NormalizedExtensionSource.parse(url) }.getOrNull()
            ?: return@withContext AddExtensionSourceResult.InvalidUrl
        val result = registry.add(address) ?: return@withContext AddExtensionSourceResult.LimitReached
        publish()
        if (result.second) scheduler.scheduleRefresh()
        if (result.second) AddExtensionSourceResult.Added(result.first.id) else AddExtensionSourceResult.Duplicate(result.first.id)
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean) = changeLifecycle(sourceId, enabled, false)
    override suspend fun remove(sourceId: String) = changeLifecycle(sourceId, false, true)

    override suspend fun removeExtension(sourceId: String, extensionId: String) = withContext(Dispatchers.IO + NonCancellable) {
        val entry = sources.value.singleOrNull { it.id == sourceId }?.let { source ->
            source.extensions.singleOrNull { it.extensionId == extensionId }?.let { source.selectionKey(it) }
        } ?: return@withContext
        val intent = synchronized(monitor) {
            val token = ++lifecycleToken
            lifecycleIntents[sourceId] = token
            jobs[sourceId]?.cancel()
            token
        }
        productPolicy.invalidateExtension(entry)
        lock(sourceId).withLock {
            if (synchronized(monitor) { lifecycleIntents[sourceId] == intent }) {
                synchronized(monitor) { stores[sourceId] }?.removeExtension(extensionId)
                synchronized(monitor) { lifecycleIntents.remove(sourceId) }
            }
        }
        publish()
    }

    override suspend fun diagnostics(key: ExtensionSelectionKey): Map<String, String> = withContext(Dispatchers.IO) {
        lock(key.sourceId).withLock {
            val store = synchronized(monitor) { stores[key.sourceId] } ?: return@withLock emptyMap()
            val before = store.snapshot()
            val verified = store.loadUsableExtension(key.extensionId)
            val snapshot = store.snapshot()
            if (before.generations != snapshot.generations) publish()
            val source = sources.value.singleOrNull { it.id == key.sourceId } ?: return@withLock emptyMap()
            val generation = snapshot.generations[key.extensionId]
            val catalog = snapshot.index?.packages?.filter { it.binding.extensionId == key.extensionId &&
                it.binding.providerId == key.providerId && it.binding.publisherId == key.publisherId }
                ?.maxByOrNull { it.binding.releaseSequence }?.binding
            val receipt = generation?.knownGood ?: generation?.lastRejected
            val publisher = snapshot.root?.publishers?.singleOrNull { it.extensionId == key.extensionId &&
                it.providerId == key.providerId && it.publisherId == key.publisherId &&
                it.keyId == (receipt?.key ?: catalog?.keyId) }
            buildMap {
                put("Extension ID", key.extensionId); put("Provider ID", key.providerId)
                put("Publisher", key.publisherId); put("Repository", source.origin)
                put("Trust status", if (verified != null) "TRUSTED" else source.extensions.firstOrNull { it.extensionId == key.extensionId }?.installedStatus?.name.orEmpty())
                put("Repository status", source.status.name)
                put("Version", receipt?.version ?: source.extensions.firstOrNull { it.extensionId == key.extensionId }?.installedVersion.orEmpty())
                // Authenticated public metadata remains inspectable after revocation, without
                // loading unusable WASM or granting the catalog any execution authority.
                put("Key ID", verified?.signingKeyId ?: receipt?.key ?: catalog?.keyId.orEmpty())
                put("Signed displayName", verified?.displayName ?: catalog?.displayName.orEmpty())
                put("Package SHA", verified?.packageDigest ?: receipt?.digest ?: catalog?.archiveSha256.orEmpty())
                put("WASM SHA", verified?.moduleDigest.orEmpty())
                val projection = source.extensions.firstOrNull { it.extensionId == key.extensionId }
                put("Current Version", receipt?.version.orEmpty())
                put("Latest Available", projection?.latestAvailableVersion.orEmpty())
                put("Release Sequence", receipt?.sequence?.toString().orEmpty())
                put("Active package generation", generation?.packageGeneration?.toString().orEmpty())
                put("Published package generation", generation?.packageGeneration?.toString().orEmpty())
                put("Journal revision", generation?.journalRevision?.toString().orEmpty())
                put("Known Good", generation?.knownGood?.version.orEmpty())
                put("Previous Good Version", generation?.previousGood?.version.orEmpty())
                put("Last Update Check", source.lastAttemptAt?.toString().orEmpty())
                put("Last Update Result", projection?.lastUpdateResult.orEmpty())
                put("Last Update Failure", projection?.lastUpdateFailure?.name.orEmpty())
                put("Last Update Failure Code", projection?.lastUpdateTechnicalCode.orEmpty())
                put("Last Successful Update", generation?.knownGood?.acceptedAt?.toString().orEmpty())
                put("Rollback Available", (store.safePreviousGood(key.extensionId, clock.instant()) != null).toString())
                put("Revocation", snapshot.revokedDigests.sorted().joinToString())
                put("Repository metadata freshness", if (projection?.metadataFresh == true) "FRESH" else "STALE_OR_UNAVAILABLE")
                put("Last metadata success", source.lastSuccessAt?.toString().orEmpty())
                put("Yanked candidate", projection?.candidateYanked?.toString().orEmpty())
                put("LKG", generation?.knownGood?.digest.orEmpty()); put("Previous Good", generation?.previousGood?.digest.orEmpty())
                put("Capabilities", (verified?.let { it.grantedRoles.map { role -> role.name } + it.navigationCapabilities.map { cap -> cap.name } }
                    ?: (publisher?.roles?.map { it.name }.orEmpty() + catalog?.navigationCapabilities?.map { it.name }.orEmpty()))
                    .distinct().sorted().joinToString())
                put("Allowed Hosts", (verified?.grantedHosts ?: publisher?.hosts.orEmpty()).sorted().joinToString())
                put("Runtime", verified?.runtimeVersion.orEmpty())
                put("Rollback", generation?.rollbackUsed?.toString().orEmpty())
                put("Quarantine", "Active package: " + (receipt?.digest in snapshot.quarantinedDigests) +
                    "; digests: " + snapshot.quarantinedDigests.sorted().joinToString())
                put("Last metadata failure", source.lastFailure?.name.orEmpty())
            }
        }
    }

    private suspend fun changeLifecycle(id: String, enabled: Boolean, removed: Boolean) = withContext(Dispatchers.IO + NonCancellable) {
        val intent = synchronized(monitor) {
            val token = ++lifecycleToken
            lifecycleIntents[id] = token
            jobs[id]?.cancel()
            token
        }
        if (!enabled || removed) productPolicy.invalidateSource(id)
        var applied = false
        lock(id).withLock {
            if (synchronized(monitor) { lifecycleIntents[id] != intent }) return@withLock
            val old = registry.find(id)
            if (old != null && !old.removed) {
                registry.update(id) { it.copy(enabled = enabled, removed = removed, epoch = it.epoch + 1) }
                applied = true
            }
            synchronized(monitor) { if (lifecycleIntents[id] == intent) lifecycleIntents.remove(id) }
        }
        publish()
        if (applied && enabled && !removed) scheduler.scheduleRefresh()
    }

    override suspend fun refresh(sourceId: String) {
        operate(sourceId, deduplicate = true) { source -> refreshMetadata(source) }
        // Never acquire the policy mutex while holding a source operation mutex.
        reconcileSelections()
    }

    override suspend fun refreshEnabled(): Boolean {
        restoreInstalled()
        registry.all().filter { it.enabled && !it.removed }.forEach { refresh(it.id) }
        return registry.all().any { it.enabled && !it.removed && it.failure == ExtensionSourceFailure.NETWORK }
    }

    override suspend fun activate(sourceId: String, extensionId: String) {
        try { operate(sourceId, deduplicate = true, phase = ExtensionUpdateState.CHECKING,
            failureClassification = ExtensionSourceFailure.INVALID_PACKAGE) { source ->
            val existing = synchronized(monitor) { stores[source.id] }
            existing?.beginOperation(extensionId, ExtensionUpdateState.CHECKING, "", clock.instant())
            var store = existing
            try {
                store = refreshMetadata(source) ?: throw SourceOperationFailure(ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE,
                    IllegalStateException("independent trust unavailable"))
                if (!runtimeSupported) throw SourceOperationFailure(ExtensionSourceFailure.UNSUPPORTED_RUNTIME,
                    IllegalStateException("unsupported runtime"))
                val snapshot = store.snapshot()
                val candidate = snapshot.index?.packages?.filter { it.binding.extensionId == extensionId }
                    ?.maxByOrNull { it.binding.releaseSequence } ?: error("no eligible signed extension")
                require(!candidate.revoked && !candidate.binding.yanked) { "latest signed extension revoked or yanked" }
                val installed = snapshot.generations[extensionId]?.active
                if (installed?.digest == candidate.binding.archiveSha256) {
                    require(store.loadUsableExtension(extensionId) != null) { "installed package unusable" }
                    store.finishOperation(extensionId, ExtensionUpdateState.INSTALLED_CURRENT, null, clock.instant())
                    return@operate
                }
                if (installed != null) require(installed.provider == candidate.binding.providerId &&
                    store.installedBinding(extensionId)?.publisherId == candidate.binding.publisherId) { "installed identity changed" }
                val reinstallRemoved = store.canReinstallRemoved(extensionId, candidate.binding.archiveSha256, candidate.binding.releaseSequence)
                require(candidate.binding.releaseSequence > (snapshot.releaseHigh[extensionId] ?: 0) || reinstallRemoved) { "release replay" }
                require(candidate.binding.archiveSha256 !in snapshot.revokedDigests &&
                    candidate.binding.archiveSha256 !in snapshot.quarantinedDigests) { "package revoked or quarantined" }
                store.beginOperation(extensionId, ExtensionUpdateState.CHECKING, candidate.binding.archiveSha256, clock.instant())
                val anchor = synchronized(monitor) { anchors.getValue(source.id) }
                progress(source.id, ExtensionUpdateState.DOWNLOADING)
                val bytes = transport.fetch(candidate.url, anchor.pin.distributionOrigins, 8 * 1024 * 1024)
                currentCoroutineContext().ensureActive()
                fence(source)
                val staged = File.createTempFile("download-", ".arex", directory)
                val operationJob = currentCoroutineContext()[Job]!!
                try {
                    progress(source.id, ExtensionUpdateState.STAGING)
                    staged.writeBytes(bytes)
                    store.install(staged, extensionId, clock.instant(), reinstallRemoved = reinstallRemoved, beforeActivation = {
                        operationJob.ensureActive(); fence(source)
                    }, onProgress = { progress(source.id, it) })
                    operationJob.ensureActive(); fence(source)
                    store.promoteHealthy(extensionId, clock.instant(), beforePromotion = {
                        operationJob.ensureActive(); fence(source)
                    })
                    store.finishOperation(extensionId, ExtensionUpdateState.UPDATED, null, clock.instant())
                } catch (error: Exception) {
                    val generation = store.snapshot().generations[extensionId]
                    // A post-promotion cancellation cannot undo an already healthy commit.
                    if (generation?.active?.digest == candidate.binding.archiveSha256 &&
                        (generation.knownGood?.digest != candidate.binding.archiveSha256 || error !is CancellationException))
                        store.quarantineAndRollback(extensionId, clock.instant())
                    throw error
                } finally { staged.delete() }
            } catch (error: Exception) {
                val completed = store?.snapshot()?.let { snapshot ->
                    val generation = snapshot.generations[extensionId]
                    val target = snapshot.operations[extensionId]?.candidateDigest
                    error is CancellationException && target?.isNotEmpty() == true &&
                        generation?.active?.digest == target && generation.knownGood?.digest == target
                } == true
                store?.finishOperation(extensionId, if (completed) ExtensionUpdateState.UPDATED else ExtensionUpdateState.UPDATE_FAILED,
                    if (completed) null else classifyUpdate(error, source.id), clock.instant(), if (completed) null else technicalFailureCode(error))
                throw error
            }
        } } finally { withContext(NonCancellable) { reconcileSelections() } }
    }

    override suspend fun rollback(sourceId: String, extensionId: String, expectedGeneration: Long, targetDigest: String) {
        try { operate(sourceId, deduplicate = true, phase = ExtensionUpdateState.ROLLING_BACK,
            failureClassification = ExtensionSourceFailure.INVALID_PACKAGE) { source ->
            val store = synchronized(monitor) { stores[source.id] } ?: error("no authenticated installed journal")
            store.beginOperation(extensionId, ExtensionUpdateState.ROLLING_BACK, targetDigest, clock.instant())
            val job = currentCoroutineContext()[Job]!!
            try {
                store.rollback(extensionId, expectedGeneration, targetDigest, clock.instant()) { job.ensureActive(); fence(source) }
                store.finishOperation(extensionId, ExtensionUpdateState.ROLLED_BACK, null, clock.instant())
            } catch (error: Exception) {
                store.finishOperation(extensionId, ExtensionUpdateState.UPDATE_FAILED, classifyUpdate(error, source.id), clock.instant(), technicalFailureCode(error))
                throw error
            }
        } } finally { withContext(NonCancellable) { reconcileSelections() } }
    }

    private fun classifyUpdate(error: Exception, sourceId: String): ExtensionUpdateFailure = when (error) {
        is CancellationException -> ExtensionUpdateFailure.CANCELLED
        is IOException -> ExtensionUpdateFailure.NETWORK
        is ExtensionSmokeException -> ExtensionUpdateFailure.SMOKE
        is ExtensionPackageVerificationException -> when (error.failure) {
            ExtensionPackageFailure.DIGEST_MISMATCH -> ExtensionUpdateFailure.DIGEST
            ExtensionPackageFailure.UNSUPPORTED_ABI, ExtensionPackageFailure.MODULE_PROFILE_REJECTED -> ExtensionUpdateFailure.RUNTIME
            else -> ExtensionUpdateFailure.SIGNATURE_OR_BINDING
        }
        is SourceOperationFailure -> when (error.classification) {
            ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE -> ExtensionUpdateFailure.TRUST
            ExtensionSourceFailure.INVALID_METADATA -> ExtensionUpdateFailure.METADATA
            ExtensionSourceFailure.UNSUPPORTED_RUNTIME -> ExtensionUpdateFailure.RUNTIME
            else -> ExtensionUpdateFailure.STORAGE
        }
        else -> if (synchronized(monitor) { phases[sourceId] } == ExtensionUpdateState.ACTIVATING)
            ExtensionUpdateFailure.ACTIVATION else ExtensionUpdateFailure.SIGNATURE_OR_BINDING
    }

    private fun technicalFailureCode(error: Exception): String = when (error) {
        is ExtensionPackageVerificationException -> error.failure.name
        is SourceOperationFailure -> error.classification.name
        is ExtensionSmokeException -> "SMOKE_" + (error.cause?.javaClass?.simpleName ?: "FAILED")
        else -> error.javaClass.simpleName
    }.replace(Regex("[^A-Za-z0-9_]"), "_").take(128).ifEmpty { "OPERATION_FAILED" }

    /** Journal-held callbacks cannot wait for publication, which may wait for that journal. */
    private fun progress(sourceId: String, state: ExtensionUpdateState) = synchronized(monitor) {
        phases[sourceId] = state
        mutableSources.value = mutableSources.value.map { source ->
            if (source.id == sourceId) source.copy(extensions = source.extensions.map { it.copy(updateState = state) }) else source
        }
    }

    private suspend fun refreshMetadata(source: RegisteredExtensionSource): ExtensionInstallStore? = try {
        refreshAuthenticatedMetadata(source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (network: IOException) {
        throw network
    } catch (classified: SourceOperationFailure) {
        throw classified
    } catch (invalid: Exception) {
        throw SourceOperationFailure(ExtensionSourceFailure.INVALID_METADATA, invalid)
    }

    private suspend fun refreshAuthenticatedMetadata(source: RegisteredExtensionSource): ExtensionInstallStore? {
        registry.update(source.id) { it.copy(attemptedAt = clock.instant(), failure = null) }
        val anchor = bootstrap.authenticate(source.address)
        currentCoroutineContext().ensureActive()
        fence(source)
        if (anchor == null) {
            registry.update(source.id) { it.copy(failure = ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE) }
            return null // Authentication precedes DNS, HTTP and even construction of a trust store.
        }
        require(source.address.origin in anchor.pin.distributionOrigins)
        val existing = synchronized(monitor) {
            val previous = anchors[source.id]
            require(previous == null || previous == anchor) { "authenticated anchor changed" }
            stores[source.id]
        }
        // The per-source operation mutex excludes another creator for this source. Do not
        // hold monitor while recovery enters the global install journal: another source's
        // installer may need monitor for its cancellation fence or progress callback.
        val store = existing ?: try {
            storeFactory.create(File(directory, source.id), anchor).also { it.recoverInterrupted(clock.instant()) }
        } catch (invalid: Exception) {
            throw SourceOperationFailure(ExtensionSourceFailure.STORAGE, invalid)
        }
        synchronized(monitor) {
            val previous = anchors[source.id]
            require(previous == null || previous == anchor) { "authenticated anchor changed" }
            anchors[source.id] = anchor
            stores[source.id] = store
        }
        val root = transport.fetch(source.address.url + "/root.json", anchor.pin.distributionOrigins, 65536)
        currentCoroutineContext().ensureActive()
        fence(source)
        store.acceptRoot(root, clock.instant())
        // A newly authenticated root can revoke the installed package even if index HTTP fails.
        store.snapshot().generations.keys.forEach { store.loadUsableExtension(it) }
        val index = transport.fetch(source.address.url + "/index.json", anchor.pin.distributionOrigins, 262144)
        currentCoroutineContext().ensureActive()
        fence(source)
        store.acceptIndex(index, clock.instant())
        store.snapshot().generations.keys.forEach { store.loadUsableExtension(it) }
        registry.update(source.id) { it.copy(succeededAt = clock.instant(), failure = null) }
        return store
    }

    private suspend fun operate(id: String, deduplicate: Boolean, phase: ExtensionUpdateState = ExtensionUpdateState.CHECKING,
        failureClassification: ExtensionSourceFailure = ExtensionSourceFailure.INVALID_METADATA,
        action: suspend (RegisteredExtensionSource) -> Unit) =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val mutex = lock(id)
                if (deduplicate) { if (!mutex.tryLock()) return@coroutineScope }
                else mutex.lock()
                try {
                    val source = registry.find(id)?.takeIf { it.enabled && !it.removed } ?: return@coroutineScope
                    val operationJob = currentCoroutineContext()[Job]!!
                    val accepted = synchronized(monitor) {
                        if (id in lifecycleIntents) false else { jobs[id] = operationJob; true }
                    }
                    if (!accepted) return@coroutineScope
                    progress(id, phase)
                    try {
                        withContext(ExtensionSourceRuntimeCancellation.job.asContextElement(operationJob)) {
                            action(source)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IOException) {
                        registry.update(id) { it.copy(failure = ExtensionSourceFailure.NETWORK) }
                    } catch (classified: SourceOperationFailure) {
                        registry.update(id) { it.copy(failure = classified.classification) }
                    } catch (_: Exception) {
                        registry.update(id) { it.copy(failure = failureClassification) }
                    } finally {
                        synchronized(monitor) { jobs.remove(id); phases.remove(id) }
                        publish()
                    }
                } finally { mutex.unlock() }
            }
        }

    private fun lock(id: String): Mutex = synchronized(monitor) { locks.getOrPut(id) { Mutex() } }
    private fun fence(source: RegisteredExtensionSource) {
        val current = registry.find(source.id)
        require(current != null && current.enabled && !current.removed && current.epoch == source.epoch) { "source lifecycle changed" }
        require(synchronized(monitor) { source.id !in lifecycleIntents }) { "source lifecycle change pending" }
    }

    private fun publish() = synchronized(publicationLock) {
        mutableSources.value = registry.all().filterNot { it.removed }.map { source ->
            val store = synchronized(monitor) { stores[source.id] }
            val snapshot = runCatching { store?.snapshot() }.getOrNull()
            val effectiveTime = maxOf(clock.instant(), snapshot?.acceptedClock ?: clock.instant())
            val currentRoot = snapshot?.root
            val currentIndex = snapshot?.index
            val fresh = currentRoot != null && currentIndex != null &&
                currentRoot.expiresAt.isAfter(effectiveTime) && currentIndex.expiresAt.isAfter(effectiveTime) &&
                currentIndex.rootVersion == currentRoot.version
            val catalog = snapshot?.index?.packages.orEmpty().groupBy { it.binding.extensionId }
            val identities = catalog.keys + snapshot?.generations.orEmpty().keys
            val extensions = identities.mapNotNull { extensionId ->
                val installedBinding = store?.installedBinding(extensionId)
                val entries = catalog[extensionId].orEmpty()
                val entry = entries.maxByOrNull { it.binding.releaseSequence }
                val binding = installedBinding ?: entry?.binding ?: return@mapNotNull null
                val generation = snapshot?.generations?.get(extensionId)
                val active = generation?.active
                val displayed = generation?.knownGood ?: generation?.lastRejected
                val inspected = store?.inspectInstalled(extensionId)
                val verified = inspected?.takeIf { it.packageDigest == generation?.knownGood?.digest &&
                    it.packageGeneration == generation?.packageGeneration }
                val root = snapshot?.root
                val candidate = entry?.binding
                val scope = root?.publishers?.singleOrNull {
                    candidate != null && it.publisherId == candidate.publisherId && it.extensionId == candidate.extensionId &&
                        it.providerId == candidate.providerId && it.keyId == candidate.keyId &&
                        effectiveTime >= it.notBefore && effectiveTime < it.expiresAt
                }
                val candidateRevoked = entry?.revoked == true || candidate?.archiveSha256 in snapshot?.revokedDigests.orEmpty() ||
                    candidate?.keyId in root?.revokedKeys.orEmpty() || candidate != null && candidate.keyId !in root?.keys.orEmpty()
                val candidateQuarantined = candidate?.archiveSha256 in snapshot?.quarantinedDigests.orEmpty()
                val authorized = candidate != null && scope != null && scope.navigation.containsAll(candidate.navigationCapabilities) &&
                    candidate.providerId == binding.providerId && candidate.publisherId == binding.publisherId
                val allowed = source.enabled && fresh && source.failure == null && authorized && !candidateRevoked &&
                    !candidateQuarantined && candidate?.yanked != true && runtimeSupported
                val usable = generation?.knownGood != null && verified != null && source.enabled && runtimeSupported
                val packageStatus = when {
                    displayed == null -> InstalledPackageStatus.NOT_INSTALLED
                    displayed.digest in snapshot?.revokedDigests.orEmpty() || displayed.key in root?.revokedKeys.orEmpty() -> InstalledPackageStatus.REVOKED
                    displayed.digest in snapshot?.quarantinedDigests.orEmpty() -> InstalledPackageStatus.QUARANTINED
                    usable -> InstalledPackageStatus.USABLE
                    else -> InstalledPackageStatus.UNUSABLE
                }
                val update = displayed != null && allowed && candidate!!.releaseSequence > (snapshot?.releaseHigh?.get(extensionId) ?: 0)
                val previous = store?.safePreviousGood(extensionId, clock.instant())
                val operation = snapshot?.operations?.get(extensionId)
                val phase = synchronized(monitor) { phases[source.id] }
                val updateState = phase ?: when {
                    packageStatus == InstalledPackageStatus.REVOKED -> ExtensionUpdateState.REVOKED
                    packageStatus == InstalledPackageStatus.QUARANTINED -> ExtensionUpdateState.QUARANTINED
                    displayed != null && !usable -> ExtensionUpdateState.UNUSABLE
                    source.failure == ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE -> ExtensionUpdateState.TRUST_UNAVAILABLE
                    source.failure == ExtensionSourceFailure.NETWORK || source.failure == ExtensionSourceFailure.INVALID_METADATA -> ExtensionUpdateState.UPDATE_FAILED
                    update -> ExtensionUpdateState.UPDATE_AVAILABLE
                    operation?.failure != null -> ExtensionUpdateState.UPDATE_FAILED
                    operation?.state == ExtensionUpdateState.UPDATED -> ExtensionUpdateState.UPDATED
                    operation?.state == ExtensionUpdateState.ROLLED_BACK -> ExtensionUpdateState.ROLLED_BACK
                    displayed == null -> ExtensionUpdateState.NOT_INSTALLED
                    previous != null -> ExtensionUpdateState.ROLLBACK_AVAILABLE
                    else -> ExtensionUpdateState.INSTALLED_CURRENT
                }
                SourceExtension(extensionId, verified?.displayName ?: binding.displayName, candidate?.version ?: binding.version,
                    candidate?.archiveSha256 ?: binding.archiveSha256, candidate?.releaseSequence ?: binding.releaseSequence,
                    (verified?.let { it.grantedRoles.map { role -> role.name } + it.navigationCapabilities.map { cap -> cap.name } }
                        ?: (scope?.roles?.map { it.name }.orEmpty() + binding.navigationCapabilities.map { it.name })).distinct().sorted(),
                    displayed?.version?.takeIf(String::isNotEmpty), displayed?.digest, update, candidateRevoked, allowed,
                    binding.providerId, binding.publisherId, installedUsable = usable, installedStatus = packageStatus,
                    updateState = updateState, latestAvailableVersion = candidate?.version?.takeIf { fresh && !candidateRevoked &&
                        !candidateQuarantined && candidate.yanked != true && authorized }, metadataFresh = fresh,
                    candidateYanked = candidate?.yanked == true, packageGeneration = generation?.packageGeneration ?: 0,
                    rollbackTarget = previous?.let { ExtensionRollbackTarget(it.version, it.digest) },
                    lastUpdateAt = operation?.completedAt, lastUpdateResult = operation?.state?.name,
                    lastUpdateFailure = operation?.failure, lastUpdateTechnicalCode = operation?.technicalCode,
                    installedReleaseSequence = displayed?.sequence)
            }.sortedBy { it.extensionId }
            val status = when {
                !source.enabled -> ExtensionSourceStatus.DISABLED
                source.failure == ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE -> ExtensionSourceStatus.TRUST_UNAVAILABLE
                source.failure != null -> ExtensionSourceStatus.ERROR
                store == null && source.attemptedAt == null -> ExtensionSourceStatus.ADDED
                store == null -> ExtensionSourceStatus.TRUST_UNAVAILABLE
                snapshot == null || !fresh -> ExtensionSourceStatus.ERROR
                extensions.any { it.installedStatus == InstalledPackageStatus.REVOKED ||
                    it.revoked && !it.installedUsable } -> ExtensionSourceStatus.REVOKED
                extensions.any { it.updateAvailable } -> ExtensionSourceStatus.UPDATE_AVAILABLE
                snapshot?.index != null -> ExtensionSourceStatus.CURRENT
                else -> ExtensionSourceStatus.ADDED
            }
            ExtensionSource(source.id, source.address.url, source.address.origin, source.enabled, status,
                snapshot?.root?.version, snapshot?.root?.digest, snapshot?.index?.sequence, snapshot?.index?.digest,
                source.attemptedAt, source.succeededAt, source.failure, extensions)
        }
    }

    private class SourceOperationFailure(val classification: ExtensionSourceFailure, cause: Exception) : Exception(cause)
}

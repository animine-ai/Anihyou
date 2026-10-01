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
}
internal object UnavailableExtensionSourceTrustBootstrap : ExtensionSourceTrustBootstrap {
    override suspend fun authenticate(source: NormalizedExtensionSource): AuthenticatedExtensionSourceAnchor? = null
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
    private var lifecycleToken = 0L
    private val mutableSources = MutableStateFlow<List<ExtensionSource>>(emptyList())
    override val sources: StateFlow<List<ExtensionSource>> = mutableSources.asStateFlow()
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
        val store = synchronized(monitor) { stores[key.sourceId] } ?: return@withContext null
        store.loadUsableExtension(key.extensionId)?.takeIf {
            it.extensionId.value == key.extensionId && it.providerId.value == key.providerId && it.publisherId == key.publisherId
        }
    }

    override suspend fun <T> withCurrentPackage(key: ExtensionSelectionKey, digest: String, block: suspend () -> T): T? =
        lock(key.sourceId).withLock {
            if (loadInstalled(key)?.packageDigest == digest) block() else null
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
            val source = sources.value.singleOrNull { it.id == key.sourceId } ?: return@withLock emptyMap()
            val store = synchronized(monitor) { stores[key.sourceId] } ?: return@withLock emptyMap()
            val verified = store.loadUsableExtension(key.extensionId)
            val snapshot = store.snapshot()
            val generation = snapshot.generations[key.extensionId]
            val catalog = snapshot.index?.packages?.filter { it.binding.extensionId == key.extensionId &&
                it.binding.providerId == key.providerId && it.binding.publisherId == key.publisherId }
                ?.maxByOrNull { it.binding.releaseSequence }?.binding
            val receipt = generation?.active
            val publisher = snapshot.root?.publishers?.singleOrNull { it.extensionId == key.extensionId &&
                it.providerId == key.providerId && it.publisherId == key.publisherId &&
                it.keyId == (receipt?.key ?: catalog?.keyId) }
            buildMap {
                put("Extension ID", key.extensionId); put("Provider ID", key.providerId)
                put("Publisher", key.publisherId); put("Repository", source.origin)
                put("Trust status", source.status.name)
                put("Version", generation?.active?.version ?: source.extensions.firstOrNull { it.extensionId == key.extensionId }?.installedVersion.orEmpty())
                // Authenticated public metadata remains inspectable after revocation, without
                // loading unusable WASM or granting the catalog any execution authority.
                put("Key ID", verified?.signingKeyId ?: receipt?.key ?: catalog?.keyId.orEmpty())
                put("Signed displayName", verified?.displayName ?: catalog?.displayName.orEmpty())
                put("Package SHA", verified?.packageDigest ?: receipt?.digest ?: catalog?.archiveSha256.orEmpty())
                put("WASM SHA", verified?.moduleDigest.orEmpty())
                put("Active package generation", generation?.active?.digest.orEmpty())
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
        val selected = productPolicy.policy.value.activeReleaseSource
        if (selected?.sourceId == sourceId && loadInstalled(selected) == null) productPolicy.invalidateSource(sourceId)
    }

    override suspend fun refreshEnabled(): Boolean {
        registry.all().filter { it.enabled && !it.removed }.forEach { refresh(it.id) }
        return registry.all().any { it.enabled && !it.removed && it.failure == ExtensionSourceFailure.NETWORK }
    }

    override suspend fun activate(sourceId: String, extensionId: String) = operate(sourceId, deduplicate = false) { source ->
        val store = refreshMetadata(source) ?: return@operate
        if (!runtimeSupported) {
            registry.update(source.id) { it.copy(failure = ExtensionSourceFailure.UNSUPPORTED_RUNTIME) }
            return@operate
        }
        val snapshot = store.snapshot()
        val candidate = snapshot.index?.packages?.filter { it.binding.extensionId == extensionId }
            ?.maxByOrNull { it.binding.releaseSequence } ?: error("no eligible signed extension")
        require(!candidate.revoked && !candidate.binding.yanked) { "latest signed extension revoked or yanked" }
        val installed = snapshot.generations[extensionId]?.active
        if (installed?.digest == candidate.binding.archiveSha256) {
            require(store.loadUsableExtension(extensionId) != null) { "installed package unusable" }
            return@operate
        }
        val reinstallRemoved = store.canReinstallRemoved(extensionId, candidate.binding.archiveSha256, candidate.binding.releaseSequence)
        require(candidate.binding.releaseSequence > (snapshot.releaseHigh[extensionId] ?: 0) || reinstallRemoved) { "release replay" }
        require(candidate.binding.archiveSha256 !in snapshot.revokedDigests &&
            candidate.binding.archiveSha256 !in snapshot.quarantinedDigests) { "package revoked or quarantined" }
        val anchor = synchronized(monitor) { anchors.getValue(source.id) }
        val bytes = transport.fetch(candidate.url, anchor.pin.distributionOrigins, 8 * 1024 * 1024)
        currentCoroutineContext().ensureActive()
        fence(source)
        val staged = File.createTempFile("download-", ".arex", directory)
        val operationJob = currentCoroutineContext()[Job]!!
        try {
            staged.writeBytes(bytes)
            // The store verifies all bytes and runs the isolated runtime smoke before atomic activation.
            store.install(staged, extensionId, clock.instant(), reinstallRemoved = reinstallRemoved, beforeActivation = {
                operationJob.ensureActive()
                fence(source)
            })
            operationJob.ensureActive()
            fence(source)
            store.promoteHealthy(extensionId, clock.instant(), beforePromotion = {
                operationJob.ensureActive()
                fence(source)
            })
        } catch (error: Exception) {
            if (store.snapshot().generations[extensionId]?.active?.digest == candidate.binding.archiveSha256) {
                store.quarantineAndRollback(extensionId, clock.instant())
            }
            throw error
        } finally { staged.delete() }
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
        val store = synchronized(monitor) {
            val previous = anchors[source.id]
            require(previous == null || previous == anchor) { "authenticated anchor changed" }
            anchors[source.id] = anchor
            stores.getOrPut(source.id) {
                try { storeFactory.create(File(directory, source.id), anchor) }
                catch (invalid: Exception) { throw SourceOperationFailure(ExtensionSourceFailure.STORAGE, invalid) }
            }
        }
        val root = transport.fetch(source.address.url + "/root.json", anchor.pin.distributionOrigins, 65536)
        currentCoroutineContext().ensureActive()
        fence(source)
        store.acceptRoot(root, clock.instant())
        val index = transport.fetch(source.address.url + "/index.json", anchor.pin.distributionOrigins, 262144)
        currentCoroutineContext().ensureActive()
        fence(source)
        store.acceptIndex(index, clock.instant())
        registry.update(source.id) { it.copy(succeededAt = clock.instant(), failure = null) }
        return store
    }

    private suspend fun operate(id: String, deduplicate: Boolean, action: suspend (RegisteredExtensionSource) -> Unit) =
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
                        registry.update(id) { it.copy(failure = if (deduplicate) ExtensionSourceFailure.INVALID_METADATA else ExtensionSourceFailure.INVALID_PACKAGE) }
                    } finally {
                        synchronized(monitor) { jobs.remove(id) }
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
            val extensions = snapshot?.index?.packages.orEmpty().groupBy { it.binding.extensionId }.values.map { entries ->
                val entry = entries.maxBy { it.binding.releaseSequence }
                val binding = entry.binding
                val active = snapshot?.generations?.get(binding.extensionId)?.active
                val root = snapshot!!.root
                val scope = root?.publishers?.singleOrNull {
                    it.publisherId == binding.publisherId && it.extensionId == binding.extensionId &&
                        it.providerId == binding.providerId && it.keyId == binding.keyId &&
                        effectiveTime >= it.notBefore && effectiveTime < it.expiresAt
                }
                val revoked = entry.revoked || binding.yanked || binding.archiveSha256 in snapshot!!.revokedDigests ||
                    binding.archiveSha256 in snapshot.quarantinedDigests ||
                    binding.keyId in root?.revokedKeys.orEmpty() || binding.keyId !in root?.keys.orEmpty()
                val authorized = scope != null && scope.navigation.containsAll(binding.navigationCapabilities)
                SourceExtension(binding.extensionId, binding.displayName, binding.version, binding.archiveSha256,
                    binding.releaseSequence, (scope?.roles?.map { it.name }.orEmpty() + binding.navigationCapabilities.map { it.name }).distinct().sorted(),
                    active?.version?.takeIf(String::isNotEmpty), active?.digest,
                    active != null && fresh && binding.releaseSequence > (snapshot.releaseHigh[binding.extensionId] ?: 0) && !revoked,
                    revoked, source.enabled && fresh && authorized && source.failure == null && !revoked && runtimeSupported,
                    binding.providerId, binding.publisherId)
            }.sortedBy { it.extensionId }
            val status = when {
                !source.enabled -> ExtensionSourceStatus.DISABLED
                source.failure == ExtensionSourceFailure.AUTHENTICATION_UNAVAILABLE -> ExtensionSourceStatus.TRUST_UNAVAILABLE
                source.failure != null -> ExtensionSourceStatus.ERROR
                store == null && source.attemptedAt == null -> ExtensionSourceStatus.ADDED
                store == null -> ExtensionSourceStatus.TRUST_UNAVAILABLE
                snapshot == null || !fresh -> ExtensionSourceStatus.ERROR
                extensions.any { it.revoked } || snapshot.generations.values.any {
                    it.active?.digest in snapshot.revokedDigests || it.active?.key in snapshot.root?.revokedKeys.orEmpty()
                } -> ExtensionSourceStatus.REVOKED
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

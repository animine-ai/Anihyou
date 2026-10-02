package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.source.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.*

internal enum class InstallBoundary { STAGED, VERIFIED, CONTENT_PLACED, BEFORE_ACTIVE, AFTER_ACTIVE, BEFORE_PROMOTION, AFTER_PROMOTION, QUARANTINE }
internal fun interface InstallFailureHook { fun at(boundary: InstallBoundary) }

internal data class InstallReceipt(val digest: String, val manifest: String, val extension: String,
    val provider: String, val key: String, val sequence: Long, val rootVersion: Long, val indexSequence: Long,
    val acceptedAt: Instant, val version: String = "")

internal data class ExtensionInstallOperation(
    val state: ExtensionUpdateState, val candidateDigest: String, val attemptedAt: Instant,
    val completedAt: Instant? = null, val failure: ExtensionUpdateFailure? = null,
)
internal class ExtensionSmokeException(cause: Exception) : Exception("isolated runtime smoke failed", cause)

internal data class ExtensionGenerationSnapshot(
    val active: InstallReceipt?, val knownGood: InstallReceipt?,
    val previousGood: InstallReceipt?, val rollbackUsed: Boolean, val packageGeneration: Long = 0,
)

internal data class ExtensionInstallSnapshot(
    val root: TrustedRoot?, val index: TrustedIndex?,
    val generations: Map<String, ExtensionGenerationSnapshot>, val releaseHigh: Map<String, Long>,
    val revokedDigests: Set<String>, val quarantinedDigests: Set<String>, val acceptedClock: Instant,
    val operations: Map<String, ExtensionInstallOperation> = emptyMap(),
)

/** One atomic state file owns the trust high-water marks and active/LKG pointers. */
internal class ExtensionInstallStore(
    private val directory: File, private val pin: AppTrustPin,
    private val verifier: ExtensionPackageVerifier,
    private val hostRoles: Set<SourceRole>, private val hostHosts: Set<String>,
    private val policyVersion: Int, private val runtimeVersion: String,
    private val smoke: (VerifiedExtensionPackage) -> Unit,
    private val failure: InstallFailureHook = InstallFailureHook { },
) : VerifiedExtensionRepository {
    private val trust = ExtensionTrustVerifier(pin)
    private val staging = File(directory, "staging")
    private val content = File(directory, "content")
    private val stateFile = File(directory, "state.json")
    private companion object { val processLock = Any() }
    private var state: State

    init {
        require(directory.isDirectory || directory.mkdirs())
        require(staging.isDirectory || staging.mkdir())
        require(content.isDirectory || content.mkdir())
        state = if (stateFile.exists()) decode(stateFile.readBytes()) else State()
        staging.listFiles()?.forEach { require(!it.isDirectory); it.delete() }
    }

    fun acceptRoot(envelope: ByteArray, now: Instant) = serialized {
        val acceptedRoots = roots(state)
        val current = acceptedRoots.lastOrNull()
        val effective = effectiveTime(now)
        val signed = (ExtensionWireCodec.parseStrictJson(envelope, 65536) as JsonObject)["signed"] as JsonObject
        val incomingVersion = (signed["version"] as JsonPrimitive).long
        val unchangedVersion = current != null && incomingVersion == current.version
        // Authenticate an unchanged root with the same predecessor as its original acceptance.
        // Neither cached digest equality nor a remotely supplied key can replace real signatures.
        val next = trust.root(envelope,
            if (unchangedVersion) acceptedRoots.dropLast(1).lastOrNull() else current, effective)
        if (unchangedVersion) require(next.digest == current!!.digest) { "root version equivocation" }
        else require(state.roots.size < 16)
        val updated = state.copy(roots = if (unchangedVersion) state.roots else
                state.roots + SignedRecord(envelope.copyOf(), effective),
            revoked = state.revoked + next.revokedDigests, clock = effective)
        commit(updated)
        next
    }

    fun acceptIndex(envelope: ByteArray, now: Instant) = serialized {
        val root = roots(state).lastOrNull() ?: error("no pinned root")
        val previous = latestIndex(state)
        val acceptedAt = effectiveTime(now)
        val next = trust.index(envelope, root, previous, acceptedAt)
        val revoked = state.revoked + next.packages.filter { it.revoked }.map { it.binding.archiveSha256 }
        val indexes = if (previous?.sequence == next.sequence) state.indexes else
            state.indexes + SignedRecord(envelope.copyOf(), acceptedAt)
        val updated = state.copy(indexes = retainReferencedIndexes(indexes, state.generations),
            indexHigh = maxOf(state.indexHigh, next.sequence), indexDigest = next.digest,
            revoked = revoked, clock = acceptedAt)
        commit(updated)
        next
    }

    /** Callbacks fence source lifecycle/cancellation and must not re-enter this store. */
    fun install(source: File, extensionId: String, now: Instant,
        reinstallRemoved: Boolean = false, beforeActivation: () -> Unit = {},
        onProgress: (ExtensionUpdateState) -> Unit = {}): InstallReceipt = serialized {
        val root = roots(state).lastOrNull() ?: error("no trusted root")
        val current = latestIndex(state) ?: error("no signed index")
        val effective = effectiveTime(now)
        require(effective < root.expiresAt && effective < current.expiresAt && current.sequence == state.indexHigh && current.rootVersion == root.version)
        val candidate = current.packages.filter { it.binding.extensionId == extensionId && !it.revoked && !it.binding.yanked }
            .maxByOrNull { it.binding.releaseSequence } ?: error("no eligible catalog entry")
        val binding = candidate.binding
        require(binding.archiveSha256 !in state.revoked && binding.archiveSha256 !in state.quarantine)
        val removed = state.removed[extensionId]
        val exactRemoved = reinstallRemoved && extensionId !in state.generations && removed != null &&
            removed.sequence == state.releaseHigh[extensionId] && removed.sequence == binding.releaseSequence &&
            removed.digest == binding.archiveSha256 && removed.manifest == binding.canonicalManifestSha256 &&
            removed.provider == binding.providerId && removed.key == binding.keyId
        require(binding.releaseSequence > (state.releaseHigh[extensionId] ?: 0L) || exactRemoved)
        require(source.isFile && source.length() in 1..(8L * 1024 * 1024))
        val temp = File.createTempFile("arex-", ".staging", staging)
        try {
            FileInputStream(source).use { input -> FileOutputStream(temp).use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 8L * 1024 * 1024) { "staging limit exceeded" }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            } }
            failure.at(InstallBoundary.STAGED)
            onProgress(ExtensionUpdateState.VERIFYING)
            val packageKey = trust.publisher(root, candidate, effective)
            val verified = verifier.verify(temp, binding, packageKey, hostRoles, hostHosts,
                policyVersion, runtimeVersion, effective)
            failure.at(InstallBoundary.VERIFIED)
            try { smoke(verified) } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                // Only a fully signature/binding-verified candidate can be quarantined by smoke.
                commit(state.copy(quarantine = state.quarantine + binding.archiveSha256, clock = effective))
                throw ExtensionSmokeException(error)
            }
            val immutable = archive(binding.archiveSha256)
            if (immutable.exists()) require(immutable.length() == binding.archiveBytes && digest(immutable) == binding.archiveSha256)
            else {
                Files.move(temp.toPath(), immutable.toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(content)
            }
            failure.at(InstallBoundary.CONTENT_PLACED)
            val receipt = InstallReceipt(binding.archiveSha256, binding.canonicalManifestSha256,
                binding.extensionId, binding.providerId, binding.keyId, binding.releaseSequence,
                root.version, current.sequence, effective, binding.version)
            onProgress(ExtensionUpdateState.ACTIVATING)
            failure.at(InstallBoundary.BEFORE_ACTIVE)
            val generation = state.generations[extensionId] ?: GenerationState()
            beforeActivation()
            commit(state.copy(generations = state.generations +
                (extensionId to generation.copy(active = receipt, rollbackUsed = false)),
                releaseHigh = state.releaseHigh + (extensionId to binding.releaseSequence),
                removed = state.removed - extensionId,
                revisions = state.revisions + (extensionId to Math.addExact(state.revisions[extensionId] ?: 0, 1)), clock = effective))
            failure.at(InstallBoundary.AFTER_ACTIVE)
            receipt
        } finally { temp.delete() }
    }

    /** Called only after post-activation health checks for all required roles. */
    fun promoteHealthy(extensionId: String, now: Instant, beforePromotion: () -> Unit = {}) = serialized {
        promote(extensionId, now, beforePromotion)
    }

    /** Compatibility for the original single-extension host. Ambiguity never chooses a slot. */
    fun promoteHealthy(now: Instant) = serialized {
        promote(singleExtension(), now, {})
    }

    private fun promote(extensionId: String, now: Instant, beforePromotion: () -> Unit) {
        val generation = state.generations[extensionId] ?: error("no installed extension")
        val active = generation.active ?: error("no active generation")
        require(eligible(active))
        failure.at(InstallBoundary.BEFORE_PROMOTION)
        val next = if (generation.knownGood?.digest == active.digest) generation else
            generation.copy(knownGood = active, previousGood = generation.knownGood)
        beforePromotion()
        val generations = state.generations + (extensionId to next)
        commit(state.copy(generations = generations,
            indexes = retainReferencedIndexes(state.indexes, generations), clock = effectiveTime(now)))
        failure.at(InstallBoundary.AFTER_PROMOTION)
    }

    fun quarantineAndRollback(extensionId: String, now: Instant): InstallReceipt? = serialized {
        rollbackBad(extensionId, now)
    }

    fun quarantineAndRollback(now: Instant): InstallReceipt? = serialized {
        rollbackBad(singleExtension(), now)
    }

    private fun singleExtension(): String {
        require(state.generations.size == 1) { "explicit extension identity required" }
        return state.generations.keys.single()
    }

    private fun rollbackBad(extensionId: String, now: Instant): InstallReceipt? {
        val generation = state.generations[extensionId] ?: return null
        val bad = generation.active ?: return null
        val newlyQuarantined = state.quarantine + bad.digest
        val prior = if (generation.knownGood?.digest == bad.digest) generation.previousGood else generation.knownGood
        val fallback = prior?.takeIf { !generation.rollbackUsed && it.digest != bad.digest && eligible(it, newlyQuarantined) && verifiedReceipt(it) != null }
        val updated = generation.copy(active = fallback, knownGood = fallback, previousGood = null, rollbackUsed = true)
        val generations = state.generations + (extensionId to updated)
        commit(state.copy(generations = generations,
            indexes = retainReferencedIndexes(state.indexes, generations),
            quarantine = newlyQuarantined,
            revisions = state.revisions + (extensionId to Math.addExact(state.revisions[extensionId] ?: 0, 1)), clock = effectiveTime(now)))
        failure.at(InstallBoundary.QUARANTINE)
        return fallback
    }

    /** Read-only availability uses exactly the same eligibility rule as recovery. */
    fun safePreviousGood(extensionId: String): InstallReceipt? = serialized {
        previousGood(extensionId)?.takeIf { verifiedReceipt(it) != null }
    }

    private fun previousGood(extensionId: String): InstallReceipt? {
        val generation = state.generations[extensionId] ?: return null
        val active = generation.active ?: return null
        return generation.previousGood?.takeIf {
            !generation.rollbackUsed && generation.knownGood?.digest == active.digest &&
                it.digest != active.digest && it.extension == active.extension && it.provider == active.provider &&
                eligible(it) && receiptPublisher(it) == receiptPublisher(active)
        }
    }
    private fun receiptPublisher(receipt: InstallReceipt): String? =
        indexAt(state, receipt.indexSequence)?.packages?.singleOrNull { it.binding.archiveSha256 == receipt.digest }?.binding?.publisherId

    private fun verifiedReceipt(receipt: InstallReceipt): VerifiedExtensionPackage? = runCatching {
        if (!eligible(receipt)) return@runCatching null
        val root = roots(state).last()
        val entry = indexAt(state, receipt.indexSequence)!!.packages.single { it.binding.archiveSha256 == receipt.digest }
        verifier.verify(archive(receipt.digest), entry.binding, trust.publisher(root, entry, receipt.acceptedAt),
            hostRoles, hostHosts, policyVersion, runtimeVersion, receipt.acceptedAt)
    }.getOrNull()

    /** Explicit rollback consumes Previous Good; it never resets release high-water or quarantines healthy current. */
    fun rollback(extensionId: String, expectedGeneration: Long, targetDigest: String, now: Instant,
        beforeActivation: () -> Unit = {}): InstallReceipt = serialized {
        require((state.revisions[extensionId] ?: 0) == expectedGeneration) { "stale rollback confirmation" }
        val target = previousGood(extensionId)?.takeIf { it.digest == targetDigest } ?: error("no safe previous good")
        val verified = verifiedReceipt(target) ?: error("previous good verification failed")
        try { smoke(verified) } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            commit(state.copy(quarantine = state.quarantine + target.digest, clock = effectiveTime(now)))
            throw ExtensionSmokeException(error)
        }
        beforeActivation()
        val old = state.generations.getValue(extensionId)
        val generations = state.generations + (extensionId to old.copy(active = target, knownGood = target,
            previousGood = null, rollbackUsed = true))
        commit(state.copy(generations = generations, indexes = retainReferencedIndexes(state.indexes, generations),
            revisions = state.revisions + (extensionId to Math.addExact(expectedGeneration, 1)), clock = effectiveTime(now)))
        target
    }

    fun beginOperation(extensionId: String, phase: ExtensionUpdateState, candidateDigest: String, now: Instant) = serialized {
        require(candidateDigest.isEmpty() || candidateDigest.matches(Regex("[0-9a-f]{64}")))
        require(phase == ExtensionUpdateState.CHECKING || phase == ExtensionUpdateState.ROLLING_BACK)
        commit(state.copy(operations = state.operations + (extensionId to
            ExtensionInstallOperation(phase, candidateDigest, effectiveTime(now))), clock = effectiveTime(now)))
    }
    fun finishOperation(extensionId: String, result: ExtensionUpdateState, failure: ExtensionUpdateFailure?, now: Instant) = serialized {
        val operation = state.operations[extensionId] ?: return@serialized
        commit(state.copy(operations = state.operations + (extensionId to operation.copy(state = result,
            completedAt = effectiveTime(now), failure = failure)), clock = effectiveTime(now)))
    }
    /** No Compose state can promote a candidate after process death. */
    fun recoverInterrupted(now: Instant) = serialized {
        state.operations.filterValues { it.completedAt == null }.forEach { (extensionId, operation) ->
            val generation = state.generations[extensionId]
            val healthyCommit = generation?.active != null && generation.active.digest == operation.candidateDigest &&
                generation.knownGood?.digest == generation.active.digest
            if (!healthyCommit && generation?.active != null && generation.active.digest != generation.knownGood?.digest)
                rollbackBad(extensionId, now)
            val recovered = operation.copy(state = if (healthyCommit) {
                if (operation.state == ExtensionUpdateState.ROLLING_BACK) ExtensionUpdateState.ROLLED_BACK else ExtensionUpdateState.UPDATED
            } else ExtensionUpdateState.UPDATE_FAILED, completedAt = effectiveTime(now),
                failure = if (healthyCommit) null else ExtensionUpdateFailure.INTERRUPTED)
            commit(state.copy(operations = state.operations + (extensionId to recovered), clock = effectiveTime(now)))
        }
    }

    override suspend fun loadUsable(providerId: ProviderId): VerifiedExtensionPackage? = serialized {
        val matching = state.generations.filterValues { it.active?.provider == providerId.value }
        if (matching.size != 1) return@serialized null
        loadExtension(matching.keys.single())?.takeIf { it.providerId == providerId }
    }

    suspend fun loadUsableExtension(extensionId: String): VerifiedExtensionPackage? = serialized {
        loadExtension(extensionId)
    }

    /** Uninstall all executable generations while retaining monotone trust/release high-water. */
    fun removeExtension(extensionId: String) = serialized {
        val receipt = state.generations[extensionId]?.active?.takeIf { it.sequence == state.releaseHigh[extensionId] }
        val generations = state.generations - extensionId
        val removed = if (receipt == null) state.removed else state.removed + (extensionId to receipt)
        commit(state.copy(generations = generations, removed = removed,
            indexes = retainReferencedIndexes(state.indexes, generations, removed),
            operations = state.operations - extensionId,
            revisions = state.revisions + (extensionId to Math.addExact(state.revisions[extensionId] ?: 0, 1))))
    }

    /** Explicit user uninstall receipt only, never a rollback or failed activation receipt. */
    fun canReinstallRemoved(extensionId: String, digest: String, sequence: Long): Boolean = serialized {
        val receipt = state.removed[extensionId]
        extensionId !in state.generations && receipt != null && receipt.digest == digest &&
            receipt.sequence == sequence && sequence == state.releaseHigh[extensionId] &&
            digest !in state.revoked && digest !in state.quarantine
    }

    private fun loadExtension(extensionId: String): VerifiedExtensionPackage? {
        val selected = state.generations[extensionId]?.active ?: return null
        val active = if (eligible(selected)) selected else rollbackBad(extensionId, Instant.now()) ?: return null
        val root = roots(state).lastOrNull() ?: return null
        val signedIndex = indexAt(state, active.indexSequence) ?: return null
        val entry = signedIndex.packages.singleOrNull { it.binding.archiveSha256 == active.digest } ?: return null
        return try {
            verifier.verify(archive(active.digest), entry.binding, trust.publisher(root, entry, active.acceptedAt),
                hostRoles, hostHosts, policyVersion, runtimeVersion, active.acceptedAt).also {
                    it.packageGeneration = state.revisions[extensionId] ?: 0
                }
        } catch (_: Exception) { null }
    }

    fun installedBinding(extensionId: String): VerifiedCatalogPackageBinding? = serialized {
        val receipt = state.generations[extensionId]?.active ?: return@serialized null
        indexAt(state, receipt.indexSequence)?.packages?.singleOrNull { it.binding.archiveSha256 == receipt.digest }?.binding
    }
    fun inspectInstalled(extensionId: String): VerifiedExtensionPackage? = serialized {
        state.generations[extensionId]?.active?.let(::verifiedReceipt)?.also {
            it.packageGeneration = state.revisions[extensionId] ?: 0
        }
    }

    fun snapshot(): ExtensionInstallSnapshot = serialized {
        ExtensionInstallSnapshot(roots(state).lastOrNull(), latestIndex(state),
            state.generations.mapValues { (extensionId, generation) ->
                ExtensionGenerationSnapshot(generation.active, generation.knownGood,
                    generation.previousGood, generation.rollbackUsed, state.revisions[extensionId] ?: 0)
            }, state.releaseHigh.toMap(), state.revoked.toSet(), state.quarantine.toSet(), state.clock, state.operations.toMap())
    }

    private fun eligible(receipt: InstallReceipt, quarantine: Set<String> = state.quarantine): Boolean {
        val root = roots(state).lastOrNull() ?: return false
        val current = indexAt(state, receipt.indexSequence) ?: return false
        val entry = current.packages.singleOrNull { it.binding.archiveSha256 == receipt.digest } ?: return false
        val latest = latestIndex(state)?.packages?.singleOrNull { it.binding.archiveSha256 == receipt.digest }
        val packageFile = archive(receipt.digest)
        return receipt.digest !in quarantine && receipt.digest !in state.revoked && receipt.digest !in root.revokedDigests &&
            receipt.key !in root.revokedKeys && receipt.key in root.keys && !entry.revoked && !entry.binding.yanked &&
            latest?.revoked != true && latest?.binding?.yanked != true &&
            entry.binding.extensionId == receipt.extension && entry.binding.providerId == receipt.provider &&
            entry.binding.keyId == receipt.key && entry.binding.releaseSequence == receipt.sequence &&
            current.rootVersion == receipt.rootVersion &&
            entry.binding.canonicalManifestSha256 == receipt.manifest && packageFile.isFile &&
            digest(packageFile) == receipt.digest &&
            runCatching { trust.publisher(root, entry, receipt.acceptedAt) }.isSuccess
    }

    private fun roots(s: State): List<TrustedRoot> {
        var previous: TrustedRoot? = null
        return s.roots.map { record -> trust.root(record.bytes, previous, record.at).also { previous = it } }
    }
    private fun latestIndex(s: State): TrustedIndex? = s.indexes.lastOrNull()?.let { signedIndex(s, it) }
    private fun indexAt(s: State, sequence: Long): TrustedIndex? = s.indexes.firstNotNullOfOrNull { record ->
        signedIndex(s, record).takeIf { it.sequence == sequence }
    }
    private fun signedIndex(s: State, record: SignedRecord): TrustedIndex {
        val body = (ExtensionWireCodec.parseStrictJson(record.bytes, 262144) as JsonObject)["signed"] as JsonObject
        val version = (body["rootVersion"] as JsonPrimitive).long
        val signedRoot = roots(s).single { it.version == version }
        return trust.index(record.bytes, signedRoot, null, record.at)
    }
    /** Keep the latest catalog plus original signed bindings for every usable/rollback receipt.
     * Sequence high-water and cumulative revocations remain separate durable state, so discarded
     * catalogs cannot authorize replay or undo a revocation. The 8 MiB state cap still applies.
     */
    private fun retainReferencedIndexes(indexes: List<SignedRecord>, generations: Map<String, GenerationState>,
        removed: Map<String, InstallReceipt> = state.removed): List<SignedRecord> {
        val needed = (generations.values.flatMap { listOfNotNull(it.active, it.knownGood, it.previousGood) } + removed.values)
            .mapTo(mutableSetOf()) { it.indexSequence }
        val latest = indexes.lastOrNull() ?: return indexes
        return indexes.filter { record -> record === latest ||
            (ExtensionWireCodec.parseStrictJson(record.bytes, 262144) as JsonObject).let { envelope ->
                val signed = envelope["signed"] as JsonObject
                (signed["sequence"] as JsonPrimitive).long in needed
            }
        }
    }
    private fun effectiveTime(now: Instant) = maxOf(now, state.clock)
    private fun archive(digest: String): File {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        return File(content, "$digest.arex")
    }
    private fun <T> serialized(block: () -> T): T = synchronized(processLock) {
        RandomAccessFile(File(directory, "install.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                state = if (stateFile.exists()) decode(stateFile.readBytes()) else State()
                block()
            }
        }
    }
    private fun commit(next: State) {
        val temporary = File(directory, "state.next")
        val bytes = encode(next)
        require(bytes.size <= 8 * 1048576) { "install state exceeds durable size limit" }
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(directory)
        state = next
    }
    private fun syncDirectory(dir: File) { ExtensionFileDurability.syncDirectory(dir) }
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(8192); while (true) {
            val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count)
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SignedRecord(val bytes: ByteArray, val at: Instant)
    private data class GenerationState(
        val active: InstallReceipt? = null, val knownGood: InstallReceipt? = null,
        val previousGood: InstallReceipt? = null, val rollbackUsed: Boolean = false,
    )
    private data class State(
        val roots: List<SignedRecord> = emptyList(), val indexes: List<SignedRecord> = emptyList(),
        val indexHigh: Long = 0, val indexDigest: String = "", val releaseHigh: Map<String, Long> = emptyMap(),
        val revoked: Set<String> = emptySet(), val quarantine: Set<String> = emptySet(),
        val generations: Map<String, GenerationState> = emptyMap(),
        val removed: Map<String, InstallReceipt> = emptyMap(),
        val revisions: Map<String, Long> = emptyMap(),
        val operations: Map<String, ExtensionInstallOperation> = emptyMap(),
        val clock: Instant = Instant.EPOCH,
    )
    private fun encode(s: State): ByteArray = JsonObject(mapOf(
        "schemaVersion" to JsonPrimitive(4),
        "roots" to JsonArray(s.roots.map(::recordJson)), "indexes" to JsonArray(s.indexes.map(::recordJson)),
        "indexHigh" to JsonPrimitive(s.indexHigh), "indexDigest" to JsonPrimitive(s.indexDigest),
        "releaseHigh" to JsonObject(s.releaseHigh.mapValues { JsonPrimitive(it.value) }),
        "revoked" to JsonArray(s.revoked.sorted().map(::JsonPrimitive)),
        "quarantine" to JsonArray(s.quarantine.sorted().map(::JsonPrimitive)),
        "generations" to JsonObject(s.generations.toSortedMap().mapValues { (_, generation) ->
            JsonObject(mapOf(
                "active" to (generation.active?.let(::receiptJson) ?: JsonNull),
                "knownGood" to (generation.knownGood?.let(::receiptJson) ?: JsonNull),
                "previousGood" to (generation.previousGood?.let(::receiptJson) ?: JsonNull),
                "rollbackUsed" to JsonPrimitive(generation.rollbackUsed),
            ))
        }),
        "removed" to JsonObject(s.removed.toSortedMap().mapValues { receiptJson(it.value) }),
        "revisions" to JsonObject(s.revisions.mapValues { JsonPrimitive(it.value) }),
        "operations" to JsonObject(s.operations.mapValues { (_, operation) -> buildJsonObject {
            put("state", operation.state.name); put("candidateDigest", operation.candidateDigest)
            put("attemptedAt", operation.attemptedAt.toString())
            put("completedAt", operation.completedAt?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("failure", operation.failure?.let { JsonPrimitive(it.name) } ?: JsonNull)
        } }),
        "clock" to JsonPrimitive(s.clock.toString()),
    )).toString().toByteArray(Charsets.UTF_8)
    private fun recordJson(r: SignedRecord) = JsonObject(mapOf("bytes" to JsonPrimitive(Base64.getEncoder().encodeToString(r.bytes)), "at" to JsonPrimitive(r.at.toString())))
    private fun receiptJson(r: InstallReceipt) = JsonObject(mapOf(
        "digest" to JsonPrimitive(r.digest), "manifest" to JsonPrimitive(r.manifest), "extension" to JsonPrimitive(r.extension),
        "provider" to JsonPrimitive(r.provider), "key" to JsonPrimitive(r.key), "sequence" to JsonPrimitive(r.sequence),
        "rootVersion" to JsonPrimitive(r.rootVersion), "indexSequence" to JsonPrimitive(r.indexSequence),
        "acceptedAt" to JsonPrimitive(r.acceptedAt.toString()),
        "version" to JsonPrimitive(r.version),
    ))
    private fun decode(bytes: ByteArray): State {
        val o = ExtensionWireCodec.parseStrictJson(bytes, 8 * 1048576) as JsonObject
        fun record(v: JsonElement) = (v as JsonObject).let { SignedRecord(Base64.getDecoder().decode((it["bytes"] as JsonPrimitive).content), Instant.parse((it["at"] as JsonPrimitive).content)) }
        fun receipt(v: JsonElement?): InstallReceipt? = if (v == null || v == JsonNull) null else (v as JsonObject).let {
            InstallReceipt((it["digest"] as JsonPrimitive).content, (it["manifest"] as JsonPrimitive).content,
                (it["extension"] as JsonPrimitive).content, (it["provider"] as JsonPrimitive).content,
                (it["key"] as JsonPrimitive).content, (it["sequence"] as JsonPrimitive).long,
                (it["rootVersion"] as JsonPrimitive).long, (it["indexSequence"] as JsonPrimitive).long,
                Instant.parse((it["acceptedAt"] as JsonPrimitive).content),
                (it["version"] as? JsonPrimitive)?.content.orEmpty())
        }
        val version = (o["schemaVersion"] as? JsonPrimitive)?.int ?: 1
        require(version in 1..4) { "unsupported install state schema" }
        val commonFields = setOf("roots", "indexes", "indexHigh", "indexDigest", "releaseHigh", "revoked", "quarantine", "clock")
        val expectedFields = if (version >= 2) commonFields + setOf("schemaVersion", "generations") +
            (if (version >= 3) setOf("removed") else emptySet()) +
            (if (version == 4) setOf("revisions", "operations") else emptySet()) else
            commonFields + setOf("active", "knownGood", "previousGood", "rollbackUsed") +
                if ("schemaVersion" in o) setOf("schemaVersion") else emptySet()
        require(o.keys == expectedFields)
        val releaseHigh = (o["releaseHigh"] as JsonObject).mapValues { (it.value as JsonPrimitive).long }
        val generations = if (version >= 2) {
            (o["generations"] as JsonObject).mapValues { (_, value) ->
                val generation = value as JsonObject
                require(generation.keys == setOf("active", "knownGood", "previousGood", "rollbackUsed"))
                GenerationState(receipt(generation["active"]), receipt(generation["knownGood"]),
                    receipt(generation["previousGood"]), (generation["rollbackUsed"] as JsonPrimitive).boolean)
            }
        } else {
            val active = receipt(o["active"])
            val knownGood = receipt(o["knownGood"])
            val previousGood = receipt(o["previousGood"])
            val rollbackUsed = (o["rollbackUsed"] as JsonPrimitive).boolean
            val identities = releaseHigh.keys + listOfNotNull(active, knownGood, previousGood).map { it.extension }
            require(!rollbackUsed || identities.isNotEmpty()) { "legacy rollback state has no extension identity" }
            identities.associateWith { extensionId ->
                GenerationState(active?.takeIf { it.extension == extensionId },
                    knownGood?.takeIf { it.extension == extensionId },
                    previousGood?.takeIf { it.extension == extensionId }, rollbackUsed)
            }
        }
        val s = State(
            roots = (o["roots"] as JsonArray).map(::record), indexes = (o["indexes"] as JsonArray).map(::record),
            indexHigh = (o["indexHigh"] as JsonPrimitive).long, indexDigest = (o["indexDigest"] as JsonPrimitive).content,
            releaseHigh = releaseHigh,
            revoked = (o["revoked"] as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
            quarantine = (o["quarantine"] as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
            generations = generations,
            removed = if (version >= 3) (o["removed"] as JsonObject).mapValues { requireNotNull(receipt(it.value)) } else emptyMap(),
            revisions = if (version == 4) o.getValue("revisions").jsonObject.mapValues { it.value.jsonPrimitive.long.also { n -> require(n >= 0) } } else emptyMap(),
            operations = if (version == 4) o.getValue("operations").jsonObject.mapValues { (_, value) ->
                val operation = value.jsonObject
                require(operation.keys == setOf("state", "candidateDigest", "attemptedAt", "completedAt", "failure"))
                ExtensionInstallOperation(ExtensionUpdateState.valueOf(operation.getValue("state").jsonPrimitive.content),
                    operation.getValue("candidateDigest").jsonPrimitive.content.also { require(it.isEmpty() || it.matches(Regex("[0-9a-f]{64}"))) },
                    Instant.parse(operation.getValue("attemptedAt").jsonPrimitive.content),
                    operation["completedAt"]?.takeUnless { it == JsonNull }?.let { Instant.parse(it.jsonPrimitive.content) },
                    operation["failure"]?.takeUnless { it == JsonNull }?.let { ExtensionUpdateFailure.valueOf(it.jsonPrimitive.content) })
            } else emptyMap(),
            clock = Instant.parse((o["clock"] as JsonPrimitive).content),
        )
        // Older states may still contain a 16-record journal. New commits retain only the
        // latest catalog and those referenced by persisted generation receipts.
        require(s.roots.size <= 16 && s.indexes.size <= maxOf(16, 1 + 3 * s.generations.size + s.removed.size))
        require(s.roots.all { it.at <= s.clock } && s.indexes.all { it.at <= s.clock })
        require(s.releaseHigh.values.all { it > 0 })
        require((s.revoked + s.quarantine).all { it.matches(Regex("[0-9a-f]{64}")) })
        val root = roots(s).lastOrNull()
        val last = latestIndex(s)
        require((last?.sequence ?: 0) == s.indexHigh && (last?.digest ?: "") == s.indexDigest)
        require(root == null || s.revoked.containsAll(root.revokedDigests))
        require(s.indexes.map { signedIndex(s, it) }.zipWithNext().all { (a, b) ->
            b.sequence > a.sequence || b.sequence == a.sequence && b.digest == a.digest
        })
        require(s.indexes.flatMap { signedIndex(s, it).packages.filter(IndexedPackage::revoked).map { entry -> entry.binding.archiveSha256 } }
            .all { it in s.revoked })
        fun validated(extensionId: String, installed: InstallReceipt?): InstallReceipt? {
                require(extensionId in s.releaseHigh)
                if (installed == null) return null
                require(installed.extension == extensionId && installed.sequence <= s.releaseHigh.getValue(extensionId) &&
                    installed.acceptedAt <= s.clock)
                val acceptedIndex = indexAt(s, installed.indexSequence) ?: error("missing receipt index")
                val binding = acceptedIndex.packages.singleOrNull { it.binding.archiveSha256 == installed.digest }?.binding
                    ?: error("missing signed receipt binding")
                require(acceptedIndex.rootVersion == installed.rootVersion && binding.extensionId == installed.extension &&
                    binding.providerId == installed.provider && binding.keyId == installed.key &&
                    binding.releaseSequence == installed.sequence && binding.canonicalManifestSha256 == installed.manifest &&
                    (installed.version.isEmpty() || installed.version == binding.version))
                return installed.copy(version = binding.version)
            }
        val normalizedGenerations = s.generations.mapValues { (extensionId, generation) ->
            generation.copy(active = validated(extensionId, generation.active), knownGood = validated(extensionId, generation.knownGood),
                previousGood = validated(extensionId, generation.previousGood))
        }
        val removed = s.removed.mapValues { (extensionId, receipt) ->
            require(extensionId !in s.generations && receipt.sequence == s.releaseHigh[extensionId])
            requireNotNull(validated(extensionId, receipt))
        }
        return s.copy(generations = normalizedGenerations, removed = removed)
    }
}

/** Directory durability on Android API 21+ and on the exported JVM compatibility host. */
internal object ExtensionFileDurability {
    fun syncDirectory(directory: File) {
        if (System.getProperty("java.vm.name") != "Dalvik") {
            FileChannel.open(directory.toPath()).use { it.force(true) }
            return
        }
        // Public Android APIs are resolved reflectively because this exact source also runs
        // in the plain JVM compatibility harness. NIO's emulated FileChannel cannot open directories.
        val os = Class.forName("android.system.Os")
        val constants = Class.forName("android.system.OsConstants")
        val flags = constants.getField("O_RDONLY").getInt(null)
        val descriptor = os.getMethod("open", String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(null, directory.absolutePath, flags, 0)
        try {
            val stat = os.getMethod("fstat", java.io.FileDescriptor::class.java).invoke(null, descriptor)
            val mode = stat.javaClass.getField("st_mode").getInt(stat)
            require(constants.getMethod("S_ISDIR", Int::class.javaPrimitiveType).invoke(null, mode) == true) {
                "directory sync requires a directory descriptor"
            }
            os.getMethod("fsync", java.io.FileDescriptor::class.java).invoke(null, descriptor)
        } finally {
            os.getMethod("close", java.io.FileDescriptor::class.java).invoke(null, descriptor)
        }
    }
}

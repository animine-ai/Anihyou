package com.axiel7.anihyou.release.data.extension

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Resource keys of the soft-freshness rows; the ledger scopes them per release source and provider. */
internal object ExtensionFreshnessKeys {
    /** The last time an automatic (foreground or process start) run was started for a source. */
    const val AUTOMATIC_ATTEMPT = "AUTOMATIC_ATTEMPT"
    fun role(role: com.axiel7.anihyou.release.core.extension.SourceRole) = "ROLE:${role.name}"
    fun target(canonicalKey: String) = "TARGET:$canonicalKey"
    fun of(resource: com.axiel7.anihyou.release.core.sync.ExtensionFreshResource): String = when (resource) {
        is com.axiel7.anihyou.release.core.sync.ExtensionFreshResource.Role -> role(resource.role)
        is com.axiel7.anihyou.release.core.sync.ExtensionFreshResource.DirectTarget -> target(resource.canonicalKey)
    }
    fun source(key: com.axiel7.anihyou.release.core.source.ExtensionSelectionKey): String =
        listOf(key.sourceId, key.extensionId, key.publisherId, key.providerId).joinToString("\u0000")
}

/**
 * Soft success freshness and denial bookkeeping, kept in the same durable host ledger so that workers,
 * app start, manual refresh, updates and rollbacks all see one state. It never relaxes a hard limit.
 */
interface ExtensionFreshnessLedger {
    /** The last real network success per resource key for exactly one release source. */
    suspend fun lastSuccesses(source: String, provider: String, keys: Collection<String>): Map<String, Instant>
    suspend fun markFresh(source: String, provider: String, keys: Collection<String>, at: Instant)
    /** Per role name: when the cooldown that denied that role's requests in [generation] lapses. Empty if none was denied. */
    suspend fun deniedUntil(provider: String, generation: String): Map<String, Instant>
}

/** Durable host-owned budget. A crashed reservation remains charged for its deadline window. */
internal class FileExtensionNetworkLedger(private val directory: File) : ExtensionNetworkLedger, ExtensionFreshnessLedger {
    private val state = AtomicFile(File(directory, "extension-network-ledger-v1"))
    private val lock = File(directory, "extension-network-ledger-v1.lock")

    override suspend fun reserve(provider: String, digest: String, generation: String, role: String,
        rootUrl: String, hopUrl: String, now: Instant): ExtensionNetworkReservation? = transaction { rows ->
        prune(rows, now.epochSecond)
        val scope = hash("$provider\u0000$generation")
        val host = hash("$provider\u0000${URI(hopUrl).host}")
        // Interactive navigation has its own success freshness window. Host-level 429 and
        // concurrent/rate budgets still span packages and arbitrarily minted generations.
        val resourceScope = if (role == "NAVIGATION") "$provider\u0000NAVIGATION" else provider
        val root = hash("$resourceScope\u0000$rootUrl")
        val hop = hash("$resourceScope\u0000$hopUrl")
        val attempts = rows.filter { it.scope == scope && it.kind == 'A' }
        val roleCount = attempts.count { it.role == role }
        val active = attempts.count { it.outcome == "RESERVED" && it.at + 240 > now.epochSecond }
        val hostAttempts = rows.filter { it.kind == 'A' && it.host == host }
        // Cooldowns come from 429, failure backoff, the host key and the short success floor. Success
        // cooldowns written before the soft-freshness split (outcome "0") are ignored: they are history.
        val blocking = rows.filter { it.kind == 'C' && it.outcome != LEGACY_SUCCESS && it.key in setOf(root, hop, host) &&
            (it.scope != scope || it.key == host) && it.at > now.epochSecond }
        if (attempts.size >= 22 || active >= 2 ||
            hostAttempts.count { it.outcome == "RESERVED" && it.at + 240 > now.epochSecond } >= 2 ||
            (role == "NAVIGATION" && hostAttempts.count { it.role == "NAVIGATION" && it.at > now.epochSecond - 60 } >= 6) ||
            (role == "DIRECT" && roleCount >= 4) ||
            (role != "DIRECT" && role != "NAVIGATION" && attempts.count {
                it.role != "DIRECT" && it.role != "NAVIGATION"
            } >= 18) ||
            (role == "NAVIGATION" && roleCount >= 7) ||
            blocking.isNotEmpty() ||
            rows.any { it.kind == 'A' && it.scope == scope && it.key == root && it.role == role &&
                it.aux == root && hop == root }) {
            if (blocking.isNotEmpty()) {
                // Remember when the cooldown lapses so the caller can wait for it instead of retrying.
                val until = maxOf(blocking.maxOf { it.at },
                    rows.filter { it.kind == 'D' && it.scope == scope && it.role == role }.maxOfOrNull { it.at } ?: 0)
                rows.removeAll { it.kind == 'D' && it.scope == scope && it.role == role }
                rows += Row('D', scope, root, "", role, "", until, "DENIED", host)
            }
            return@transaction null
        }
        val token = UUID.randomUUID().toString()
        rows += Row('A', scope, root, hop, role, token, now.epochSecond, "RESERVED", host)
        ExtensionNetworkReservation(token)
    }

    override suspend fun complete(reservation: ExtensionNetworkReservation, outcome: String,
        retryAfterSeconds: Long?, now: Instant) {
        transaction { rows ->
            val attempt = rows.singleOrNull { it.kind == 'A' && it.token == reservation.token } ?: return@transaction Unit
            if (attempt.outcome != "RESERVED") return@transaction Unit
            val index = rows.indexOf(attempt)
            rows[index] = attempt.copy(outcome = outcome.take(40))
            val failure = outcome !in setOf("HTTP_2XX", "REDIRECT")
            val priorFailures = rows.filter { it.kind == 'C' && it.key == attempt.key }
                .maxByOrNull { it.at }?.outcome?.toIntOrNull() ?: 0
            val failures = if (failure) (priorFailures + 1).coerceAtMost(5) else 0
            val backoff = (1_800L * (1L shl (failures.coerceAtLeast(1) - 1))).coerceAtMost(21_600)
            val cooldown = when {
                outcome == "HTTP_429" -> maxOf(backoff, retryAfterSeconds?.coerceIn(0, 21_600) ?: 0)
                outcome == "HTTP_304" -> 1_800L
                failure -> backoff
                attempt.role == "NAVIGATION" -> 1L
                // A success only keeps a short anti-hammer floor. Freshness (1 h and 15 min windows) is soft
                // and decided by the caller; this row is the hard limit and must stay small.
                else -> SUCCESS_FLOOR_SECONDS
            }
            val keys = (listOf(attempt.key, attempt.aux) +
                if (outcome == "HTTP_429") listOf(attempt.host) else emptyList()).distinct()
            keys.forEach { key ->
                val prior = rows.filter { it.kind == 'C' && it.key == key }.maxByOrNull { it.at }
                rows.removeAll { it.kind == 'C' && it.key == key }
                val next = now.epochSecond + cooldown
                rows += Row('C', if (prior != null && prior.at >= next) prior.scope else attempt.scope,
                    key, "", "", "", maxOf(next, prior?.at ?: 0),
                    if (prior != null && prior.at >= next) prior.outcome
                    else if (failure || outcome == "HTTP_429") failures.toString() else SUCCESS_OUTCOME, "")
            }
            Unit
        }
    }

    override suspend fun lastSuccesses(source: String, provider: String, keys: Collection<String>): Map<String, Instant> =
        transaction { rows ->
            val scope = hash(source)
            val wanted = keys.associateBy { hash("$provider\u0000$it") }
            rows.filter { it.kind == 'F' && it.scope == scope && it.key in wanted }
                .associate { wanted.getValue(it.key) to Instant.ofEpochSecond(it.at) }
        }

    override suspend fun markFresh(source: String, provider: String, keys: Collection<String>, at: Instant) {
        if (keys.isEmpty()) return
        transaction { rows ->
            val scope = hash(source)
            keys.forEach { key ->
                val hashed = hash("$provider\u0000$key")
                val prior = rows.firstOrNull { it.kind == 'F' && it.scope == scope && it.key == hashed }
                rows.removeAll { it.kind == 'F' && it.scope == scope && it.key == hashed }
                // A late writer must not move a newer success backwards.
                rows += Row('F', scope, hashed, "", "", "", maxOf(at.epochSecond, prior?.at ?: 0), "OK", "")
            }
        }
    }

    override suspend fun deniedUntil(provider: String, generation: String): Map<String, Instant> = transaction { rows ->
        val scope = hash("$provider\u0000$generation")
        rows.filter { it.kind == 'D' && it.scope == scope }.groupBy { it.role }
            .mapValues { (_, denials) -> Instant.ofEpochSecond(denials.maxOf { it.at }) }
    }

    private suspend fun <T> transaction(block: (MutableList<Row>) -> T): T = PROCESS_MUTEX.withLock {
        withContext(Dispatchers.IO) {
        require(directory.isDirectory || directory.mkdirs())
        RandomAccessFile(lock, "rw").use { raf ->
            raf.channel.lock().use {
                val rows = read()
                val result = block(rows)
                write(rows)
                result
            }
        }
        }
    }

    private fun read(): MutableList<Row> {
        if (!state.baseFile.exists()) return mutableListOf()
        return DataInputStream(state.openRead()).use { input ->
            require(input.readInt() == 0x45584e32)
            val count = input.readInt()
            require(count in 0..20_000)
            MutableList(count) {
                Row(input.readChar(), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readLong(), input.readUTF(), input.readUTF())
            }
        }
    }

    private fun write(rows: List<Row>) {
        val output = state.startWrite()
        try {
            DataOutputStream(output).apply {
                writeInt(0x45584e32)
                writeInt(rows.size)
                rows.forEach { row ->
                    writeChar(row.kind.code); writeUTF(row.scope); writeUTF(row.key); writeUTF(row.aux)
                    writeUTF(row.role); writeUTF(row.token); writeLong(row.at); writeUTF(row.outcome)
                    writeUTF(row.host)
                }
                flush()
            }
            state.finishWrite(output)
        } catch (failure: Exception) {
            state.failWrite(output)
            throw failure
        }
    }

    private fun prune(rows: MutableList<Row>, now: Long) {
        rows.removeAll { row -> row.at + 86_400 < now }
        require(rows.size < 20_000) { "network ledger full" }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Row(val kind: Char, val scope: String, val key: String, val aux: String,
        val role: String, val token: String, val at: Long, val outcome: String, val host: String)

    private companion object {
        val PROCESS_MUTEX = Mutex()
        /** Hard floor between two successful fetches of the same URL. Not a freshness window. */
        const val SUCCESS_FLOOR_SECONDS = 60L
        const val SUCCESS_OUTCOME = "S"
        /** Success cooldown rows written by the 6 hour rule; ignored now. */
        const val LEGACY_SUCCESS = "0"
    }
}

/** The caller supplies an app-private directory; no cookies, credentials or ambient client exist. */
object ProductionExtensionTransportFactory {
    fun create(appPrivateDirectory: File): DestinationBoundExtensionTransport =
        ProductionExtensionHttpTransport(FileExtensionNetworkLedger(appPrivateDirectory))
}

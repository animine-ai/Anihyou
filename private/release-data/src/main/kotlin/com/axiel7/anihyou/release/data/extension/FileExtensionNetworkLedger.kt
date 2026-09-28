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
import kotlinx.coroutines.withContext

/** Durable host-owned budget. A crashed reservation remains charged for its deadline window. */
internal class FileExtensionNetworkLedger(private val directory: File) : ExtensionNetworkLedger {
    private val state = AtomicFile(File(directory, "extension-network-ledger-v1"))
    private val lock = File(directory, "extension-network-ledger-v1.lock")

    override suspend fun reserve(provider: String, digest: String, generation: String, role: String,
        rootUrl: String, hopUrl: String, now: Instant): ExtensionNetworkReservation? = transaction { rows ->
        prune(rows, now.epochSecond)
        val scope = hash("$provider\u0000$generation")
        val host = hash("$provider\u0000${URI(hopUrl).host}")
        val root = hash("$provider\u0000$rootUrl")
        val hop = hash("$provider\u0000$hopUrl")
        val attempts = rows.filter { it.scope == scope && it.kind == 'A' }
        val roleCount = attempts.count { it.role == role }
        val active = attempts.count { it.outcome == "RESERVED" && it.at + 240 > now.epochSecond }
        if (attempts.size >= 22 || active >= 2 ||
            (role == "DIRECT" && roleCount >= 4) ||
            (role != "DIRECT" && role != "NAVIGATION" && attempts.count { it.role != "DIRECT" && it.role != "NAVIGATION" } >= 3) ||
            (role == "NAVIGATION" && roleCount >= 7) ||
            rows.any { it.kind == 'C' && it.key in setOf(root, hop, host) &&
                (it.scope != scope || it.key == host) && it.at > now.epochSecond } ||
            rows.any { it.kind == 'A' && it.scope == scope && it.key == root && it.role == role &&
                it.aux == root && hop == root }) return@transaction null
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
            val cooldown = when {
                outcome == "HTTP_429" -> maxOf(60, retryAfterSeconds?.coerceIn(0, 21_600) ?: 0)
                failure -> 60L
                else -> 30L
            }
            val keys = (listOf(attempt.key, attempt.aux) +
                if (outcome == "HTTP_429") listOf(attempt.host) else emptyList()).distinct()
            keys.forEach { key ->
                rows.removeAll { it.kind == 'C' && it.key == key }
                rows += Row('C', attempt.scope, key, "", "", "", now.epochSecond + cooldown, "", "")
            }
            Unit
        }
    }

    private suspend fun <T> transaction(block: (MutableList<Row>) -> T): T = withContext(Dispatchers.IO) {
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
        rows.removeAll { row ->
            if (row.kind == 'C') row.at <= now else row.at + 86_400 < now
        }
        require(rows.size < 20_000) { "network ledger full" }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Row(val kind: Char, val scope: String, val key: String, val aux: String,
        val role: String, val token: String, val at: Long, val outcome: String, val host: String)
}

/** The caller supplies an app-private directory; no cookies, credentials or ambient client exist. */
object ProductionExtensionTransportFactory {
    fun create(appPrivateDirectory: File): DestinationBoundExtensionTransport =
        ProductionExtensionHttpTransport(FileExtensionNetworkLedger(appPrivateDirectory))
}

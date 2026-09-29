package com.axiel7.anihyou.release.data.extension

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic persistence and concurrency coverage for the durable network ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileExtensionNetworkLedgerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun restartAndFreshLogicalRootsDoNotResetScopedQuota() = runBlocking {
        val directory = temporaryFolder.newFolder("restart-reservation")
        val original = FileExtensionNetworkLedger(directory)
        val root = url("restart")

        assertNotNull(reserve(original, root = root, digest = DIGEST_A, at = NOW))

        // A fresh instance sees the persisted attempt; changing package identity does not reset it.
        val restarted = FileExtensionNetworkLedger(directory)
        assertNull(reserve(restarted, root = root, digest = DIGEST_B, at = NOW.plusSeconds(1)))

        // Attempt rows are retained for 24 hours, then pruned on the next transaction.
        assertNotNull(reserve(restarted, root = root, digest = DIGEST_B, at = NOW.plusSeconds(86_401)))

        val quotaLedger = FileExtensionNetworkLedger(temporaryFolder.newFolder("logical-root-quota"))

        repeat(18) { ordinal ->
            val logicalAttempt = requireNotNull(
                reserve(
                    quotaLedger,
                    root = url("logical-$ordinal"),
                    digest = if (ordinal % 2 == 0) DIGEST_A else DIGEST_B,
                    role = "CALENDAR",
                    at = NOW.plusSeconds(ordinal.toLong()),
                ),
            )
            assertNotNull(
                "logical request $ordinal should have a reservation",
                logicalAttempt,
            )
            quotaLedger.complete(logicalAttempt, "HTTP_2XX", null, NOW.plusSeconds(ordinal.toLong()))
        }

        // The ledger receives no request ID. Unique roots and package digests still share the same
        // provider/generation budget, so minting a new logical request cannot reset this quota.
        assertNull(
            reserve(
                quotaLedger,
                root = url("logical-fresh-id"),
                digest = "c".repeat(64),
                role = "CALENDAR",
                at = NOW.plusSeconds(19),
            ),
        )

        // A new generation gets an independent budget.
        assertNotNull(
            reserve(
                quotaLedger,
                root = url("logical-fresh-generation"),
                generation = "generation-b",
                role = "CALENDAR",
                at = NOW.plusSeconds(19),
            ),
        )
    }

    @Test
    fun cooldownRetryAfterAndStaleCompletionPreserveTheLongestDeadline() = runBlocking {
        val failureDirectory = temporaryFolder.newFolder("failure-cooldown")
        val ledger = FileExtensionNetworkLedger(failureDirectory)
        val root = url("failed")
        val reservation = requireNotNull(reserve(ledger, root = root, at = NOW))
        ledger.complete(reservation, "HTTP_500", retryAfterSeconds = null, now = NOW)

        val restarted = FileExtensionNetworkLedger(failureDirectory)
        assertNull(
            reserve(
                restarted,
                root = root,
                generation = "generation-b",
                at = NOW.plusSeconds(1_799),
            ),
        )
        assertNotNull(
            reserve(
                restarted,
                root = root,
                generation = "generation-b",
                at = NOW.plusSeconds(1_800),
            ),
        )

        val retryDirectory = temporaryFolder.newFolder("retry-after")
        val retryLedger = FileExtensionNetworkLedger(retryDirectory)
        val limitedRoot = url("rate-limited")
        val retryReservation = requireNotNull(reserve(retryLedger, root = limitedRoot, at = NOW))
        retryLedger.complete(retryReservation, "HTTP_429", retryAfterSeconds = 3_600, now = NOW)

        val differentRootOnSameHost = url("other-root")
        val restartedRetryLedger = FileExtensionNetworkLedger(retryDirectory)
        assertNull(
            reserve(
                restartedRetryLedger,
                root = differentRootOnSameHost,
                generation = "generation-b",
                at = NOW.plusSeconds(3_599),
            ),
        )
        assertNotNull(
            reserve(
                restartedRetryLedger,
                root = differentRootOnSameHost,
                generation = "generation-b",
                at = NOW.plusSeconds(3_600),
            ),
        )

        val staleDirectory = temporaryFolder.newFolder("stale-completion")
        val staleLedger = FileExtensionNetworkLedger(staleDirectory)
        val staleRoot = url("stale")
        val older = requireNotNull(reserve(staleLedger, root = staleRoot, generation = "generation-old", at = NOW))
        val newer = requireNotNull(
            reserve(staleLedger, root = staleRoot, generation = "generation-new", at = NOW.plusSeconds(1)),
        )

        staleLedger.complete(newer, "HTTP_429", retryAfterSeconds = 7_200, now = NOW.plusSeconds(10))
        // The older request finishes later with a short success cooldown.
        staleLedger.complete(older, "HTTP_2XX", retryAfterSeconds = null, now = NOW.plusSeconds(20))

        val restartedStaleLedger = FileExtensionNetworkLedger(staleDirectory)
        assertNull(
            reserve(
                restartedStaleLedger,
                root = staleRoot,
                generation = "generation-later",
                at = NOW.plusSeconds(21_619),
            ),
        )
        assertNotNull(
            reserve(
                restartedStaleLedger,
                root = staleRoot,
                generation = "generation-later",
                at = NOW.plusSeconds(21_620),
            ),
        )
    }

    @Test
    fun concurrentCallersCanHoldOnlyTwoOutstandingReservations() = runBlocking {
        val directory = temporaryFolder.newFolder("concurrent-reservations")
        val callers = 12
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var readyCount = 0

        val results = (0 until callers).map { ordinal ->
            async(Dispatchers.Default) {
                synchronized(ready) {
                    readyCount++
                    if (readyCount == callers) ready.complete(Unit)
                }
                release.await()
                runCatching {
                    reserve(
                        FileExtensionNetworkLedger(directory),
                        root = url("concurrent-$ordinal"),
                        at = NOW,
                    )
                }
            }
        }

        ready.await()
        release.complete(Unit)
        val completed = results.awaitAll()

        assertEquals("all concurrent transactions should finish without lock errors", callers,
            completed.count { it.isSuccess })
        assertEquals("the active reservation cap is shared across ledger instances", 2,
            completed.count { it.getOrNull() != null })
    }

    private suspend fun reserve(
        ledger: FileExtensionNetworkLedger,
        root: String,
        digest: String = DIGEST_A,
        generation: String = "generation-a",
        role: String = "RECENT",
        at: Instant,
    ): ExtensionNetworkReservation? = ledger.reserve(
        provider = PROVIDER,
        digest = digest,
        generation = generation,
        role = role,
        rootUrl = root,
        hopUrl = root,
        now = at,
    )

    private fun url(path: String) = "https://provider.example/$path"

    private companion object {
        const val PROVIDER = "provider.example"
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val NOW: Instant = Instant.parse("2026-09-28T12:00:00Z")
    }
}

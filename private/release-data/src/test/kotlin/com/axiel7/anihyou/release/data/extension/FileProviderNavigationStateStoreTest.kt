package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileProviderNavigationStateStoreTest {
    private val a = ExtensionSelectionKey("source-a", "example.a", "publisher-a", "provider-a")
    private val b = ExtensionSelectionKey("source-b", "example.b", "publisher-b", "provider-b")
    private fun segment(key: ExtensionSelectionKey, season: Int = 1) =
        ProviderEpisodeSegment(key, 42, "series", season, 1, if (season == 1) 1 else 13, 12)

    @Test fun numberingMetadataSurvivesRestartWithoutBecomingAReleaseOrManualMapping() = runBlocking {
        val directory = Files.createTempDirectory("numbering-metadata").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            val metadata = ProviderMediaNumbering(42, setOf("Ordinary Show"), 12)
            store.rememberNumbering(metadata)
            store.rememberNumbering(metadata.copy(episodeExtent = 13))
            val reopened = FileProviderNavigationStateStore(directory).state.value
            assertEquals(listOf(metadata.copy(episodeExtent = 13)), reopened.mediaNumbering)
            assertTrue(reopened.installments.isEmpty())
            assertTrue(reopened.segments.isEmpty())
            assertNull(reopened.rowsSource)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `statistics and navigation status are persisted and fenced by source and package`() = runBlocking {
        val directory = Files.createTempDirectory("ep06-statistics").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            val digest = "a".repeat(64)
            val statistics = mapOf("Last successful sync" to "2026-10-01T12:00:00Z", "Role health" to "CALENDAR: HEALTHY/SUCCESS")
            store.record(a, 1, digest, emptyList(), statistics, packageGeneration = 7)
            store.recordNavigation(a, digest, "READY", packageGeneration = 7)
            val restarted = FileProviderNavigationStateStore(directory)
            assertEquals(statistics, restarted.state.value.syncStatistics)
            assertEquals("READY", restarted.state.value.navigationStatus)
            assertEquals(7, restarted.state.value.packageGeneration)
            restarted.record(a, 1, digest, emptyList(), packageGeneration = 7)
            assertEquals(statistics, restarted.state.value.syncStatistics)
            restarted.recordNavigation(b, digest, "LAUNCHED", packageGeneration = 7)
            restarted.recordNavigation(a, digest, "LAUNCHED", packageGeneration = 8)
            restarted.recordNavigation(a, "b".repeat(64), "LAUNCHED", packageGeneration = 7)
            assertEquals("READY", restarted.state.value.navigationStatus)
            restarted.record(a, 1, digest, emptyList(), packageGeneration = 8)
            assertTrue(restarted.state.value.syncStatistics.isEmpty())
            assertNull(restarted.state.value.navigationStatus)
            restarted.record(b, 2, "b".repeat(64), emptyList())
            assertTrue(restarted.state.value.syncStatistics.isEmpty())
            assertNull(restarted.state.value.navigationStatus)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `row ownership moves only with a committed refresh and survives update and restart`() = runBlocking {
        val directory = Files.createTempDirectory("ep07-rows-source").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            val digestA = "a".repeat(64)
            val digestB = "b".repeat(64)
            val installment = AcceptedProviderInstallment("projection-1", "series", 1, "1", "DE_SUB")
            store.record(a, 1, digestA, listOf(installment), packageGeneration = 1, rowsCommitted = true)
            assertEquals(a, store.state.value.rowsSource)

            // A refresh of another source that only failed moves the receipt source, never the row owner.
            store.record(b, 2, digestB, emptyList(), mapOf("Last sync outcome" to "FAILED"), packageGeneration = 1)
            assertEquals(b, store.state.value.source)
            assertEquals(a, store.state.value.rowsSource)

            // Its committed refresh makes it the owner.
            store.record(b, 2, digestB, listOf(installment), packageGeneration = 1, rowsCommitted = true)
            assertEquals(b, store.state.value.rowsSource)

            // An update of the same source (new digest and generation) that fails keeps the owner.
            store.record(b, 2, "c".repeat(64), emptyList(), mapOf("Last sync outcome" to "FAILED"), packageGeneration = 2)
            assertEquals(b, store.state.value.rowsSource)

            assertEquals(b, FileProviderNavigationStateStore(directory).state.value.rowsSource)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `row ownership follows committed refreshes through A then B then A with a restart between every step`() = runBlocking {
        val directory = Files.createTempDirectory("ep07-rows-source-aba").toFile()
        try {
            val digestA = "a".repeat(64)
            val digestB = "b".repeat(64)
            val installment = AcceptedProviderInstallment("projection-1", "series", 1, "1", "DE_SUB")
            fun reopened() = FileProviderNavigationStateStore(directory)

            reopened().record(a, 1, digestA, listOf(installment), packageGeneration = 1, rowsCommitted = true)
            assertEquals(a, reopened().state.value.rowsSource)

            // B becomes active and its first run fails: the owner stays A, also after a restart.
            reopened().record(b, 2, digestB, emptyList(), mapOf("Last sync outcome" to "FAILED"), packageGeneration = 1)
            assertEquals(b, reopened().state.value.source)
            assertEquals(a, reopened().state.value.rowsSource)

            // B commits: the owner is B after a restart.
            reopened().record(b, 2, digestB, listOf(installment), packageGeneration = 1, rowsCommitted = true)
            assertEquals(b, reopened().state.value.rowsSource)

            // Back to A: its first run fails, the owner stays B, so A is not presented as the owner of B's rows.
            reopened().record(a, 3, digestA, emptyList(), mapOf("Last sync outcome" to "FAILED"), packageGeneration = 2)
            assertEquals(a, reopened().state.value.source)
            assertEquals(b, reopened().state.value.rowsSource)

            // A commits again: the owner returns to A, never both and never none.
            reopened().record(a, 3, digestA, listOf(installment), packageGeneration = 2, rowsCommitted = true)
            assertEquals(a, reopened().state.value.rowsSource)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `receipt written before row ownership existed derives the owner from accepted installments`() = runBlocking {
        val withRows = Files.createTempDirectory("ep07-rows-source-legacy-rows").toFile()
        val failureOnly = Files.createTempDirectory("ep07-rows-source-legacy-failure").toFile()
        try {
            val installment = AcceptedProviderInstallment("projection-1", "series", 1, "1", "DE_SUB")
            FileProviderNavigationStateStore(withRows).record(a, 1, "a".repeat(64), listOf(installment), rowsCommitted = true)
            FileProviderNavigationStateStore(failureOnly).record(a, 1, "a".repeat(64), emptyList())
            for (directory in listOf(withRows, failureOnly)) {
                val file = directory.resolve("navigation-state.json")
                file.writeText(Regex(",\"rowsSource\":(null|\\[[^\\]]*\\])").replace(file.readText(), ""))
                assertFalse(file.readText().contains("rowsSource"))
            }
            assertEquals(a, FileProviderNavigationStateStore(withRows).state.value.rowsSource)
            assertNull(FileProviderNavigationStateStore(failureOnly).state.value.rowsSource)
        } finally { withRows.deleteRecursively(); failureOnly.deleteRecursively() }
    }

    @Test fun `legacy receipt without package generation reopens as generation zero`() = runBlocking {
        val directory = Files.createTempDirectory("ep06-navigation-legacy-generation").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            store.record(a, 1, "a".repeat(64), emptyList())
            val file = directory.resolve("navigation-state.json")
            file.writeText(file.readText().replace(",\"packageGeneration\":0", ""))

            val reopened = FileProviderNavigationStateStore(directory)

            assertEquals(0, reopened.state.value.packageGeneration)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `late ABA receipt from an earlier package generation cannot replace the newest token`() = runBlocking {
        val directory = Files.createTempDirectory("ep07-navigation-late-aba").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            val digestV1 = "a".repeat(64)
            val digestV2 = "b".repeat(64)
            store.record(a, 1, digestV1, emptyList(), packageGeneration = 4)
            store.record(a, 2, digestV2, emptyList(), packageGeneration = 5)
            store.record(a, 3, digestV1, emptyList(), packageGeneration = 6)
            val newest = store.state.value

            val staleWrite = runCatching {
                store.record(a, 1, digestV1, emptyList(), packageGeneration = 4)
            }

            assertTrue(staleWrite.isFailure)
            assertEquals(newest, store.state.value)
            assertEquals(newest, FileProviderNavigationStateStore(directory).state.value)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `receipts never merge source generation or package while exact mappings survive restart`() = runBlocking {
        val directory = Files.createTempDirectory("ep06-navigation-store").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            store.upsertSegment(segment(a))
            store.upsertSegment(segment(a, 2))
            val factA = AcceptedProviderInstallment("projection-a", "series", 1, "1", "DE_SUB")
            val factB = AcceptedProviderInstallment("projection-b", "series", 1, "2", "DE_DUB")
            store.record(a, 1, "a".repeat(64), listOf(factA))
            store.record(a, 1, "a".repeat(64), listOf(factB))
            assertEquals(2, store.state.value.installments.size)
            store.record(b, 2, "b".repeat(64), listOf(factB))
            assertEquals(listOf(factB), store.state.value.installments)
            store.record(a, 3, "a".repeat(64), listOf(factA))
            assertEquals(listOf(factA), store.state.value.installments)
            store.record(a, 3, "c".repeat(64), emptyList())
            assertTrue(store.state.value.installments.isEmpty())
            val reopened = FileProviderNavigationStateStore(directory)
            assertEquals(store.state.value, reopened.state.value)
            assertEquals(listOf(segment(a), segment(a, 2)), reopened.state.value.segments)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `concurrent independent mapping writes are durable without lost updates`() = runBlocking {
        val directory = Files.createTempDirectory("ep06-navigation-parallel").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            coroutineScope { listOf(a, b).map { key -> async { store.upsertSegment(segment(key)) } }.awaitAll() }
            assertEquals(setOf(a, b), store.state.value.segments.map { it.key }.toSet())
            assertEquals(store.state.value, FileProviderNavigationStateStore(directory).state.value)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `contradictory route receipts remain visible for fail closed ambiguity handling`() = runBlocking {
        val directory = Files.createTempDirectory("ep06-navigation-conflict").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            val first = AcceptedProviderInstallment("projection-a", "series", 1, "1", "DE_SUB")
            val conflicting = first.copy(sourceSeason = 2)
            store.record(a, 1, "a".repeat(64), listOf(first))
            store.record(a, 1, "a".repeat(64), listOf(conflicting))
            assertEquals(listOf(first, conflicting), store.state.value.installments)
            assertEquals(store.state.value, FileProviderNavigationStateStore(directory).state.value)
        } finally { directory.deleteRecursively() }
    }
}

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

    @Test fun `statistics and navigation status are persisted and fenced by source and package`() = runBlocking {
        val directory = Files.createTempDirectory("ep06-statistics").toFile()
        try {
            val store = FileProviderNavigationStateStore(directory)
            val digest = "a".repeat(64)
            val statistics = mapOf("Last successful sync" to "2026-10-01T12:00:00Z", "Role health" to "CALENDAR: HEALTHY/SUCCESS")
            store.record(a, 1, digest, emptyList(), statistics)
            store.recordNavigation(a, digest, "READY")
            val restarted = FileProviderNavigationStateStore(directory)
            assertEquals(statistics, restarted.state.value.syncStatistics)
            assertEquals("READY", restarted.state.value.navigationStatus)
            restarted.record(a, 1, digest, emptyList())
            assertEquals(statistics, restarted.state.value.syncStatistics)
            restarted.recordNavigation(b, digest, "LAUNCHED")
            restarted.recordNavigation(a, "b".repeat(64), "LAUNCHED")
            assertEquals("READY", restarted.state.value.navigationStatus)
            restarted.record(a, 2, digest, emptyList())
            assertTrue(restarted.state.value.syncStatistics.isEmpty())
            assertNull(restarted.state.value.navigationStatus)
            restarted.record(b, 2, "b".repeat(64), emptyList())
            assertTrue(restarted.state.value.syncStatistics.isEmpty())
            assertNull(restarted.state.value.navigationStatus)
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

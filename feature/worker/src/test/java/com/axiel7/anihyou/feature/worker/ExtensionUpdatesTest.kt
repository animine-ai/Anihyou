package com.axiel7.anihyou.feature.worker

import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.ExtensionUpdateFailure
import com.axiel7.anihyou.release.core.source.SourceExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionUpdatesTest {
    private class MemoryLedger : ExtensionUpdateLedger {
        val digests = mutableSetOf<String>()
        override fun attempted(digest: String) = digest in digests
        override fun markAttempted(digest: String) { digests += digest }
    }

    private fun extension(
        updateAvailable: Boolean = true, installedDigest: String? = "old", revoked: Boolean = false, yanked: Boolean = false,
    ) = SourceExtension(
        extensionId = "de.aniworld", displayName = "AniWorld", version = "1.0.2", digest = "new", releaseSequence = 9,
        capabilities = emptyList(), installedVersion = "1.0.0", installedDigest = installedDigest,
        updateAvailable = updateAvailable, revoked = revoked, candidateYanked = yanked,
    )

    private fun source(extension: SourceExtension) = ExtensionSource(
        id = "s", url = "https://example.test/catalog", origin = "https://example.test", enabled = true,
        status = ExtensionSourceStatus.UPDATE_AVAILABLE, extensions = listOf(extension),
    )

    /** A repository whose activate installs the offered package, or leaves the old one when [succeeds] is false. */
    private fun repository(start: SourceExtension, succeeds: Boolean, recordsFailure: Boolean = true): Pair<ExtensionSourceRepository, MutableStateFlow<List<ExtensionSource>>> {
        val sources = MutableStateFlow(listOf(source(start)))
        val repository = mockk<ExtensionSourceRepository>()
        coEvery { repository.sources } returns sources
        coEvery { repository.activate("s", "de.aniworld") } coAnswers {
            if (succeeds) sources.value = listOf(source(start.copy(installedDigest = "new", installedVersion = "1.0.2", updateAvailable = false)))
            else if (recordsFailure) sources.value = listOf(source(start.copy(lastUpdateFailure = ExtensionUpdateFailure.NETWORK)))
        }
        return repository to sources
    }

    @Test fun `a newer package of an installed extension is installed once and reported`() = runBlocking {
        val (repository, _) = repository(extension(), succeeds = true)
        val ledger = MemoryLedger()
        val first = installAvailableExtensionUpdates(repository, ledger)
        assertEquals(listOf(ExtensionUpdateOutcome("AniWorld", "1.0.0", "1.0.2", installed = true)), first)
        assertTrue(installAvailableExtensionUpdates(repository, ledger).isEmpty())
        coVerify(exactly = 1) { repository.activate("s", "de.aniworld") }
    }

    @Test fun `a package that could not be installed is reported and not tried again`() = runBlocking {
        val (repository, _) = repository(extension(), succeeds = false)
        val ledger = MemoryLedger()
        val first = installAvailableExtensionUpdates(repository, ledger)
        assertEquals(1, first.size)
        assertFalse(first.single().installed)
        assertTrue(installAvailableExtensionUpdates(repository, ledger).isEmpty())
        coVerify(exactly = 1) { repository.activate("s", "de.aniworld") }
    }

    @Test fun `nothing is asked for that is not installed, current, yanked or revoked`() = runBlocking {
        listOf(
            extension(installedDigest = null), extension(updateAvailable = false), extension(yanked = true), extension(revoked = true),
        ).forEach { candidate ->
            val (repository, _) = repository(candidate, succeeds = true)
            assertTrue(installAvailableExtensionUpdates(repository, MemoryLedger()).isEmpty())
            coVerify(exactly = 0) { repository.activate(any(), any()) }
        }
    }

    @Test fun `a request the source skipped is neither reported nor remembered, and is asked for again`() = runBlocking {
        // activate leaves the extension as it was and records no failure: that is how a skipped request looks.
        val (repository, _) = repository(extension(), succeeds = false, recordsFailure = false)
        val ledger = MemoryLedger()
        val outcomes = installAvailableExtensionUpdates(repository, ledger, retryMillis = 0)
        assertEquals(1, outcomes.size)
        assertFalse(outcomes.single().attempted)
        assertFalse("not remembered", "new" in ledger.digests)
        coVerify(exactly = 3) { repository.activate("s", "de.aniworld") }
    }
}

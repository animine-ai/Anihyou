package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.AbsencePolicySnapshot
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.CycleResult
import com.axiel7.anihyou.release.core.model.CycleSourceObservation
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicy
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.ExtensionSourceFailure
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceStatus
import com.axiel7.anihyou.release.core.source.InstalledPackageStatus
import com.axiel7.anihyou.release.core.source.SourceExtension
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Transition tests for the calendar trust gate (4f52e946) against the real Room projection with rows that were
 * accepted earlier. The persisted evidence never changes in these tests; only the live trust state of the active
 * source does. Presented rows must follow the current trust state, and losing trust must not delete accepted rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomReleasePresentationCalendarTrustTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "calendar-trust-transition-test.db"
    private val observedAt = Instant.parse("2026-09-26T10:00:00Z")
    private val range = LocalDate.parse("2026-09-20")..LocalDate.parse("2026-10-03")
    private val keyA = ExtensionSelectionKey("source-a", "de.aniworld", "publisher.a", "aniworld")
    private val keyB = ExtensionSelectionKey("source-b", "de.aniworld", "publisher.b", "aniworld")

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun open() = Room.databaseBuilder(context, ReleaseDatabase::class.java, name)
        .allowMainThreadQueries().build()

    private fun forecast(): ReleaseEvidence {
        val item = ReleaseEvidence(
            "trust-forecast", ReleaseSourceType.ANIWORLD_CALENDAR,
            "https://aniworld.to/anime/stream/trust-transition", "hash-trust-forecast", "fixture",
            observedAt, observedAt, false, AniWorldSiteIdentifier("trust-transition"), 2, 4,
            Installment.Episode(1), LanguageTrack.DE_SUB, ReleaseEvidenceType.FORECAST,
            ScheduleCondition.UNKNOWN, ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        return item.copy(id = ReleaseEvidenceFingerprintV2.evidenceId(item))
    }

    private suspend fun seedAcceptedRow(db: ReleaseDatabase) {
        val item = forecast()
        val reconciliation = RoomReleaseReconciliationRepository(db)
        reconciliation.importBaseline()
        reconciliation.persistCompletedCycle(CompletedObservationCycle(
            "trust-cycle", "trust-transition", observedAt.minusSeconds(60), observedAt, AbsencePolicySnapshot(),
            listOf(CycleSourceObservation(
                "trust-cycle:source", item.sourceType, CanonicalReleaseIdentity.from(item)?.key ?: "scope",
                item.languageTrack, CycleResult.SUCCESS, SourceHealthStatus.HEALTHY,
                observedAt = observedAt, evidence = listOf(item),
            )),
        ))
        reconciliation.rebuildProjections()
    }

    private fun extension(
        key: ExtensionSelectionKey,
        usable: Boolean = true,
        status: InstalledPackageStatus = if (usable) InstalledPackageStatus.USABLE else InstalledPackageStatus.UNUSABLE,
        revoked: Boolean = false,
        candidateYanked: Boolean = false,
    ) = SourceExtension(
        extensionId = key.extensionId, displayName = "AniWorld", version = "1.0.0", digest = "digest-${key.sourceId}",
        releaseSequence = 1, capabilities = listOf("CALENDAR"), installedVersion = "1.0.0",
        installedDigest = "digest-${key.sourceId}", activationAllowed = true, providerId = key.providerId,
        publisherId = key.publisherId, installedUsable = usable, installedStatus = status, revoked = revoked,
        candidateYanked = candidateYanked,
    )

    private fun source(
        key: ExtensionSelectionKey,
        extension: SourceExtension = extension(key),
        enabled: Boolean = true,
        status: ExtensionSourceStatus = ExtensionSourceStatus.CURRENT,
        failure: ExtensionSourceFailure? = null,
    ) = ExtensionSource(
        id = key.sourceId, url = "https://${key.sourceId}.example.test/repository",
        origin = "https://${key.sourceId}.example.test", enabled = enabled, status = status,
        lastFailure = failure, extensions = listOf(extension),
    )

    private class Policy(initial: ExtensionProductPolicy) : ExtensionProductPolicyRepository {
        override val policy = MutableStateFlow(initial)
        override suspend fun selectActiveSource(key: ExtensionSelectionKey?) {
            policy.value = policy.value.copy(activeReleaseSource = key)
        }
        override suspend fun selectNavigationProvider(key: ExtensionSelectionKey?) = Unit
        override suspend fun setPreferences(key: ExtensionSelectionKey, preferences: ExtensionPreferences) = Unit
        override suspend fun invalidateSource(sourceId: String) = Unit
        override suspend fun <T> withCurrentSelection(snapshot: ExtensionProductPolicy, block: suspend () -> T): T? =
            if (policy.value == snapshot) block() else null
    }

    private class Sources(initial: List<ExtensionSource>) : ExtensionSourceRepository {
        override val sources = MutableStateFlow(initial)
        override suspend fun add(url: String): AddExtensionSourceResult = error("unused")
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = error("unused")
        override suspend fun remove(sourceId: String) = error("unused")
        override suspend fun refresh(sourceId: String) = error("unused")
        override suspend fun refreshEnabled(): Boolean = error("unused")
        override suspend fun activate(sourceId: String, extensionId: String) = error("unused")
    }

    private suspend fun presented(repository: RoomReleasePresentationRepository) =
        repository.observeCalendar(null, range).first()

    private fun repository(db: ReleaseDatabase, policy: Policy, sources: Sources) =
        RoomReleasePresentationRepository(RoomReleaseProjectionRepository(db), db, policy, sources)

    /** The product wiring: rows are presented only for the source whose refresh committed them. */
    private fun gatedRepository(db: ReleaseDatabase, policy: Policy, sources: Sources, committed: Flow<ExtensionSelectionKey?>) =
        RoomReleasePresentationRepository(RoomReleaseProjectionRepository(db), db, policy, sources, committed)

    @Test fun usableThenRevokedHidesAcceptedRowsWithoutDeletingThemAndResumesOnlyWhenTrustedAgain() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            val trusted = presented(repository)
            assertEquals("accepted row is presented while the active source is usable", 1, trusted.size)

            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false,
                status = InstalledPackageStatus.REVOKED, revoked = true)))
            assertTrue("revoked source must not present its old rows", presented(repository).isEmpty())
            assertEquals("accepted evidence stays stored internally", 1,
                db.reconciliationDao().observeNavigationProjections().first().size)

            sources.sources.value = listOf(source(keyA))
            assertEquals("a source trusted again presents the retained rows", trusted, presented(repository))
        } finally { db.close() }
    }

    @Test fun disabledAndUnusableAndQuarantinedStatesPresentNothing() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            assertEquals(1, presented(repository).size)

            sources.sources.value = listOf(source(keyA, enabled = false))
            assertTrue("disabled source", presented(repository).isEmpty())
            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false)))
            assertTrue("unusable package", presented(repository).isEmpty())
            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false,
                status = InstalledPackageStatus.QUARANTINED)))
            assertTrue("quarantined package", presented(repository).isEmpty())
            sources.sources.value = emptyList()
            assertTrue("source removed", presented(repository).isEmpty())
            assertEquals(1, db.reconciliationDao().observeNavigationProjections().first().size)
        } finally { db.close() }
    }

    @Test fun switchingTheActiveSelectionToAnUntrustedSourceNeverPresentsTheOldSourcesRows() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA), source(keyB, extension(keyB, usable = false))))
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
            val repository = repository(db, policy, sources)
            assertEquals(1, presented(repository).size)

            policy.selectActiveSource(keyB)
            assertTrue("rows of source A must not appear while source B is not usable", presented(repository).isEmpty())
            policy.selectActiveSource(keyA)
            assertEquals(1, presented(repository).size)
        } finally { db.close() }
    }

    /**
     * Canonical rows are keyed by provider identity and carry no source attribution. Two trusted sources that both
     * offer provider "aniworld" (different source id, publisher and trust history) must therefore not share the
     * presentation: B must not show what A accepted, in particular not after A lost trust.
     */
    @Test fun aSecondTrustedSourceOfTheSameProviderNeverInheritsTheFirstSourcesRowsAsItsOwn() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA), source(keyB)))
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
            val committed = MutableStateFlow<ExtensionSelectionKey?>(keyA)
            val repository = gatedRepository(db, policy, sources, committed)
            assertEquals("A committed these rows and is active", 1, presented(repository).size)

            policy.selectActiveSource(keyB)
            assertTrue("B is usable but A's rows are not B's", presented(repository).isEmpty())

            // A is revoked, B is still trusted and active: the revoked source's rows stay hidden.
            sources.sources.value = listOf(source(keyA, extension(keyA, usable = false, status = InstalledPackageStatus.REVOKED,
                revoked = true)), source(keyB))
            assertTrue(presented(repository).isEmpty())

            // B's own refresh commits: the receipt now names B and its lane is presented.
            committed.value = keyB
            assertEquals(1, presented(repository).size)

            // Going back to A does not present B's lane as A's.
            policy.selectActiveSource(keyA)
            assertTrue(presented(repository).isEmpty())
            // The persisted evidence was never touched by any of this.
            assertEquals(1, db.reconciliationDao().projectionPage(10, 0).size)
        } finally { db.close() }
    }

    @Test fun anUpdatedPackageOfTheSameSourceKeepsItsRowsPresented() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = gatedRepository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources,
                MutableStateFlow<ExtensionSelectionKey?>(keyA))
            assertEquals(1, presented(repository).size)
            // A new package digest of the same source identity (update or rollback) keeps last-known-good rows.
            sources.sources.value = listOf(source(keyA, extension(keyA).copy(installedDigest = "digest-after-update", digest = "digest-after-update")))
            assertEquals(1, presented(repository).size)
        } finally { db.close() }
    }

    @Test fun withoutAnyCommittedReceiptNoExtensionRowsArePresentedWhenTheGateIsActive() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val policy = Policy(ExtensionProductPolicy(activeReleaseSource = keyA))
            assertTrue(presented(gatedRepository(db, policy, sources, MutableStateFlow<ExtensionSelectionKey?>(null))).isEmpty())
            // Without a gate the previous behaviour is unchanged (used by contexts that have no receipt store).
            assertEquals(1, presented(repository(db, policy, sources)).size)
        } finally { db.close() }
    }

    @Test fun healthyInstalledPackageStaysPresentedWhenOnlyTheCandidateIsYankedOrTheNetworkFails() = runBlocking {
        val db = open()
        try {
            seedAcceptedRow(db)
            val sources = Sources(listOf(source(keyA)))
            val repository = repository(db, Policy(ExtensionProductPolicy(activeReleaseSource = keyA)), sources)
            assertEquals(1, presented(repository).size)

            // A yanked candidate is not a revocation of the healthy installed package (planner decision D1).
            sources.sources.value = listOf(source(keyA, extension(keyA, candidateYanked = true)))
            assertEquals(1, presented(repository).size)
            // A transient network failure is not a loss of trust either.
            sources.sources.value = listOf(source(keyA, status = ExtensionSourceStatus.ERROR,
                failure = ExtensionSourceFailure.NETWORK))
            assertEquals(1, presented(repository).size)
        } finally { db.close() }
    }
}

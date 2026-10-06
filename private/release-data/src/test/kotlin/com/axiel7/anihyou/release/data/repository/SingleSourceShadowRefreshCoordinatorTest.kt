package com.axiel7.anihyou.release.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.ShadowRefreshOutcome
import com.axiel7.anihyou.release.core.api.TriggeredShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.api.WorkScopedShadowRefreshCoordinator
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.ExtensionSource
import com.axiel7.anihyou.release.core.source.AddExtensionSourceResult
import com.axiel7.anihyou.release.core.extension.*
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.*
import com.axiel7.anihyou.release.core.state.AniWorldReleaseAuthorityReducer
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SingleSourceShadowRefreshCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: ReleaseDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ReleaseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `selection change during fresh package lookup retries rather than skipping the new source`() = runBlocking {
        val pkg = extensionPackage(SOURCE_A_KEY)
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to pkg))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        var switched = false
        val changing = object : InstalledExtensionAccess by access {
            override suspend fun loadInstalled(key: ExtensionSelectionKey): VerifiedExtensionPackage? {
                val result = access.loadInstalled(key)
                if (!switched) {
                    switched = true
                    rig.policy.selectActiveSource(SOURCE_B_KEY)
                }
                return result
            }
        }
        assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = true),
            product(rig, changing).refresh("fresh-during-switch", ExtensionRefreshTrigger.SCHEDULED_SLOT))
        assertTrue(rig.runtime.exports.isEmpty())
        assertEquals(SOURCE_B_KEY, rig.policy.policy.value.activeReleaseSource)
    }

    @Test
    fun `failed durable postponement write preserves LKG and cannot claim successful freshness`() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val presentation = FileExtensionPostponementStore(directory, database)
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime(), postponementStore = presentation)
        assertTrue(product(rig, access).refresh("persist-success", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        val original = rig.navigationStore.state.value
        // The next trigger is a slot one hour later, so every role is stale again.
        rig.clock.now = NOW.plusSeconds(3601)
        val staleReceipt = rig.navigationStore.state.value
        val lkg = presentation.snapshot.value
        // Fail the next atomic file write while leaving the previous durable file intact.
        assertTrue(File(directory, "postponements-v1.json.next").mkdir())
        val failure = runCatching { product(rig, access).refresh("persist-failure", ExtensionRefreshTrigger.SCHEDULED_SLOT) }.exceptionOrNull()
        assertTrue("durable write failure must reach the worker retry boundary", failure is java.io.IOException)
        assertEquals(staleReceipt, rig.navigationStore.state.value)
        assertEquals(lkg, presentation.snapshot.value)
        assertEquals(lkg, FileExtensionPostponementStore(directory, database).snapshot.value)
        assertTrue(database.reconciliationDao().projectionPage(256, 0).isNotEmpty())
    }

    @Test
    fun `product refresh commits accepted data then skips fresh execution across a restart`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        val first = product(rig, access).refresh("product-first", ExtensionRefreshTrigger.SCHEDULED_SLOT)
        assertTrue(first is ShadowRefreshOutcome.Committed)
        assertEquals(NOW.toString(), rig.navigationStore.state.value.syncStatistics["Last successful sync"])
        // The title the source itself reported is kept for the matching management, bound to the exact source only.
        val label = database.matchingDao().label(SOURCE_A_KEY.sourceId, SOURCE_A_KEY.extensionId,
            SOURCE_A_KEY.publisherId, SOURCE_A_KEY.providerId, "fixture-series")
        assertEquals("Fixture release", label?.title)
        assertEquals("fixture release", label?.titleNormalized)
        assertEquals(null, database.matchingDao().label(SOURCE_B_KEY.sourceId, SOURCE_B_KEY.extensionId,
            SOURCE_B_KEY.publisherId, SOURCE_B_KEY.providerId, "fixture-series"))
        val exports = rig.runtime.exports.toList()
        // A new store and a new coordinator over the same app directories see the durable freshness.
        val skipped = product(rig.copy(navigationStore = FileProviderNavigationStateStore(rig.navigationDirectory)), access)
            .refresh("product-restarted", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue(skipped is ShadowRefreshOutcome.Skipped)
        skipped as ShadowRefreshOutcome.Skipped
        assertEquals("extension-data-fresh", skipped.reason)
        // The earliest due role is RECENT, whose window is 15 minutes.
        assertEquals(NOW.plusSeconds(900), skipped.nextEligibleAt)
        assertEquals(exports, rig.runtime.exports)
        assertTrue(rig.navigationStore.state.value.installments.isNotEmpty())
    }

    @Test
    fun `a restart that reads policy, receipt and ledger from disk again skips without creating a host`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        assertTrue(product(rig, access).refresh("seed-slot", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        // Like the device proof: two hours later the start of a process refreshes the lists, then asks again.
        rig.clock.now = NOW.plusSeconds(2 * 3600)
        assertTrue(product(rig, access).refresh("stale-start", ExtensionRefreshTrigger.PROCESS_START) is ShadowRefreshOutcome.Committed)
        val again = product(rig, access).refresh("start-again", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue("the same process must skip, got $again", again is ShadowRefreshOutcome.Skipped)
        // What a new process reads from disk must equal what the old one held, piece by piece.
        val policyAfter = FileExtensionProductPolicyRepository(rig.policyDirectory) { true }.policy.value
        val receiptAfter = FileProviderNavigationStateStore(rig.navigationDirectory).state.value
        val seen = FileExtensionNetworkLedger(rig.networkDirectory).lastSuccesses(ExtensionFreshnessKeys.source(SOURCE_A_KEY), "aniworld",
            SourceRole.entries.map(ExtensionFreshnessKeys::role) + ExtensionFreshnessKeys.AUTOMATIC_ATTEMPT)
        val problems = buildList {
            if (rig.policy.policy.value != policyAfter) add("policy before=${rig.policy.policy.value} after=$policyAfter")
            if (rig.navigationStore.state.value != receiptAfter) add("receipt before=${rig.navigationStore.state.value} after=$receiptAfter")
            if (!rig.reconciliation.hasCommittedCycles(SOURCE_A_KEY)) add("no committed cycles of the source")
            if (seen[ExtensionFreshnessKeys.AUTOMATIC_ATTEMPT] != rig.clock.now) add("ledger=$seen now=${rig.clock.now}")
        }
        assertTrue("a new process must read the same state: $problems", problems.isEmpty())
        val restarted = product(rig, access, delegate = rig.restarted()).refresh("restarted-start", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue("the restarted process must skip, got $restarted", restarted is ShadowRefreshOutcome.Skipped)
    }

    @Test
    fun `soft freshness survives an update and a rollback of the same source`() = runBlocking {
        val first = extensionPackage(SOURCE_A_KEY, packageGeneration = 6)
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to first))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        assertTrue(product(rig, access).refresh("before-update", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        val requests = rig.runtime.plannedRoles.size
        // Update to a new package digest and generation, then roll back to the original digest.
        access.replace(SOURCE_A_KEY, extensionPackage(SOURCE_A_KEY, packageDigest = sha256("updated".toByteArray()), packageGeneration = 7))
        access.replace(SOURCE_A_KEY, extensionPackage(SOURCE_A_KEY, packageGeneration = 8))
        val receiptBefore = rig.navigationStore.state.value
        val after = product(rig, access).refresh("after-rollback", ExtensionRefreshTrigger.SCHEDULED_SLOT)
        assertTrue("an update or rollback must not reset freshness, got $after", after is ShadowRefreshOutcome.Skipped)
        assertEquals("no request after update and rollback", requests, rig.runtime.plannedRoles.size)
        // The receipt follows the verified package, with the same accepted data and no new network success.
        val receiptAfter = rig.navigationStore.state.value
        assertEquals(8L, receiptAfter.packageGeneration)
        assertEquals(receiptBefore.installments, receiptAfter.installments)
        assertEquals(receiptBefore.syncStatistics, receiptAfter.syncStatistics)
        assertEquals(SOURCE_A_KEY, receiptAfter.source)
    }

    @Test
    fun `an automatic trigger asks only the list roles and the slot asks every role`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        assertTrue(product(rig, access).refresh("auto-first", ExtensionRefreshTrigger.PROCESS_START) is ShadowRefreshOutcome.Committed)
        assertEquals(listOf(setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT)), rig.runtime.plannedRoles)
        // The list roles are now fresh; the slot asks for the one thing no automatic trigger asks: DIRECT.
        val slot = product(rig, access).refresh("slot-direct", ExtensionRefreshTrigger.SCHEDULED_SLOT)
        assertTrue(slot is ShadowRefreshOutcome.Committed)
        assertEquals(setOf(SourceRole.DIRECT), rig.runtime.plannedRoles.last())
    }

    @Test
    fun `only due roles are asked and roles that were not asked are never judged absent`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        assertTrue(product(rig, access).refresh("slot-1", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        // 20 minutes later RECENT and DIRECT (15 min windows) are due; CALENDAR and POSTPONEMENT (1 h) are not.
        rig.clock.now = NOW.plusSeconds(20 * 60)
        val second = product(rig, access).refresh("slot-2", ExtensionRefreshTrigger.SCHEDULED_SLOT)
        assertTrue(second is ShadowRefreshOutcome.Committed)
        second as ShadowRefreshOutcome.Committed
        assertEquals(setOf(SourceRole.RECENT, SourceRole.DIRECT), rig.runtime.plannedRoles.last())
        assertEquals(setOf(ReleaseSourceType.ANIWORLD_RECENT, ReleaseSourceType.ANIWORLD_DIRECT_PAGE),
            second.cycle.sources.map { it.sourceType }.toSet())
        assertEquals(setOf(SourceRole.RECENT, SourceRole.DIRECT), second.successfulRoles)
    }

    @Test
    fun `a manual refresh bypasses soft freshness for every role`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        assertTrue(product(rig, access).refresh("slot", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        assertTrue(product(rig, access).refresh("manual", ExtensionRefreshTrigger.MANUAL) is ShadowRefreshOutcome.Committed)
        assertEquals(SourceRole.entries.toSet(), rig.runtime.plannedRoles.last())
    }

    @Test
    fun `soft freshness of one source never suppresses another source or a future timestamp`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        val ledger = FileExtensionNetworkLedger(rig.networkDirectory)
        val roleKeys = SourceRole.entries.map(ExtensionFreshnessKeys::role)
        // Another source of the same provider just succeeded everywhere; a timestamp from the future is untrusted.
        ledger.markFresh(ExtensionFreshnessKeys.source(SOURCE_B_KEY), "aniworld", roleKeys, NOW)
        ledger.markFresh(ExtensionFreshnessKeys.source(SOURCE_A_KEY), "aniworld", roleKeys, NOW.plusSeconds(1))
        assertTrue(product(rig, access).refresh("not-suppressed", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        assertTrue(rig.runtime.exports.isNotEmpty())
    }

    @Test
    fun `the automatic window allows one automatic run per hour but never blocks the first fill`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        val ledger = FileExtensionNetworkLedger(rig.networkDirectory)
        // A very recent automatic attempt marker without any committed data does not block the first fill.
        ledger.markFresh(ExtensionFreshnessKeys.source(SOURCE_A_KEY), "aniworld",
            listOf(ExtensionFreshnessKeys.AUTOMATIC_ATTEMPT), NOW)
        assertTrue(product(rig, access).refresh("first-fill", ExtensionRefreshTrigger.FOREGROUND) is ShadowRefreshOutcome.Committed)
        rig.clock.now = NOW.plusSeconds(10 * 60)
        val soon = product(rig, access).refresh("too-soon", ExtensionRefreshTrigger.FOREGROUND)
        assertTrue(soon is ShadowRefreshOutcome.Skipped)
        soon as ShadowRefreshOutcome.Skipped
        assertEquals("extension-auto-trigger-window", soon.reason)
        assertEquals(NOW.plusSeconds(3600), soon.nextEligibleAt)
        rig.clock.now = NOW.plusSeconds(3601)
        assertTrue(product(rig, access).refresh("next-hour", ExtensionRefreshTrigger.FOREGROUND) is ShadowRefreshOutcome.Committed)
    }

    @Test
    fun `a failed role does not block committing the roles that succeeded and is asked again by the next trigger`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(failRoles = setOf(SourceRole.RECENT))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        val first = product(rig, access).refresh("partial", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue(first is ShadowRefreshOutcome.Committed)
        first as ShadowRefreshOutcome.Committed
        assertEquals(setOf(SourceRole.CALENDAR, SourceRole.POSTPONEMENT), first.successfulRoles)
        assertFalse(first.refreshSucceeded)
        assertTrue("the failed role is absent from the cycle, not judged absent",
            first.cycle.sources.none { it.sourceType == ReleaseSourceType.ANIWORLD_RECENT })
        // The failure healed; the next trigger asks only for the role that is still not fresh.
        runtime.failRoles = emptySet()
        rig.clock.now = NOW.plusSeconds(3601)
        assertTrue(product(rig, access).refresh("heal", ExtensionRefreshTrigger.FOREGROUND) is ShadowRefreshOutcome.Committed)
        assertEquals(setOf(SourceRole.CALENDAR, SourceRole.RECENT, SourceRole.POSTPONEMENT), rig.runtime.plannedRoles.last())
    }

    @Test
    fun `a partial report still commits the rows the guest could read and never counts as fresh`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(partialRoles = setOf(SourceRole.RECENT))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        val outcome = product(rig, access).refresh("partial-report", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue("expected a commit, got $outcome", outcome is ShadowRefreshOutcome.Committed)
        outcome as ShadowRefreshOutcome.Committed
        assertTrue("the partial role is in the cycle", outcome.cycle.sources.any { it.sourceType == ReleaseSourceType.ANIWORLD_RECENT })
        assertTrue("its rows are evidence", outcome.cycle.sources.first { it.sourceType == ReleaseSourceType.ANIWORLD_RECENT }.evidence.isNotEmpty())
        assertEquals("only a fully successful role is fresh", setOf(SourceRole.CALENDAR, SourceRole.POSTPONEMENT), outcome.successfulRoles)
        assertFalse(outcome.refreshSucceeded)
        assertTrue(outcome.roleReports, outcome.roleReports.contains("RECENT:") && outcome.roleReports.contains("report=PARTIAL"))
        val statistics = rig.navigationStore.state.value.syncStatistics
        assertEquals("COMMITTED_PARTIAL", statistics["Last sync outcome"])
        assertEquals(NOW.toString(), statistics["Last committed sync"])
        assertEquals("", statistics["Last successful sync"])
    }

    @Test
    fun `a cooldown that denies every request is a typed deferral with a time and changes nothing`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val ledgerDirectory = temporaryFolder.newFolder()
        val transportLedger = FileExtensionNetworkLedger(ledgerDirectory)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime(), transportLedger = transportLedger,
            networkDirectory = ledgerDirectory)
        seedHealth()
        // One host-wide 429 blocks all three list URLs for this provider, with a two hour Retry-After.
        val url = "https://aniworld.to/animekalender"
        val reservation = requireNotNull(transportLedger.reserve("aniworld", "digest", "seed-generation", "CALENDAR", url, url, NOW))
        transportLedger.complete(reservation, "HTTP_429", 7200, NOW)
        val cyclesBefore = database.reconciliationDao().lastSequence()
        val outcome = product(rig, access).refresh("denied", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue("expected a typed deferral, got $outcome", outcome is ShadowRefreshOutcome.Skipped)
        outcome as ShadowRefreshOutcome.Skipped
        assertEquals("extension-budget-deferred", outcome.reason)
        assertEquals(NOW.plusSeconds(7200), outcome.nextEligibleAt)
        assertEquals("no cycle is committed", cyclesBefore, database.reconciliationDao().lastSequence())
        assertHealthUnchanged()
        assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
    }

    @Test
    fun `a role denied by its own cooldown is neither a failure nor damage to source health and the other roles commit`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val ledgerDirectory = temporaryFolder.newFolder()
        val transportLedger = FileExtensionNetworkLedger(ledgerDirectory)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime(), transportLedger = transportLedger,
            networkDirectory = ledgerDirectory)
        seedHealth()
        // An earlier transport failure of the RECENT list URL only (no host-wide 429).
        val url = "https://aniworld.to/neu"
        val reservation = requireNotNull(transportLedger.reserve("aniworld", "digest", "seed-generation", "RECENT", url, url, NOW))
        transportLedger.complete(reservation, "TRANSPORT_FAILURE", null, NOW)
        val outcome = product(rig, access).refresh("one-role-denied", ExtensionRefreshTrigger.PROCESS_START)
        assertTrue("expected a partial commit, got $outcome", outcome is ShadowRefreshOutcome.Committed)
        outcome as ShadowRefreshOutcome.Committed
        assertEquals(setOf(SourceRole.CALENDAR, SourceRole.POSTPONEMENT), outcome.successfulRoles)
        assertTrue("the denied role is absent from the cycle", outcome.cycle.sources.none { it.sourceType == ReleaseSourceType.ANIWORLD_RECENT })
        val recent = requireNotNull(database.aniworldPollDao().health(ReleaseSourceType.ANIWORLD_RECENT.name)?.toDomainOrNull())
        assertEquals("a host denial must not mark the source unavailable", SourceHealthStatus.HEALTHY, recent.status)
        assertEquals(NOW.minusSeconds(60), recent.lastAttemptAt)
        val expectedDeferral = "RECENT: http=none report=DEFERRED obs=0 diag=HOST_COOLDOWN until=${NOW.plusSeconds(1800)}"
        assertTrue(outcome.roleReports, outcome.roleReports.contains(expectedDeferral))
        assertFalse("a cooldown is not a parse failure", outcome.roleReports.contains("report=FAILURE"))
        assertEquals(outcome.roleReports, rig.navigationStore.state.value.syncStatistics["Role reports"])
        // The denied role stays due: the next automatic trigger asks for it again once the cooldown has lapsed.
        rig.clock.now = NOW.plusSeconds(3601)
        val recovered = product(rig, access).refresh("after-cooldown", ExtensionRefreshTrigger.FOREGROUND)
        assertTrue(recovered is ShadowRefreshOutcome.Committed)
        assertFalse((recovered as ShadowRefreshOutcome.Committed).roleReports.contains("report=DEFERRED"))
        assertTrue(SourceRole.RECENT in rig.runtime.plannedRoles.last())
    }

    @Test
    fun `product failure preserves prior receipt and accepted Room evidence`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime())
        assertTrue(product(rig, access).refresh("product-lkg", ExtensionRefreshTrigger.SCHEDULED_SLOT) is ShadowRefreshOutcome.Committed)
        val old = rig.navigationStore.state.value
        val before = database.reconciliationDao().projectionPage(256, 0)
        val failed = TriggeredShadowRefreshCoordinator { _, _ -> ShadowRefreshOutcome.Failed("NETWORK", true) }
        assertEquals(ShadowRefreshOutcome.Failed("NETWORK", true),
            product(rig, access, failed).refresh("product-transient-failure", ExtensionRefreshTrigger.MANUAL))
        assertEquals(old, rig.navigationStore.state.value)
        assertEquals(before, database.reconciliationDao().projectionPage(256, 0))
    }

    @Test
    fun `two concurrent product triggers use one owner and do not duplicate runtime work`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime(blockParsing = true))
        val production = product(rig, access)
        withTimeout(30_000) {
            val running = async { production.refresh("product-one-owner", ExtensionRefreshTrigger.SCHEDULED_SLOT) }
            rig.runtime.parseEntered.await()
            assertEquals(ShadowRefreshOutcome.Failed("BUSY", true),
                production.refresh("product-duplicate", ExtensionRefreshTrigger.MANUAL))
            rig.runtime.releaseParsing.complete(Unit)
            assertTrue(running.await() is ShadowRefreshOutcome.Committed)
            val again = production.refresh("product-new-start", ExtensionRefreshTrigger.PROCESS_START)
            assertTrue(again is ShadowRefreshOutcome.Skipped && again.reason == "extension-data-fresh")
        }
    }

    private fun product(rig: Rig, access: InstalledExtensionAccess,
        delegate: TriggeredShadowRefreshCoordinator? = null): ProductionExtensionReleaseRefreshCoordinator {
        val sources = object : ExtensionSourceRepository {
            override val sources = MutableStateFlow<List<ExtensionSource>>(emptyList())
            override suspend fun add(url: String) = AddExtensionSourceResult.InvalidUrl
            override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun refresh(sourceId: String) = Unit
            override suspend fun refreshEnabled() = false
            override suspend fun activate(sourceId: String, extensionId: String) = Unit
        }
        val selectedCoordinator = delegate ?: if (access === rig.originalAccess) rig.worker
            else rig.coordinatorFactory(access)
        return ProductionExtensionReleaseRefreshCoordinator(sources, selectedCoordinator)
    }

    @Test
    fun `no active release source skips even when a navigation provider is selected`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(NAVIGATION_ONLY_KEY to extensionPackage(NAVIGATION_ONLY_KEY, roles = emptySet())))
        val rig = rig(access, active = null, navigation = NAVIGATION_ONLY_KEY, runtime = FixtureRuntime())

        val outcome = rig.worker.refreshForWork("no-active-source")

        assertEquals(ShadowRefreshOutcome.Skipped("no-active-release-source"), outcome)
        assertTrue(access.loads.isEmpty())
        assertEquals(0, rig.runtime.exports.size)
        assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
    }

    @Test
    fun `worker resolves the source selected immediately before execution`() = runBlocking {
        val selectedAtExecution = SOURCE_B_KEY
        val access = AtomicInstalledAccess(mapOf(selectedAtExecution to extensionPackage(selectedAtExecution, packageGeneration = 4)))
        val runtime = FixtureRuntime()
        val rig = rig(access, active = null, runtime = runtime)
        rig.policy.selectActiveSource(selectedAtExecution)

        val outcome = rig.worker.refreshForWork("selection-at-execution")

        assertTrue("unexpected worker outcome: $outcome; exports=${runtime.exports}", outcome is ShadowRefreshOutcome.Committed)
        assertTrue(access.loads.contains(selectedAtExecution))
        assertFalse(access.loads.contains(SOURCE_A_KEY))
        assertEquals(selectedAtExecution, rig.navigationStore.state.value.source)
        assertEquals(4L, rig.navigationStore.state.value.packageGeneration)
        assertCurrentEvidenceProducedReceipt(outcome, rig)
    }

    @Test
    fun `navigation-only provider never becomes the release ingestion source`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(
            SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY),
            NAVIGATION_ONLY_KEY to extensionPackage(NAVIGATION_ONLY_KEY, roles = emptySet()),
        ))
        val rig = rig(access, active = SOURCE_A_KEY, navigation = NAVIGATION_ONLY_KEY, runtime = FixtureRuntime())

        val outcome = rig.worker.refreshForWork("navigation-cannot-ingest")

        assertTrue("unexpected worker outcome: $outcome; loads=${access.loads}; exports=${rig.runtime.exports}",
            outcome is ShadowRefreshOutcome.Committed)
        assertTrue(access.loads.contains(SOURCE_A_KEY))
        assertFalse(access.loads.contains(NAVIGATION_ONLY_KEY))
        assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.source)
        assertCurrentEvidenceProducedReceipt(outcome, rig)
    }

    @Test
    fun `navigation preference changes do not stale a running release generation`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, navigation = NAVIGATION_ONLY_KEY, runtime = runtime)
        val before = rig.policy.policy.value
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("navigation-policy-change") }
            runtime.parseEntered.await()

            rig.policy.selectNavigationProvider(SOURCE_B_KEY)
            rig.policy.setPreferences(NAVIGATION_ONLY_KEY, ExtensionPreferences(enabledTracks = setOf("DE_DUB")))
            val after = rig.policy.policy.value
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertTrue(outcome is ShadowRefreshOutcome.Committed)
            assertTrue(after.generation > before.generation)
            assertEquals(before.releaseGeneration, after.releaseGeneration)
            assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.source)
        }
    }

    @Test
    fun `active source switch fences completion and source health writes`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(
            SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY),
            SOURCE_B_KEY to extensionPackage(SOURCE_B_KEY),
        ))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        seedHealth()
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("switch-source-during-run") }
            runtime.parseEntered.await()

            rig.policy.selectActiveSource(SOURCE_B_KEY)
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertHealthUnchanged()
            assertNull(rig.navigationStore.state.value.source)
            assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
        }
    }

    @Test
    fun `switch to a same-provider source of another publisher fences the old worker and the new source commits under its own receipt`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(
            SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY),
            SOURCE_OTHER_PUBLISHER_KEY to extensionPackage(SOURCE_OTHER_PUBLISHER_KEY),
        ))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        seedHealth()
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("a-worker-across-a-publisher-switch") }
            runtime.parseEntered.await()

            rig.policy.selectActiveSource(SOURCE_OTHER_PUBLISHER_KEY)
            runtime.releaseParsing.complete(Unit)
            val stale = running.await()

            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), stale)
            assertHealthUnchanged()
            assertNull("the old worker must not leave a receipt in the new source's context", rig.navigationStore.state.value.source)
            assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))

            val own = rig.worker.refreshForWork("other-publisher-own-run")
            assertTrue("the new source must be able to commit after the old worker was fenced: $own",
                own is ShadowRefreshOutcome.Committed)
            assertEquals(SOURCE_OTHER_PUBLISHER_KEY, rig.navigationStore.state.value.source)
            assertEquals(SOURCE_OTHER_PUBLISHER_KEY, rig.navigationStore.state.value.rowsSource)
            assertEquals(extensionPackage(SOURCE_OTHER_PUBLISHER_KEY).packageDigest, rig.navigationStore.state.value.packageDigest)
        }
    }

    @Test
    fun `a failed first refresh of a new source does not take over the rows of the previous source`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(
            SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY),
            SOURCE_OTHER_PUBLISHER_KEY to extensionPackage(SOURCE_OTHER_PUBLISHER_KEY),
        ))
        val runtime = FixtureRuntime()
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        withTimeout(30_000) {
            val first = rig.worker.refreshForProductWork("a-commits-the-rows")
            assertTrue("source A must commit: $first", first is ShadowRefreshOutcome.Committed)
            assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.rowsSource)

            rig.policy.selectActiveSource(SOURCE_OTHER_PUBLISHER_KEY)
            runtime.failPlan = true
            val failed = rig.worker.refreshForProductWork("other-publisher-first-refresh-fails")
            assertTrue("the refresh of the new source must fail: $failed", failed is ShadowRefreshOutcome.Failed)
            // The failure is recorded for the new source, but the rows still belong to A.
            assertEquals(SOURCE_OTHER_PUBLISHER_KEY, rig.navigationStore.state.value.source)
            assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.rowsSource)

            runtime.failPlan = false
            val own = rig.worker.refreshForProductWork("other-publisher-second-refresh-succeeds")
            assertTrue("the new source must commit once its runtime works: $own", own is ShadowRefreshOutcome.Committed)
            assertEquals(SOURCE_OTHER_PUBLISHER_KEY, rig.navigationStore.state.value.rowsSource)
        }
    }

    @Test
    fun `active source track preference change fences the pinned release generation`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        val before = rig.policy.policy.value
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("active-track-policy-change") }
            runtime.parseEntered.await()

            rig.policy.setPreferences(SOURCE_A_KEY, ExtensionPreferences(enabledTracks = setOf("DE_DUB")))
            val after = rig.policy.policy.value
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertTrue(after.releaseGeneration > before.releaseGeneration)
            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertTrue(ROLE_SOURCE_TYPES.values.all { database.aniworldPollDao().health(it.name) == null })
            assertNull(rig.navigationStore.state.value.source)
        }
    }

    @Test
    fun `package revocation after dispatch prevents commit and health writes`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        seedHealth()
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("revoke-package-during-run") }
            runtime.parseEntered.await()

            access.revoke(SOURCE_A_KEY)
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertHealthUnchanged()
            assertNull(rig.navigationStore.state.value.source)
        }
    }

    @Test
    fun `ABA reinstall with the original digest is fenced by the pinned package generation`() = runBlocking {
        val first = extensionPackage(SOURCE_A_KEY, packageGeneration = 10)
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to first))
        val runtime = FixtureRuntime(blockParsing = true)
        val rig = rig(access, active = SOURCE_A_KEY, runtime = runtime)
        seedHealth()
        withTimeout(30_000) {
            val running = async { rig.worker.refreshForWork("aba-package-reinstall") }
            runtime.parseEntered.await()

            access.replace(SOURCE_A_KEY, extensionPackage(SOURCE_A_KEY,
                packageDigest = sha256("package-v2".toByteArray()), packageGeneration = 11))
            access.replace(SOURCE_A_KEY, extensionPackage(SOURCE_A_KEY,
                packageDigest = first.packageDigest, packageGeneration = 12))
            runtime.releaseParsing.complete(Unit)
            val outcome = running.await()

            assertEquals(ShadowRefreshOutcome.Failed("stale-generation-token", retryable = false), outcome)
            assertHealthUnchanged()
            assertNull(rig.navigationStore.state.value.source)
            assertNull(database.aniworldPollDao().activeGeneration(RoomExtensionShadowGenerationStore.SCOPE_ID))
        }
    }

    @Test
    fun `failed host run records health under the ordered policy and package guards`() = runBlocking {
        val access = AtomicInstalledAccess(mapOf(SOURCE_A_KEY to extensionPackage(SOURCE_A_KEY)))
        val rig = rig(access, active = SOURCE_A_KEY, runtime = FixtureRuntime(failPlan = true))

        val outcome = withTimeout(10_000) { rig.worker.refreshForWork("failure-health-guard") }

        assertEquals(ShadowRefreshOutcome.Failed(ExtensionHostFailureCode.RUNTIME_FAILURE.name, retryable = true), outcome)
        assertEquals(SOURCE_A_KEY, rig.navigationStore.state.value.source)
        assertTrue(rig.navigationStore.state.value.installments.isEmpty())
        assertEquals("FAILED", rig.navigationStore.state.value.syncStatistics["Last sync outcome"])
        assertEquals("RUNTIME_FAILURE", rig.navigationStore.state.value.syncStatistics["Last sync failure"])
        assertEquals("NO_COMMITTED_PARSE", rig.navigationStore.state.value.syncStatistics["Last parse status"])
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireNotNull(database.aniworldPollDao().health(type.name)?.toDomainOrNull())
            assertEquals(SourceHealthStatus.UNAVAILABLE, health.status)
            assertEquals("extension-host-runtime_failure", health.diagnostic)
        }
    }

    private suspend fun rig(
        installed: AtomicInstalledAccess,
        active: ExtensionSelectionKey?,
        navigation: ExtensionSelectionKey? = null,
        runtime: FixtureRuntime,
        postponementStore: FileExtensionPostponementStore? = null,
        transportLedger: ExtensionNetworkLedger = HermeticExtensionNetworkLedger(),
        networkDirectory: File? = null,
    ): Rig {
        val root = temporaryFolder.newFolder()
        val network = networkDirectory ?: File(root, "network")
        val clock = MutableClock(NOW)
        val policy = FileExtensionProductPolicyRepository(
            File(root, "policy"), eligible = { true }, releaseEligible = { true }, navigationEligible = { true },
        )
        policy.selectActiveSource(active)
        policy.selectNavigationProvider(navigation)
        val reconciliation = RoomReleaseReconciliationRepository(database)
        val authority = ExtensionEvidenceAuthorityAdapter(installed.packages.values
            .filter { it.grantedRoles.isNotEmpty() }
            .map { packageInfo -> ApprovedExtensionAuthorityTuple(
                packageInfo.publisherId, packageInfo.signingKeyId, packageInfo.extensionId.value,
                packageInfo.providerId.value, packageInfo.grantedRoles,
            ) }.toSet())
        val navigationStore = FileProviderNavigationStateStore(File(root, "navigation"))
        val coordinatorFactory: (InstalledExtensionAccess) -> SingleSourceShadowRefreshCoordinator = { accessForWorker ->
            SingleSourceShadowRefreshCoordinator(
                policy = policy,
                installed = accessForWorker,
                runtime = runtime,
                networkDirectory = network,
                authority = authority,
                reconciliation = reconciliation,
                generations = RoomExtensionShadowGenerationStore(
                    database, reconciliation, clock, processEpoch = "worker-test",
                ),
                targetSource = ExtensionTargetSource { listOf(ACQUISITION_TARGET) },
                clock = clock,
                navigationStore = navigationStore,
                postponementStore = postponementStore,
                releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { repository, hostRuntime, _, observationPolicy ->
                    ExtensionHostCoordinator(
                        repository, hostRuntime, hermeticProductionTransport(transportLedger, clock), observationPolicy,
                        clock = clock, enabled = { true },
                        parseFuelByExtensionId = mapOf(ExtensionId.parse("de.aniworld") to 25_000_000L),
                    )
                },
            )
        }
        val worker = coordinatorFactory(installed)
        val restarted: () -> SingleSourceShadowRefreshCoordinator = {
            val reloadedReconciliation = RoomReleaseReconciliationRepository(database)
            SingleSourceShadowRefreshCoordinator(
                policy = FileExtensionProductPolicyRepository(File(root, "policy")) { true },
                installed = installed,
                runtime = runtime,
                networkDirectory = network,
                authority = authority,
                reconciliation = reloadedReconciliation,
                generations = RoomExtensionShadowGenerationStore(
                    database, reloadedReconciliation, clock, processEpoch = "restarted-test",
                ),
                targetSource = ExtensionTargetSource { emptyList() },
                clock = clock,
                navigationStore = FileProviderNavigationStateStore(File(root, "navigation")),
                releaseHostFactory = ReleaseExtensionHostCoordinatorFactory { _, _, _, _ ->
                    error("a restarted process with fresh data must not create a host")
                },
            )
        }
        return Rig(worker, policy, navigationStore, runtime, reconciliation, File(root, "navigation"), clock, network,
            originalAccess = installed, coordinatorFactory = coordinatorFactory,
            policyDirectory = File(root, "policy"), restarted = restarted)
    }

    private suspend fun assertCurrentEvidenceProducedReceipt(outcome: ShadowRefreshOutcome, rig: Rig) {
        val cycle = (outcome as ShadowRefreshOutcome.Committed).cycle
        val evidence = cycle.sources.flatMap { it.evidence }
        assertTrue(
            "committed worker cycle contained no projected Evidence; sources=" +
                cycle.sources.map { "${it.sourceType}:${it.result}:${it.evidence.size}" } +
                "; exports=${rig.runtime.exports}",
            evidence.isNotEmpty(),
        )

        val reducer = AniWorldReleaseAuthorityReducer()
        val traces = evidence.map { item ->
            val identity = CanonicalReleaseIdentity.from(item)
            val standalone = reducer.reduce(null, item)
            val persisted = identity?.let { rig.reconciliation.get(it.key) }
            "${item.sourceType}/${item.evidenceType}/${item.languageTrack}:" +
                "identity=${identity?.key}:standalone=${standalone.phase}/${standalone.authority}:" +
                "stored=${persisted?.underlyingPhase}/${persisted?.phase}/${persisted?.authority}"
        }
        val independentlyPositive = evidence.filter { item ->
            val decision = reducer.reduce(null, item)
            decision.phase == ReleasePhase.RELEASED && decision.authority == ReleaseAuthority.ANIWORLD
        }
        assertTrue("cycle Evidence did not independently reduce to authoritative RELEASED: $traces",
            independentlyPositive.isNotEmpty())

        val persistedPositive = independentlyPositive.filter { item ->
            val identity = CanonicalReleaseIdentity.from(item) ?: return@filter false
            val state = rig.reconciliation.get(identity.key) ?: return@filter false
            state.underlyingPhase == ReleasePhase.RELEASED && state.authority != ReleaseAuthority.NONE
        }
        assertTrue("independent positive Evidence had no persisted release authority: $traces",
            persistedPositive.isNotEmpty())

        assertTrue("current positive Evidence did not produce a navigation receipt; $traces; " +
            "receipt=${rig.navigationStore.state.value.installments}",
            rig.navigationStore.state.value.installments.isNotEmpty())
    }

    private suspend fun seedHealth() {
        ROLE_SOURCE_TYPES.values.forEach { type ->
            database.aniworldPollDao().putHealth(SourceHealth(
                sourceType = type,
                status = SourceHealthStatus.HEALTHY,
                lastAttemptAt = NOW.minusSeconds(60),
                lastSuccessAt = NOW.minusSeconds(60),
                parserVersion = "seed-parser",
                sourceHash = "seed-hash",
            ).toEntity())
        }
    }

    private suspend fun assertHealthUnchanged() {
        ROLE_SOURCE_TYPES.values.forEach { type ->
            val health = requireNotNull(database.aniworldPollDao().health(type.name)?.toDomainOrNull())
            assertEquals(SourceHealthStatus.HEALTHY, health.status)
            assertEquals(NOW.minusSeconds(60), health.lastAttemptAt)
            assertEquals(NOW.minusSeconds(60), health.lastSuccessAt)
            assertEquals("seed-parser", health.parserVersion)
            assertEquals("seed-hash", health.sourceHash)
        }
    }

    private data class Rig(
        val worker: SingleSourceShadowRefreshCoordinator,
        val policy: FileExtensionProductPolicyRepository,
        val navigationStore: FileProviderNavigationStateStore,
        val runtime: FixtureRuntime,
        val reconciliation: RoomReleaseReconciliationRepository,
        val navigationDirectory: File,
        val clock: MutableClock,
        val networkDirectory: File,
        val originalAccess: InstalledExtensionAccess,
        val coordinatorFactory: (InstalledExtensionAccess) -> SingleSourceShadowRefreshCoordinator,
        val policyDirectory: File,
        /** A coordinator of a new process: policy and receipt are read from disk again, nothing is shared in memory. */
        val restarted: () -> SingleSourceShadowRefreshCoordinator,
    )

    /** A clock the test moves; the host, the generation store and the coordinator all read the same one. */
    private class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private class AtomicInstalledAccess(packages: Map<ExtensionSelectionKey, VerifiedExtensionPackage>) : InstalledExtensionAccess {
        private val mutex = Mutex()
        private val mutablePackages = packages.toMutableMap()
        val packages: Map<ExtensionSelectionKey, VerifiedExtensionPackage> get() = synchronized(mutablePackages) { mutablePackages.toMap() }
        val loads = mutableListOf<ExtensionSelectionKey>()

        override suspend fun loadInstalled(key: ExtensionSelectionKey): VerifiedExtensionPackage? {
            loads += key
            return synchronized(mutablePackages) { mutablePackages[key] }
        }

        override suspend fun <T> withCurrentPackage(
            key: ExtensionSelectionKey,
            digest: String,
            block: suspend () -> T,
        ): T? = mutex.withLock {
            val matches = synchronized(mutablePackages) { mutablePackages[key]?.packageDigest == digest }
            if (matches) block() else null
        }

        override suspend fun <T> withCurrentGeneration(
            key: ExtensionSelectionKey,
            digest: String,
            generation: Long,
            block: suspend () -> T,
        ): T? = mutex.withLock {
            val current = synchronized(mutablePackages) { mutablePackages[key] }
            if (current?.packageDigest == digest && current.packageGeneration == generation) block() else null
        }

        suspend fun replace(key: ExtensionSelectionKey, packageInfo: VerifiedExtensionPackage) = mutex.withLock {
            synchronized(mutablePackages) { mutablePackages[key] = packageInfo }
        }

        suspend fun revoke(key: ExtensionSelectionKey) = mutex.withLock {
            synchronized(mutablePackages) { mutablePackages.remove(key) }
        }
    }

    private class FixtureRuntime(
        private val blockParsing: Boolean = false,
        @Volatile var failPlan: Boolean = false,
        @Volatile var failRoles: Set<SourceRole> = emptySet(),
        /** Roles whose report is PARTIAL: the guest returned the rows it could read and dropped the others. */
        @Volatile var partialRoles: Set<SourceRole> = emptySet(),
    ) : ExtensionRuntime {
        val exports = mutableListOf<String>()
        /** The roles the host asked the guest to plan, one entry per plan call. */
        val plannedRoles = mutableListOf<Set<SourceRole>>()
        val parseEntered = CompletableDeferred<Unit>()
        val releaseParsing = CompletableDeferred<Unit>()
        private var hasBlocked = false

        override suspend fun execute(
            moduleDigest: String,
            moduleBytes: ByteArray,
            exportName: String,
            inputUtf8: ByteArray,
            limits: ExtensionExecutionLimits,
        ): ExtensionRuntimeResult {
            assertEquals(sha256(MODULE_BYTES), moduleDigest)
            assertTrue(moduleBytes.contentEquals(MODULE_BYTES))
            exports += exportName
            return when (exportName) {
                "plan_requests" -> {
                    if (failPlan) return ExtensionRuntimeResult.Failure(ExtensionRuntimeErrorCode.TRAP)
                    val input = ExtensionWireCodec.decodePlanInput(inputUtf8)
                    plannedRoles.add(input.context.sourceRoles.toSet())
                    val requests = input.context.sourceRoles.map { role ->
                        val token = if (role == SourceRole.DIRECT) input.context.targets.single().targetToken else null
                        val url = when (role) {
                            SourceRole.CALENDAR -> "https://aniworld.to/animekalender"
                            SourceRole.RECENT -> "https://aniworld.to/neu"
                            SourceRole.POSTPONEMENT -> "https://aniworld.to/verspaetungen"
                            SourceRole.DIRECT -> "https://aniworld.to/anime/stream/fixture-series/episode-1"
                        }
                        """{"requestId":"request-${role.name.lowercase()}","sourceRole":"${role.name}","url":"$url","method":"GET","targetToken":${token?.let { "\"$it\"" } ?: "null"}}"""
                    }
                    ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"requests":[${requests.joinToString(",")}] }""".toByteArray(),
                    )
                }
                "parse_responses" -> {
                    if (blockParsing && !hasBlocked) {
                        hasBlocked = true
                        parseEntered.complete(Unit)
                        releaseParsing.await()
                    }
                    val input = ExtensionWireCodec.decodeParseInput(inputUtf8)
                    val response = input.responses.single()
                    val role = response.sourceRole
                    if (response.status == com.axiel7.anihyou.release.core.extension.ExtensionResponseStatus.BUDGET_DENIED) {
                        return ExtensionRuntimeResult.Success(
                            """{"schemaVersion":1,"observations":[],"responseReports":[{"requestId":"${response.requestId}","outcome":"FAILURE","diagnostics":[]}]}"""
                                .toByteArray(),
                        )
                    }
                    if (role in failRoles) return ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"observations":[],"responseReports":[{"requestId":"${response.requestId}","outcome":"FAILURE","diagnostics":[]}] }""".toByteArray())
                    val target = if (role == SourceRole.DIRECT) input.context.targets.single() else null
                    val kind = when (role) {
                        SourceRole.CALENDAR -> "FORECAST"
                        SourceRole.RECENT -> "RELEASE_LISTING"
                        SourceRole.POSTPONEMENT -> "CORRECTION"
                        SourceRole.DIRECT -> "DIRECT_AVAILABILITY"
                    }
                    val series = if (role == SourceRole.DIRECT) target!!.providerSeriesKey else "fixture-series"
                    val sourceSeason = target?.sourceSeason ?: 1
                    val navigationSeason = target?.navigationSeason ?: 1
                    val installmentKind = target?.installment?.kind?.name ?: ObservationInstallmentKind.EPISODE.name
                    val installmentNumber = target?.installment?.number ?: "1"
                    val track = target?.track?.name ?: ObservationTrack.DE_SUB.name
                    val observation = """{"schemaVersion":1,"extensionId":"${input.context.extensionId.value}","providerId":"${input.context.providerId.value}","requestId":"${response.requestId}","sourceRole":"${role.name}","providerSeriesKey":"$series","rawTitle":"Fixture release","sourceSeason":$sourceSeason,"navigationSeason":$navigationSeason,"installment":{"kind":"$installmentKind","number":"$installmentNumber"},"track":"$track","claimKind":"$kind","sourceDateText":null,"sourceTimeText":null,"sourceRawText":null,"parsedTimestamp":null,"approximate":false,"scheduleMarker":"NONE","correctionMarker":null,"sourceUrl":"${response.finalUrl}","sourceHash":"${response.sourceHash}","diagnostics":[]}"""
                    ExtensionRuntimeResult.Success(
                        """{"schemaVersion":1,"observations":[$observation],"responseReports":[{"requestId":"${response.requestId}","outcome":"${if (role in partialRoles) "PARTIAL" else "SUCCESS"}","diagnostics":[]}] }""".toByteArray(),
                    )
                }
                else -> error("unexpected extension export $exportName")
            }
        }
    }

    private fun hermeticProductionTransport(ledger: ExtensionNetworkLedger, clock: Clock) = ProductionExtensionHttpTransport(
        ledger = ledger,
        resolver = ExtensionAddressResolver { host ->
            assertEquals("aniworld.to", host)
            listOf(PUBLIC_ADDRESS)
        },
        hop = BoundHttpsHopExecutor { _, host, addresses, _, cancellation ->
            cancellation.check()
            assertEquals("aniworld.to", host)
            assertTrue(PUBLIC_ADDRESS in addresses)
            BoundHopResponse(200, emptyMap(), NETWORK_BODY.toByteArray(), PUBLIC_ADDRESS)
        },
        clock = clock,
    )

    private class HermeticExtensionNetworkLedger : ExtensionNetworkLedger {
        private val counter = AtomicInteger()

        override suspend fun reserve(
            provider: String,
            digest: String,
            generation: String,
            role: String,
            rootUrl: String,
            hopUrl: String,
            now: Instant,
        ): ExtensionNetworkReservation = ExtensionNetworkReservation("worker-test-${counter.incrementAndGet()}")

        override suspend fun complete(
            reservation: ExtensionNetworkReservation,
            outcome: String,
            retryAfterSeconds: Long?,
            now: Instant,
        ) = Unit
    }

    private fun extensionPackage(
        key: ExtensionSelectionKey,
        roles: Set<SourceRole> = ALL_ROLES,
        packageDigest: String = sha256("package:${key.sourceId}".toByteArray()),
        packageGeneration: Long = 0,
    ) = VerifiedExtensionPackage(
        extensionId = ExtensionId.parse(key.extensionId),
        providerId = ProviderId.parse(key.providerId),
        displayName = "Fixture ${key.sourceId}",
        publisherId = key.publisherId,
        signingKeyId = SIGNING_KEY_ID,
        trustRootVersion = 1,
        releaseSequence = 1,
        abiVersion = 1,
        policyVersion = 1,
        packageDigest = packageDigest,
        manifestDigest = sha256("manifest:${key.sourceId}".toByteArray()),
        moduleDigest = sha256(MODULE_BYTES),
        moduleBytes = MODULE_BYTES,
        grantedRoles = roles,
        navigationCapabilities = if (roles.isEmpty()) setOf(NavigationCapability.EPISODE_NAVIGATION) else emptySet(),
        grantedHosts = setOf("aniworld.to"),
        runtimeVersion = "wasmtime-48.0.3-test",
    ).also { it.packageGeneration = packageGeneration }

    companion object {
        private const val SIGNING_KEY_ID = "fixture-key"
        private const val NETWORK_BODY = "<html>hermetic worker fixture</html>"
        private val PUBLIC_ADDRESS = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        private val NOW = Instant.parse("2026-10-01T10:00:00Z")
        private val MODULE_BYTES = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        private val ALL_ROLES = SourceRole.entries.toSet()
        private val ROLE_SOURCE_TYPES = mapOf(
            SourceRole.CALENDAR to ReleaseSourceType.ANIWORLD_CALENDAR,
            SourceRole.RECENT to ReleaseSourceType.ANIWORLD_RECENT,
            SourceRole.POSTPONEMENT to ReleaseSourceType.ANIWORLD_POSTPONEMENT,
            SourceRole.DIRECT to ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
        )
        private val SOURCE_A_KEY = ExtensionSelectionKey("release-source-a", "de.aniworld", "fixture-publisher", "aniworld")
        private val SOURCE_B_KEY = ExtensionSelectionKey("release-source-b", "de.aniworld", "fixture-publisher", "aniworld")
        private val SOURCE_OTHER_PUBLISHER_KEY = ExtensionSelectionKey("release-source-c", "de.aniworld", "other-publisher", "aniworld")
        private val NAVIGATION_ONLY_KEY = ExtensionSelectionKey("navigation-only", "de.navigation", "navigation-publisher", "otherprovider")
        private val DIRECT_TARGET = ExtensionTargetV1(
            targetToken = "target-1",
            providerSeriesKey = "fixture-series",
            providerUrl = null,
            sourceSeason = 1,
            navigationSeason = 1,
            installment = InstallmentV1(ObservationInstallmentKind.EPISODE, "1"),
            track = ObservationTrack.DE_SUB,
        )
        private val DIRECT_EVIDENCE = ReleaseEvidence(
            id = "fixture-direct-target",
            sourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE,
            sourceUrl = "https://aniworld.to/anime/stream/fixture-series/episode-1",
            sourceHash = "fixture-identity-hash",
            parserVersion = "fixture",
            observedAt = NOW,
            sourceReportedAt = null,
            approximateTime = false,
            siteIdentifier = AniWorldSiteIdentifier("fixture-series", sourceType = AniWorldIdentitySourceType.DIRECT_PAGE),
            sourceSeason = 1,
            navigationSeason = 1,
            installment = Installment.Episode(1),
            languageTrack = LanguageTrack.DE_SUB,
            evidenceType = ReleaseEvidenceType.VERIFICATION,
            scheduleCondition = ScheduleCondition.UNKNOWN,
            confidence = ConfidenceVector(1.0, 1.0, 1.0, 1.0, 1.0),
        )
        private val ACQUISITION_TARGET = ExtensionAcquisitionTarget(
            DIRECT_TARGET, requireNotNull(CanonicalReleaseIdentity.from(DIRECT_EVIDENCE)).key,
        )

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

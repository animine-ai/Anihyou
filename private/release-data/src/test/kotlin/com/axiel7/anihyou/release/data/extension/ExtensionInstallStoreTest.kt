package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.SourceRole
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.erdtman.jcs.JsonCanonicalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Focused persistence and recovery coverage for the package install state machine. */
class ExtensionInstallStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pre-activation fault boundaries survive restart without activating partial install`() {
        for (boundary in listOf(
            InstallBoundary.STAGED,
            InstallBoundary.VERIFIED,
            InstallBoundary.CONTENT_PLACED,
            InstallBoundary.BEFORE_ACTIVE,
        )) {
            val directory = temporaryFolder.newFolder("before-${boundary.name.lowercase()}")
            val fixture = StoreFixture(directory)
            val release = fixture.release(1)
            val firstIndex = fixture.index(sequence = 1, packages = listOf(release))
            val store = fixture.store()
            fixture.initialize(store, firstIndex)

            val interrupted = fixture.store { observed ->
                if (observed == boundary) throw SimulatedCrash(boundary)
            }
            assertCrash(boundary) { interrupted.install(release.archive, EXTENSION, NOW) }

            // A process restart reloads the last committed state and discards stale staging files.
            File(directory, "staging/abandoned.staging").writeBytes(byteArrayOf(1, 2, 3))
            val restarted = fixture.store()
            assertNull(load(restarted))
            assertEquals(0, File(directory, "staging").listFiles()!!.size)

            val receipt = restarted.install(release.archive, EXTENSION, NOW)
            assertEquals(1L, receipt.sequence)
            assertNotNull(load(restarted))
            if (boundary == InstallBoundary.CONTENT_PLACED) {
                assertTrue(File(directory, "content/${receipt.digest}.arex").isFile)
            }
        }
    }

    @Test
    fun `active commit and release high water survive failure after activation`() {
        val directory = temporaryFolder.newFolder("after-active")
        val fixture = StoreFixture(directory)
        val release = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release)))

        val interrupted = fixture.store { boundary ->
            if (boundary == InstallBoundary.AFTER_ACTIVE) throw SimulatedCrash(boundary)
        }
        assertCrash(InstallBoundary.AFTER_ACTIVE) { interrupted.install(release.archive, EXTENSION, NOW) }

        val restarted = fixture.store()
        assertEquals(release.binding.archiveSha256, load(restarted)!!.packageDigest)
        assertRejected { restarted.install(release.archive, EXTENSION, NOW) }
    }

    @Test
    fun `same sequence different content and index downgrade remain rejected after restart`() {
        val directory = temporaryFolder.newFolder("index-high-water")
        val fixture = StoreFixture(directory)
        val release = fixture.release(1)
        val accepted = fixture.index(7, listOf(release))
        val store = fixture.store()
        fixture.initialize(store, accepted)

        val restarted = fixture.store()
        assertRejected {
            restarted.acceptIndex(
                fixture.index(7, listOf(release), issuedAt = NOW.minusSeconds(30)).envelope,
                NOW,
            )
        }
        assertRejected { restarted.acceptIndex(fixture.index(6, listOf(release)).envelope, NOW) }

        // The failed candidates did not move the persisted high-water mark or poison the accepted index.
        val receipt = restarted.install(release.archive, EXTENSION, NOW)
        assertEquals(7L, receipt.indexSequence)
        assertEquals(release.binding.archiveSha256, load(fixture.store())!!.packageDigest)
    }

    @Test
    fun `more than sixteen catalogs retain installed signed bindings and high water after restart`() {
        val directory = temporaryFolder.newFolder("sparse-index-history")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val second = fixture.release(2)
        val third = fixture.release(3)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.acceptIndex(fixture.index(2, listOf(second)).envelope, NOW)
        store.install(second.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.acceptIndex(fixture.index(3, listOf(third)).envelope, NOW)
        store.install(third.archive, EXTENSION, NOW)
        for (sequence in 4L..25L) {
            store.acceptIndex(fixture.index(sequence, listOf(third),
                issuedAt = NOW.minusSeconds(60 - sequence)).envelope, NOW)
        }
        val persisted = stateJson(directory)
        assertEquals(4, (persisted["indexes"] as JsonArray).size)
        val restarted = fixture.store()
        assertEquals(25L, restarted.snapshot().index!!.sequence)
        assertEquals(third.binding.archiveSha256, load(restarted)!!.packageDigest)
        assertRejected { restarted.acceptIndex(fixture.index(24, listOf(third)).envelope, NOW) }
        assertRejected { restarted.acceptIndex(fixture.index(25, listOf(second)).envelope, NOW) }
        assertEquals(second.binding.archiveSha256,
            restarted.quarantineAndRollback(EXTENSION, NOW)!!.digest)
        assertEquals(second.binding.archiveSha256, load(fixture.store())!!.packageDigest)
        assertEquals(2, (stateJson(directory)["indexes"] as JsonArray).size)
        assertEquals(25L, fixture.store().snapshot().index!!.sequence)
    }

    @Test
    fun `revocation still lands after compaction and excludes prior known good`() {
        val directory = temporaryFolder.newFolder("sparse-index-revocation")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val second = fixture.release(2)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.acceptIndex(fixture.index(2, listOf(second)).envelope, NOW)
        store.install(second.archive, EXTENSION, NOW)
        for (sequence in 3L..20L) store.acceptIndex(fixture.index(sequence, listOf(second),
            issuedAt = NOW.minusSeconds(60 - sequence)).envelope, NOW)
        store.acceptIndex(fixture.index(21, listOf(first.copy(revoked = true), second)).envelope, NOW)
        val restarted = fixture.store()
        assertTrue(first.binding.archiveSha256 in restarted.snapshot().revokedDigests)
        assertEquals(3, (stateJson(directory)["indexes"] as JsonArray).size)
        assertNull(restarted.quarantineAndRollback(EXTENSION, NOW))
        assertNull(load(fixture.store()))
        val after = fixture.store()
        assertRejected { after.acceptIndex(fixture.index(20, listOf(second)).envelope, NOW) }
        assertTrue(first.binding.archiveSha256 in after.snapshot().revokedDigests)
    }

    @Test
    fun `rollback after promotion fault quarantines new generation and preserves release high water`() {
        val directory = temporaryFolder.newFolder("rollback-restart")
        val fixture = StoreFixture(directory)
        val release1 = fixture.release(1)
        var store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release1)))
        store.install(release1.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        val release2 = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(release1, release2)).envelope, NOW)
        store.install(release2.archive, EXTENSION, NOW)

        val promotionInterrupted = fixture.store { boundary ->
            if (boundary == InstallBoundary.AFTER_PROMOTION) throw SimulatedCrash(boundary)
        }
        assertCrash(InstallBoundary.AFTER_PROMOTION) { promotionInterrupted.promoteHealthy(NOW) }

        store = fixture.store()
        val fallback = store.quarantineAndRollback(NOW)
        assertEquals(release1.binding.archiveSha256, fallback!!.digest)
        assertNotNull(load(store))

        // Rollback restores the known-good pointer only; it never rewinds the release high-water.
        assertRejected { store.install(release2.archive, EXTENSION, NOW) }
        val afterRestart = fixture.store()
        assertEquals(release1.binding.archiveSha256, load(afterRestart)!!.packageDigest)
    }

    @Test
    fun `revoked known good is not selected or executable during rollback`() {
        val directory = temporaryFolder.newFolder("revoked-known-good")
        val fixture = StoreFixture(directory)
        val release1 = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release1)))
        store.install(release1.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        val release2 = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(release1.copy(revoked = true), release2)).envelope, NOW)
        store.install(release2.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        assertNull(store.quarantineAndRollback(NOW))
        assertNull(load(store))
        assertNull(load(fixture.store()))
    }

    @Test
    fun `corrupt active content falls back once to eligible prior known good after restart`() {
        val directory = temporaryFolder.newFolder("corrupt-active")
        val fixture = StoreFixture(directory)
        val release1 = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(release1)))
        store.install(release1.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)

        val release2 = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(release1, release2)).envelope, NOW)
        val updated = store.install(release2.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)
        File(directory, "content/${updated.digest}.arex").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(release1.binding.archiveSha256, load(fixture.store())!!.packageDigest)
        assertEquals(release1.binding.archiveSha256, load(fixture.store())!!.packageDigest)
        assertRejected { fixture.store().install(release2.archive, EXTENSION, NOW) }
    }

    @Test
    fun `two installed extensions retain independent active and healthy generations after restart`() {
        val directory = temporaryFolder.newFolder("multiple-generations")
        val fixture = StoreFixture(directory, listOf(EXTENSION to PROVIDER, OTHER_EXTENSION to OTHER_PROVIDER))
        val first = fixture.release(1)
        val other = fixture.release(1, extensionId = OTHER_EXTENSION, providerId = OTHER_PROVIDER)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first, other)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.install(other.archive, OTHER_EXTENSION, NOW)
        store.promoteHealthy(OTHER_EXTENSION, NOW)

        val restarted = fixture.store()
        assertEquals(first.binding.archiveSha256, load(restarted)!!.packageDigest)
        assertEquals(other.binding.archiveSha256, runBlocking {
            restarted.loadUsable(ProviderId.parse(OTHER_PROVIDER))
        }!!.packageDigest)
        val snapshot = restarted.snapshot()
        assertEquals(setOf(EXTENSION, OTHER_EXTENSION), snapshot.generations.keys)
        assertEquals(1L, snapshot.root!!.version)
        assertEquals(1L, snapshot.index!!.sequence)
        assertEquals(mapOf(EXTENSION to 1L, OTHER_EXTENSION to 1L), snapshot.releaseHigh)
        assertEquals("1.0.1", snapshot.generations.getValue(EXTENSION).active!!.version)
        assertEquals(first.binding.archiveSha256, snapshot.generations.getValue(EXTENSION).knownGood!!.digest)
        assertEquals(other.binding.archiveSha256, snapshot.generations.getValue(OTHER_EXTENSION).knownGood!!.digest)
        assertRejected { restarted.promoteHealthy(NOW) }
        assertRejected { restarted.quarantineAndRollback(NOW) }
    }

    @Test
    fun `provider ambiguity fails closed while explicit extension health remains available`() {
        val directory = temporaryFolder.newFolder("ambiguous-provider")
        val fixture = StoreFixture(directory, listOf(EXTENSION to PROVIDER, OTHER_EXTENSION to PROVIDER))
        val first = fixture.release(1)
        val other = fixture.release(1, extensionId = OTHER_EXTENSION)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first, other)))
        store.install(first.archive, EXTENSION, NOW)
        store.install(other.archive, OTHER_EXTENSION, NOW)

        assertNull(load(fixture.store()))
        assertEquals(first.binding.archiveSha256, loadExtension(fixture.store(), EXTENSION)!!.packageDigest)
        assertEquals(other.binding.archiveSha256, loadExtension(fixture.store(), OTHER_EXTENSION)!!.packageDigest)
        assertNull(loadExtension(fixture.store(), "unknown.extension"))
    }

    @Test
    fun `failed update leaves both extensions and their release high water unchanged`() {
        val directory = temporaryFolder.newFolder("independent-update-failure")
        val fixture = StoreFixture(directory, listOf(EXTENSION to PROVIDER, OTHER_EXTENSION to OTHER_PROVIDER))
        val first = fixture.release(1)
        val other = fixture.release(1, extensionId = OTHER_EXTENSION, providerId = OTHER_PROVIDER)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first, other)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.install(other.archive, OTHER_EXTENSION, NOW)
        store.promoteHealthy(OTHER_EXTENSION, NOW)
        val update = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(first, update, other)).envelope, NOW)
        val interrupted = fixture.store { boundary ->
            if (boundary == InstallBoundary.BEFORE_ACTIVE) throw SimulatedCrash(boundary)
        }
        assertCrash(InstallBoundary.BEFORE_ACTIVE) { interrupted.install(update.archive, EXTENSION, NOW) }

        val restarted = fixture.store()
        assertEquals(first.binding.archiveSha256, loadExtension(restarted, EXTENSION)!!.packageDigest)
        assertEquals(other.binding.archiveSha256, loadExtension(restarted, OTHER_EXTENSION)!!.packageDigest)
        assertEquals(mapOf(EXTENSION to 1L, OTHER_EXTENSION to 1L), restarted.snapshot().releaseHigh)
        assertEquals(first.binding.archiveSha256, restarted.snapshot().generations.getValue(EXTENSION).knownGood!!.digest)
    }

    @Test
    fun `rollback and repeated healthy promotion affect only the selected extension`() {
        val directory = temporaryFolder.newFolder("independent-rollback")
        val fixture = StoreFixture(directory, listOf(EXTENSION to PROVIDER, OTHER_EXTENSION to OTHER_PROVIDER))
        val first = fixture.release(1)
        val other = fixture.release(1, extensionId = OTHER_EXTENSION, providerId = OTHER_PROVIDER)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first, other)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.install(other.archive, OTHER_EXTENSION, NOW)
        store.promoteHealthy(OTHER_EXTENSION, NOW)
        val update = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(first, update, other)).envelope, NOW)
        store.install(update.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)

        assertEquals(first.binding.archiveSha256, store.quarantineAndRollback(EXTENSION, NOW)!!.digest)
        val snapshot = fixture.store().snapshot()
        assertEquals(first.binding.archiveSha256, snapshot.generations.getValue(EXTENSION).active!!.digest)
        assertTrue(snapshot.generations.getValue(EXTENSION).rollbackUsed)
        assertEquals(false, snapshot.generations.getValue(OTHER_EXTENSION).rollbackUsed)
        assertEquals(other.binding.archiveSha256, loadExtension(fixture.store(), OTHER_EXTENSION)!!.packageDigest)
        assertEquals(mapOf(EXTENSION to 2L, OTHER_EXTENSION to 1L), snapshot.releaseHigh)
        assertTrue(update.binding.archiveSha256 in snapshot.quarantinedDigests)
        assertRejected { fixture.store().install(update.archive, EXTENSION, NOW) }
    }

    @Test
    fun `shared root revocation blocks one extension without replacing the other generation`() {
        val directory = temporaryFolder.newFolder("independent-revocation")
        val fixture = StoreFixture(directory, listOf(EXTENSION to PROVIDER, OTHER_EXTENSION to OTHER_PROVIDER))
        val first = fixture.release(1)
        val other = fixture.release(1, extensionId = OTHER_EXTENSION, providerId = OTHER_PROVIDER)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first, other)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.install(other.archive, OTHER_EXTENSION, NOW)
        store.promoteHealthy(OTHER_EXTENSION, NOW)
        store.acceptRoot(fixture.rootEnvelope(2, revokedDigests = setOf(first.binding.archiveSha256)), NOW)

        assertNull(loadExtension(fixture.store(), EXTENSION))
        assertEquals(other.binding.archiveSha256, loadExtension(fixture.store(), OTHER_EXTENSION)!!.packageDigest)
        val snapshot = fixture.store().snapshot()
        assertTrue(first.binding.archiveSha256 in snapshot.revokedDigests)
        assertEquals(other.binding.archiveSha256, snapshot.generations.getValue(OTHER_EXTENSION).knownGood!!.digest)
        assertEquals(mapOf(EXTENSION to 1L, OTHER_EXTENSION to 1L), snapshot.releaseHigh)
    }

    @Test
    fun `unchanged authenticated metadata refresh does not exhaust history after more than sixteen checks`() {
        val directory = temporaryFolder.newFolder("unchanged-metadata")
        val fixture = StoreFixture(directory)
        val index = fixture.index(1, listOf(fixture.release(1)))
        val store = fixture.store()
        fixture.initialize(store, index)
        repeat(40) { check ->
            val now = NOW.plusSeconds(check.toLong())
            assertEquals(1L, store.acceptRoot(fixture.rootEnvelope(), now).version)
            assertEquals(1L, store.acceptIndex(index.envelope, now).sequence)
        }
        val state = stateJson(directory)
        assertEquals(1, (state["roots"] as JsonArray).size)
        assertEquals(1, (state["indexes"] as JsonArray).size)
        assertEquals(NOW.plusSeconds(39), fixture.store().snapshot().acceptedClock)
        assertEquals(1L, fixture.store().snapshot().index!!.sequence)
    }

    @Test
    fun `unchanged rotated root still verifies thresholds signatures and canonical digest`() {
        val directory = temporaryFolder.newFolder("unchanged-root-authentication")
        val fixture = StoreFixture(directory)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(fixture.release(1))))
        val root2 = fixture.rootEnvelope(2)
        store.acceptRoot(root2, NOW)
        repeat(20) { store.acceptRoot(root2, NOW.plusSeconds(it.toLong())) }
        val before = File(directory, "state.json").readBytes()
        assertRejected { store.acceptRoot(fixture.rootEnvelope(2, signerCount = 1), NOW.plusSeconds(30)) }
        assertRejected { store.acceptRoot(fixture.rootEnvelope(2, expiresAt = NOW.plusSeconds(86401)), NOW.plusSeconds(30)) }
        val parsed = ExtensionWireCodec.parseStrictJson(root2, 65536) as JsonObject
        val signatures = (parsed["signatures"] as JsonArray).toMutableList()
        signatures[0] = JsonObject((signatures[0] as JsonObject).toMutableMap().apply {
            put("signature", string(Base64.getEncoder().encodeToString(ByteArray(64))))
        })
        val tampered = JsonObject(parsed.toMutableMap().apply { put("signatures", JsonArray(signatures)) })
        assertRejected { store.acceptRoot(tampered.toString().toByteArray(), NOW.plusSeconds(30)) }
        org.junit.Assert.assertArrayEquals(before, File(directory, "state.json").readBytes())
        assertEquals(2, (stateJson(directory)["roots"] as JsonArray).size)
    }

    @Test
    fun `unchanged root remains refreshable when the bounded rotation journal is full`() {
        val directory = temporaryFolder.newFolder("full-root-journal")
        val fixture = StoreFixture(directory)
        val store = fixture.store()
        for (version in 1L..16L) store.acceptRoot(fixture.rootEnvelope(version), NOW)
        repeat(20) { assertEquals(16L, store.acceptRoot(fixture.rootEnvelope(16), NOW.plusSeconds(it.toLong())).version) }
        assertRejected { store.acceptRoot(fixture.rootEnvelope(17), NOW.plusSeconds(30)) }
        assertEquals(16, (stateJson(directory)["roots"] as JsonArray).size)
        assertEquals(16L, fixture.store().snapshot().root!!.version)
    }

    @Test
    fun `expired unchanged root is rejected without advancing durable time`() {
        val directory = temporaryFolder.newFolder("unchanged-expired-root")
        val fixture = StoreFixture(directory)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(fixture.release(1))))
        assertRejected { store.acceptRoot(fixture.rootEnvelope(), NOW.plusSeconds(86400)) }
        assertEquals(NOW, fixture.store().snapshot().acceptedClock)
        assertEquals(1, (stateJson(directory)["roots"] as JsonArray).size)
    }

    @Test
    fun `legacy migration preserves rollback revocation quarantine high water and authenticated installed version`() {
        val directory = temporaryFolder.newFolder("legacy-migration")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)
        val update = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(first, update)).envelope, NOW)
        store.install(update.archive, EXTENSION, NOW)
        store.promoteHealthy(NOW)
        store.quarantineAndRollback(NOW)
        val revoked = fixture.release(3, revoked = true)
        val latest = fixture.index(3, listOf(revoked))
        store.acceptIndex(latest.envelope, NOW.plusSeconds(30))
        val legacy = legacyState(directory)
        File(directory, "state.json").writeText(legacy.toString())
        val before = File(directory, "state.json").readBytes()

        val restarted = fixture.store()
        val snapshot = restarted.snapshot()
        assertEquals("1.0.1", snapshot.generations.getValue(EXTENSION).active!!.version)
        assertTrue(snapshot.generations.getValue(EXTENSION).rollbackUsed)
        assertEquals(2L, snapshot.releaseHigh.getValue(EXTENSION))
        assertTrue(update.binding.archiveSha256 in snapshot.quarantinedDigests)
        assertTrue(revoked.binding.archiveSha256 in snapshot.revokedDigests)
        assertEquals(NOW.plusSeconds(30), snapshot.acceptedClock)
        org.junit.Assert.assertArrayEquals(before, File(directory, "state.json").readBytes())

        restarted.acceptIndex(latest.envelope, NOW.plusSeconds(30))
        val migrated = stateJson(directory)
        assertEquals(2, (migrated["schemaVersion"] as JsonPrimitive).content.toInt())
        for (field in listOf("roots", "indexes", "indexHigh", "indexDigest", "releaseHigh", "revoked", "quarantine", "clock")) {
            assertEquals("preserved $field", legacy[field], migrated[field])
        }
        assertEquals(first.binding.archiveSha256, load(fixture.store())!!.packageDigest)
    }

    @Test
    fun `legacy rollback flag survives a generation with no remaining active or healthy receipt`() {
        val directory = temporaryFolder.newFolder("legacy-empty-rollback")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first)))
        store.install(first.archive, EXTENSION, NOW)
        assertNull(store.quarantineAndRollback(NOW))
        File(directory, "state.json").writeText(legacyState(directory).toString())
        val snapshot = fixture.store().snapshot()
        assertEquals(setOf(EXTENSION), snapshot.generations.keys)
        assertNull(snapshot.generations.getValue(EXTENSION).active)
        assertTrue(snapshot.generations.getValue(EXTENSION).rollbackUsed)
        assertEquals(1L, snapshot.releaseHigh.getValue(EXTENSION))
        assertTrue(first.binding.archiveSha256 in snapshot.quarantinedDigests)
    }

    @Test
    fun `legacy cross-extension scalar receipts never make one extension active through another healthy pointer`() {
        val directory = temporaryFolder.newFolder("legacy-cross-extension")
        val fixture = StoreFixture(directory, listOf(EXTENSION to PROVIDER, OTHER_EXTENSION to OTHER_PROVIDER))
        val first = fixture.release(1)
        val other = fixture.release(1, extensionId = OTHER_EXTENSION, providerId = OTHER_PROVIDER)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first, other)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        store.install(other.archive, OTHER_EXTENSION, NOW)
        File(directory, "state.json").writeText(legacyState(directory,
            activeExtension = OTHER_EXTENSION, knownGoodExtension = EXTENSION, rollbackUsed = true).toString())
        val restarted = fixture.store()
        val snapshot = restarted.snapshot()
        assertNull(snapshot.generations.getValue(EXTENSION).active)
        assertEquals(first.binding.archiveSha256, snapshot.generations.getValue(EXTENSION).knownGood!!.digest)
        assertEquals(other.binding.archiveSha256, snapshot.generations.getValue(OTHER_EXTENSION).active!!.digest)
        assertTrue(snapshot.generations.values.all { it.rollbackUsed })
        assertNull(loadExtension(restarted, EXTENSION))
        assertEquals(other.binding.archiveSha256, loadExtension(restarted, OTHER_EXTENSION)!!.packageDigest)
        assertRejected { restarted.quarantineAndRollback(NOW) }
    }

    @Test
    fun `generation identity and installed version cannot disagree with authenticated receipt bindings`() {
        val directory = temporaryFolder.newFolder("corrupt-generation-identity")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first)))
        store.install(first.archive, EXTENSION, NOW)
        val valid = stateJson(directory)
        val generations = valid["generations"] as JsonObject
        val wrongIdentity = JsonObject(valid.toMutableMap().apply {
            put("generations", JsonObject(mapOf(OTHER_EXTENSION to generations.getValue(EXTENSION))))
        })
        File(directory, "state.json").writeText(wrongIdentity.toString())
        assertRejected { fixture.store() }
        val generation = generations.getValue(EXTENSION) as JsonObject
        val wrongVersion = JsonObject(valid.toMutableMap().apply {
            put("generations", JsonObject(mapOf(EXTENSION to JsonObject(generation.toMutableMap().apply {
                put("active", JsonObject((generation["active"] as JsonObject).toMutableMap().apply {
                    put("version", string("9.9.9"))
                }))
            }))))
        })
        File(directory, "state.json").writeText(wrongVersion.toString())
        assertRejected { fixture.store() }
    }

    @Test
    fun `source cancellation after synchronous smoke cannot commit an activation`() {
        val directory = temporaryFolder.newFolder("cancel-after-smoke")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val events = mutableListOf<String>()
        val store = fixture.store(smoke = { events += "smoke" })
        fixture.initialize(store, fixture.index(1, listOf(first)))
        val before = File(directory, "state.json").readBytes()
        try {
            store.install(first.archive, EXTENSION, NOW) {
                events += "activation-fence"
                throw CancellationException("source disabled during smoke")
            }
            throw AssertionError("cancelled activation committed")
        } catch (_: CancellationException) { }
        assertEquals(listOf("smoke", "activation-fence"), events)
        org.junit.Assert.assertArrayEquals(before, File(directory, "state.json").readBytes())
        assertEquals(emptyMap<String, Long>(), fixture.store().snapshot().releaseHigh)
        assertNull(load(fixture.store()))
        assertTrue(File(directory, "content/${first.binding.archiveSha256}.arex").isFile)
        // Cached verified bytes are harmless until a later authorized activation commits.
        store.install(first.archive, EXTENSION, NOW)
        assertEquals(first.binding.archiveSha256, load(fixture.store())!!.packageDigest)
    }

    @Test
    fun `source cancellation before promotion leaves the prior healthy generation intact`() {
        val directory = temporaryFolder.newFolder("cancel-promotion")
        val fixture = StoreFixture(directory)
        val first = fixture.release(1)
        val store = fixture.store()
        fixture.initialize(store, fixture.index(1, listOf(first)))
        store.install(first.archive, EXTENSION, NOW)
        store.promoteHealthy(EXTENSION, NOW)
        val update = fixture.release(2)
        store.acceptIndex(fixture.index(2, listOf(first, update)).envelope, NOW)
        store.install(update.archive, EXTENSION, NOW)
        val before = File(directory, "state.json").readBytes()
        try {
            store.promoteHealthy(EXTENSION, NOW) { throw CancellationException("source removed before promotion") }
            throw AssertionError("cancelled promotion committed")
        } catch (_: CancellationException) { }
        org.junit.Assert.assertArrayEquals(before, File(directory, "state.json").readBytes())
        assertEquals(first.binding.archiveSha256, fixture.store().snapshot().generations.getValue(EXTENSION).knownGood!!.digest)
        assertEquals(first.binding.archiveSha256, store.quarantineAndRollback(EXTENSION, NOW)!!.digest)
    }

    private fun stateJson(directory: File) =
        ExtensionWireCodec.parseStrictJson(File(directory, "state.json").readBytes(), 8 * 1048576) as JsonObject

    private fun legacyState(directory: File, activeExtension: String = EXTENSION,
        knownGoodExtension: String = activeExtension, rollbackUsed: Boolean? = null): JsonObject {
        val current = stateJson(directory)
        val generations = current["generations"] as JsonObject
        val active = generations.getValue(activeExtension) as JsonObject
        val healthy = generations.getValue(knownGoodExtension) as JsonObject
        fun withoutVersion(value: JsonElement?) = if (value == null || value == JsonNull) JsonNull else
            JsonObject((value as JsonObject).filterKeys { it != "version" })
        return JsonObject(current.filterKeys { it !in setOf("schemaVersion", "generations") } + mapOf(
            "active" to withoutVersion(active["active"]), "knownGood" to withoutVersion(healthy["knownGood"]),
            "previousGood" to withoutVersion(healthy["previousGood"]),
            "rollbackUsed" to (rollbackUsed?.let(::JsonPrimitive) ?: active.getValue("rollbackUsed")),
        ))
    }

    private fun loadExtension(store: ExtensionInstallStore, extensionId: String) = runBlocking {
        store.loadUsableExtension(extensionId)
    }

    private fun load(store: ExtensionInstallStore) = runBlocking {
        store.loadUsable(ProviderId.parse(PROVIDER))
    }

    private fun assertCrash(expected: InstallBoundary, block: () -> Unit) {
        try {
            block()
        } catch (failure: SimulatedCrash) {
            assertEquals(expected, failure.boundary)
            return
        }
        throw AssertionError("expected injected fault at $expected")
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected install-store policy rejection")
    }

    private class SimulatedCrash(val boundary: InstallBoundary) : RuntimeException("fault at $boundary")

    private class StoreFixture(
        private val directory: File,
        private val identities: List<Pair<String, String>> = listOf(EXTENSION to PROVIDER),
    ) {
        private val rootKeys = listOf(key(1), key(2), key(3))
        private val indexKey = key(4)
        private val publisherKey = key(5)
        private val rootDoc = rootDocument()
        private val pin = AppTrustPin(REPOSITORY, rootDoc.digest, setOf(CDN_ORIGIN))

        fun store(smoke: (VerifiedExtensionPackage) -> Unit = {},
            hook: (InstallBoundary) -> Unit = {}): ExtensionInstallStore = ExtensionInstallStore(
            directory = directory,
            pin = pin,
            verifier = ExtensionPackageVerifier(WasmCoreModuleProfileVerifier { _, _ -> }),
            hostRoles = setOf(SourceRole.CALENDAR),
            hostHosts = setOf(HOST),
            policyVersion = 1,
            runtimeVersion = "wasmtime-48.0.3",
            smoke = smoke,
            failure = InstallFailureHook(hook),
        )

        fun initialize(store: ExtensionInstallStore, index: IndexDocument) {
            store.acceptRoot(rootDoc.envelope(rootKeys.take(2)), NOW)
            store.acceptIndex(index.envelope, NOW)
        }

        fun release(sequence: Long, revoked: Boolean = false,
            extensionId: String = EXTENSION, providerId: String = PROVIDER): PackageDocument {
            val version = "1.0.$sequence"
            val displayName = "Demo $sequence"
            val archive = packageArchive(sequence, version, displayName, extensionId, providerId)
            val manifest = manifest(sequence, version, displayName, extensionId = extensionId, providerId = providerId)
            val binding = VerifiedCatalogPackageBinding(
                extensionId = extensionId,
                providerId = providerId,
                displayName = displayName,
                navigationCapabilities = emptySet(),
                publisherId = PUBLISHER,
                keyId = sha256(publisherKey.public),
                version = version,
                releaseSequence = sequence,
                archiveSha256 = sha256(archive.readBytes()),
                archiveBytes = archive.length(),
                canonicalManifestSha256 = sha256(canonical(manifest)),
                yanked = false,
            )
            return PackageDocument(archive, binding, revoked)
        }

        fun index(
            sequence: Long,
            packages: List<PackageDocument>,
            issuedAt: Instant = NOW.minusSeconds(60),
            rootVersion: Long = 1,
        ): IndexDocument {
            val entries = packages.sortedWith(compareBy<PackageDocument> { it.binding.extensionId }
                .thenBy { it.binding.releaseSequence }).map { pkg ->
                val binding = pkg.binding
                jsonObject(
                    "extensionId" to string(binding.extensionId),
                    "providerId" to string(binding.providerId),
                    "displayName" to string(binding.displayName),
                    "navigationCapabilities" to JsonArray(emptyList()),
                    "publisherId" to string(binding.publisherId),
                    "keyId" to string(binding.keyId),
                    "version" to string(binding.version),
                    "releaseSequence" to number(binding.releaseSequence),
                    "hostApiMin" to number(1),
                    "hostApiMax" to number(1),
                    "packageUrl" to string("$CDN_ORIGIN/packages/${binding.archiveSha256}.arex"),
                    "archiveSha256" to string(binding.archiveSha256),
                    "archiveBytes" to number(binding.archiveBytes),
                    "manifestSha256" to string(binding.canonicalManifestSha256),
                    "yanked" to JsonPrimitive(false),
                    "revoked" to JsonPrimitive(pkg.revoked),
                )
            }
            val signed = jsonObject(
                "schemaVersion" to number(1),
                "repositoryId" to string(REPOSITORY),
                "rootVersion" to number(rootVersion),
                "sequence" to number(sequence),
                "issuedAt" to string(issuedAt.toString()),
                "expiresAt" to string(NOW.plusSeconds(3600).toString()),
                "entries" to JsonArray(entries),
            )
            return IndexDocument(signed, indexKey)
        }

        fun rootEnvelope(version: Long = 1, signerCount: Int = 2,
            revokedDigests: Set<String> = emptySet(), expiresAt: Instant = NOW.plusSeconds(86400)): ByteArray {
            val signed = JsonObject(rootDoc.signed.toMutableMap().apply {
                put("version", number(version))
                put("expiresAt", string(expiresAt.toString()))
                put("revokedDigests", JsonArray(revokedDigests.sorted().map(::string)))
            })
            return envelopeBytes("AREX-ROOT-V1\n", signed, rootKeys.take(signerCount))
        }

        private fun rootDocument(): RootDocument {
            val allKeys = rootKeys + indexKey + publisherKey
            val signed = jsonObject(
                "schemaVersion" to number(1),
                "repositoryId" to string(REPOSITORY),
                "version" to number(1),
                "expiresAt" to string(NOW.plusSeconds(86400).toString()),
                "keys" to JsonArray(allKeys.map { key ->
                    jsonObject("keyId" to string(sha256(key.public)), "publicKey" to string(Base64.getEncoder().encodeToString(key.public)))
                }),
                "roles" to jsonObject(
                    "root" to jsonObject("threshold" to number(2), "keyIds" to JsonArray(rootKeys.map { string(sha256(it.public)) })),
                    "index" to jsonObject("threshold" to number(1), "keyIds" to JsonArray(listOf(string(sha256(indexKey.public))))),
                ),
                "publishers" to JsonArray(identities.map { (extensionId, providerId) -> jsonObject(
                    "publisherId" to string(PUBLISHER),
                    "extensionId" to string(extensionId),
                    "providerId" to string(providerId),
                    "keyId" to string(sha256(publisherKey.public)),
                    "roles" to JsonArray(listOf(string(SourceRole.CALENDAR.name))),
                    "navigation" to JsonArray(emptyList()),
                    "hosts" to JsonArray(listOf(string(HOST))),
                    "notBefore" to string(NOW.minusSeconds(3600).toString()),
                    "expiresAt" to string(NOW.plusSeconds(86400).toString()),
                ) }),
                "revokedKeys" to JsonArray(emptyList()),
                "revokedDigests" to JsonArray(emptyList()),
            )
            return RootDocument(signed)
        }

        private fun packageArchive(sequence: Long, version: String, displayName: String,
            extensionId: String, providerId: String): File {
            val module = WASM_MODULE
            val provenance = provenance(module)
            val notice = "SPDX-License-Identifier: MIT\n".toByteArray(StandardCharsets.UTF_8)
            val manifest = manifest(sequence, version, displayName, module, provenance, notice, extensionId, providerId)
            val canonicalManifest = canonical(manifest)
            val signer = Ed25519Signer().apply { init(true, publisherKey.privateKey) }
            val message = PACKAGE_DOMAIN + canonicalManifest
            signer.update(message, 0, message.size)
            val signature = JsonObject(mapOf(
                "algorithm" to string("Ed25519"),
                "keyId" to string(sha256(publisherKey.public)),
                "signature" to string(Base64.getEncoder().encodeToString(signer.generateSignature())),
            )).toString().toByteArray(StandardCharsets.UTF_8)
            val entries = linkedMapOf(
                "manifest.json" to manifest.toByteArray(StandardCharsets.UTF_8),
                "module.wasm" to module,
                "provenance.json" to provenance,
                "NOTICE" to notice,
                "package.sig" to signature,
            )
            val archive = File(directory, "fixture-$extensionId-$sequence.arex")
            ZipArchiveOutputStream(archive).use { output ->
                entries.forEach { (name, bytes) ->
                    output.putArchiveEntry(ZipArchiveEntry(name).apply {
                        time = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
                    })
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
                output.finish()
            }
            return archive
        }

        private fun manifest(
            sequence: Long,
            version: String,
            displayName: String,
            module: ByteArray = WASM_MODULE,
            provenance: ByteArray = provenance(module),
            notice: ByteArray = "SPDX-License-Identifier: MIT\n".toByteArray(StandardCharsets.UTF_8),
            extensionId: String = EXTENSION, providerId: String = PROVIDER,
        ): String = """{"schemaVersion":1,"extensionId":"$extensionId","providerId":"$providerId","displayName":"$displayName","version":"$version","releaseSequence":$sequence,"hostApiMin":1,"hostApiMax":1,"capabilities":["CALENDAR"],"navigationCapabilities":[],"allowedHosts":["$HOST"],"digests":{"module":{"sha256":"${sha256(module)}","bytes":${module.size}},"provenance":{"sha256":"${sha256(provenance)}","bytes":${provenance.size}},"notice":{"sha256":"${sha256(notice)}","bytes":${notice.size}}},"publisherId":"$PUBLISHER","keyId":"${sha256(publisherKey.public)}","sourceRepository":"https://github.com/example/extension","sourceCommit":"${"a".repeat(40)}","build":{"toolchainVersion":"rustc-1.88.0","target":"wasm32-wasip1","lockfileDigest":"${"b".repeat(64)}","workflowIdentity":".github/workflows/extension-build.yml"}}"""

        private fun provenance(module: ByteArray): ByteArray =
            """{"schemaVersion":1,"sourceRepository":"https://github.com/example/extension","sourceCommit":"${"a".repeat(40)}","licenseSpdx":["MIT"],"components":[],"localModifications":[],"compilerVersion":"rustc-1.88.0","sdkVersion":"wasm32-wasip1","dependencyLockDigest":"${"b".repeat(64)}","reproducibleBuildCommand":"cargo build --locked --release --target wasm32-wasip1","workflowIdentity":".github/workflows/extension-build.yml","moduleDigest":"${sha256(module)}"}"""
                .toByteArray(StandardCharsets.UTF_8)
    }

    private data class PackageDocument(
        val archive: File,
        val binding: VerifiedCatalogPackageBinding,
        val revoked: Boolean,
    )

    private data class RootDocument(val signed: JsonObject) {
        val digest: String get() = sha256(canonical(signed))
        fun envelope(signers: List<SigningKey>) = envelopeBytes("AREX-ROOT-V1\n", signed, signers)
    }

    private data class IndexDocument(val signed: JsonObject, val signer: SigningKey) {
        val envelope: ByteArray get() = envelopeBytes("AREX-INDEX-V1\n", signed, listOf(signer))
    }

    private data class SigningKey(val privateKey: Ed25519PrivateKeyParameters) {
        val public: ByteArray = privateKey.generatePublicKey().encoded
    }

    companion object {
        private const val REPOSITORY = "install-store-test"
        private const val CDN_ORIGIN = "https://cdn.example"
        private const val PUBLISHER = "test-publisher"
        private const val EXTENSION = "demo.extension"
        private const val PROVIDER = "demo"
        private const val OTHER_EXTENSION = "demo.other"
        private const val OTHER_PROVIDER = "other"
        private const val HOST = "aniworld.to"
        private val NOW = Instant.parse("2026-09-28T12:00:00Z")
        private val WASM_MODULE = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        private val PACKAGE_DOMAIN = "AREX-PACKAGE-V1\n".toByteArray(StandardCharsets.UTF_8)

        private fun key(seed: Int) = SigningKey(Ed25519PrivateKeyParameters(ByteArray(32) { (seed + it).toByte() }, 0))
        private fun canonical(value: String) = JsonCanonicalizer(value).encodedString.toByteArray(StandardCharsets.UTF_8)
        private fun canonical(value: JsonObject) = canonical(value.toString())
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun string(value: String) = JsonPrimitive(value)
        private fun number(value: Long) = JsonPrimitive(value)
        private fun number(value: Int) = JsonPrimitive(value)
        private fun jsonObject(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(fields.toMap())

        private fun envelopeBytes(domain: String, signed: JsonObject, signers: List<SigningKey>): ByteArray {
            val message = domain.toByteArray(StandardCharsets.UTF_8) + canonical(signed)
            val signatures = signers.map { key ->
                val signer = Ed25519Signer()
                signer.init(true, key.privateKey)
                signer.update(message, 0, message.size)
                jsonObject(
                    "algorithm" to string("Ed25519"),
                    "keyId" to string(sha256(key.public)),
                    "signature" to string(Base64.getEncoder().encodeToString(signer.generateSignature())),
                )
            }
            return jsonObject(
                "signed" to signed,
                "signatures" to JsonArray(signatures),
            ).toString().toByteArray(StandardCharsets.UTF_8)
        }
    }
}

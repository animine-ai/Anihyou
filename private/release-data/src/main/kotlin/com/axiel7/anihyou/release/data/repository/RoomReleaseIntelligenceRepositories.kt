package com.axiel7.anihyou.release.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.ReleaseAuthorityReducer
import com.axiel7.anihyou.release.core.api.ReleaseDecisionRepository
import com.axiel7.anihyou.release.core.api.ReleaseEvidenceRepository
import com.axiel7.anihyou.release.core.api.ReleaseForecastRevisionRepository
import com.axiel7.anihyou.release.core.api.SourceHealthRepository
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseForecastRevision
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.ReleaseEvidenceAliasEntity
import com.axiel7.anihyou.release.data.db.ReleaseEvidenceDuplicateArchiveEntity
import com.axiel7.anihyou.release.data.db.ReleaseEvidenceEntity
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntityOrNull
import com.axiel7.anihyou.release.data.db.legacyEvidenceId
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal sealed class EvidenceAppendResolution {
    data class Inserted(val canonicalEvidence: ReleaseEvidence) : EvidenceAppendResolution()
    data class Existing(val canonicalEvidence: ReleaseEvidence) : EvidenceAppendResolution()
    data class Rejected(val reason: String) : EvidenceAppendResolution()
}

internal class RoomReleaseEvidenceStore(private val database: ReleaseDatabase) {
    private val dao = database.releaseDao()

    suspend fun resolveOrAppend(evidence: ReleaseEvidence): EvidenceAppendResolution {
        val entity = evidence.toEntityOrNull()
            ?: return EvidenceAppendResolution.Rejected("Evidence exceeds persistence bounds")
        if (ReleaseEvidenceFingerprintV2.isV2Id(evidence.id) &&
            !ReleaseEvidenceFingerprintV2.isValidEvidenceId(evidence)
        ) {
            return EvidenceAppendResolution.Rejected("Evidence v2 ID does not match its payload")
        }
        if (evidence.sourceType.isAniWorld && !ReleaseEvidenceFingerprintV2.isValidEvidenceId(evidence)) {
            return EvidenceAppendResolution.Rejected("new AniWorld Evidence must use its v2 ID")
        }

        val byId = resolveStoredId(evidence.id)
        if (byId != null) {
            requireCompatible(byId, evidence)
            return EvidenceAppendResolution.Existing(byId)
        }

        val byFingerprint = dao.getReleaseEvidenceByFingerprint(entity.canonicalFingerprint)
        require(byFingerprint.size <= 1) { "duplicate active Evidence fingerprints" }
        byFingerprint.singleOrNull()?.let { row ->
            val canonical = decodeActive(row)
            requireCompatible(canonical, evidence)
            val canonicalKindKnown = canonical.id == evidence.id ||
                ReleaseEvidenceFingerprintV2.isValidEvidenceId(canonical) ||
                legacyEvidenceId(canonical) == canonical.id
            check(canonicalKindKnown) { "cannot alias an opaque active Evidence ID" }
            if (canonical.id != evidence.id) {
                require(ReleaseEvidenceFingerprintV2.isV2Id(evidence.id) &&
                    ReleaseEvidenceFingerprintV2.isValidEvidenceId(evidence)
                ) { "a semantic duplicate must use a valid v2 alias ID" }
                val alias = ReleaseEvidenceAliasEntity(
                    aliasId = evidence.id,
                    canonicalEvidenceId = canonical.id,
                    canonicalFingerprint = entity.canonicalFingerprint,
                    aliasKind = "RUNTIME_V2",
                    createdAt = evidence.observedAt.toString(),
                )
                try {
                    dao.insertEvidenceAlias(alias)
                } catch (error: SQLiteConstraintException) {
                    val concurrent = resolveStoredId(evidence.id) ?: throw error
                    requireCompatible(concurrent, evidence)
                    return EvidenceAppendResolution.Existing(concurrent)
                }
            }
            return EvidenceAppendResolution.Existing(canonical)
        }

        return try {
            dao.insertReleaseEvidence(entity)
            EvidenceAppendResolution.Inserted(evidence)
        } catch (error: SQLiteConstraintException) {
            val concurrent = resolveStoredId(evidence.id)
            if (concurrent != null) {
                requireCompatible(concurrent, evidence)
                EvidenceAppendResolution.Existing(concurrent)
            } else {
                val fingerprintRace = dao.getReleaseEvidenceByFingerprint(entity.canonicalFingerprint)
                require(fingerprintRace.size <= 1) { "duplicate active Evidence fingerprints" }
                val canonical = fingerprintRace.singleOrNull()?.let(::decodeActive) ?: throw error
                requireCompatible(canonical, evidence)
                check(canonical.id == evidence.id ||
                    ReleaseEvidenceFingerprintV2.isValidEvidenceId(canonical) ||
                    legacyEvidenceId(canonical) == canonical.id
                ) { "cannot alias an opaque active Evidence ID" }
                if (canonical.id != evidence.id) {
                    dao.insertEvidenceAlias(
                        ReleaseEvidenceAliasEntity(
                            aliasId = evidence.id,
                            canonicalEvidenceId = canonical.id,
                            canonicalFingerprint = entity.canonicalFingerprint,
                            aliasKind = "RUNTIME_V2",
                            createdAt = evidence.observedAt.toString(),
                        ),
                    )
                }
                EvidenceAppendResolution.Existing(canonical)
            }
        }
    }

    suspend fun resolveStoredId(id: String): ReleaseEvidence? {
        val physical = dao.getReleaseEvidence(id)
        val alias = dao.getEvidenceAlias(id)
        check(physical == null || alias == null) { "active Evidence and alias share an ID" }
        if (physical != null) return decodeActive(physical)
        if (alias == null) return null

        check(alias.aliasId == id && alias.aliasId != alias.canonicalEvidenceId) {
            "invalid Evidence alias namespace"
        }
        check(ReleaseEvidenceFingerprintV2.isValidFingerprint(alias.canonicalFingerprint)) {
            "invalid Evidence alias fingerprint"
        }
        Instant.parse(alias.createdAt)
        val target = dao.getReleaseEvidence(alias.canonicalEvidenceId)
            ?: error("dangling Evidence alias")
        check(dao.getEvidenceAlias(target.id) == null) { "Evidence alias chain or namespace collision" }
        val canonical = decodeActive(target)
        check(alias.canonicalFingerprint == target.canonicalFingerprint &&
            alias.canonicalFingerprint == ReleaseEvidenceFingerprintV2.compute(canonical)
        ) { "Evidence alias fingerprint does not match its target" }

        when (alias.aliasKind) {
            "MIGRATED_LEGACY", "MIGRATED_V2" -> verifyMigratedAlias(alias, canonical)
            "RUNTIME_V2" -> {
                check(ReleaseEvidenceFingerprintV2.isV2Id(alias.aliasId) &&
                    alias.aliasId == ReleaseEvidenceFingerprintV2.evidenceId(canonical)
                ) { "runtime alias is not the canonical v2 ID" }
                check(dao.getEvidenceDuplicateArchive(alias.aliasId) == null) {
                    "runtime alias unexpectedly has a migration archive"
                }
            }
            else -> error("unknown Evidence alias kind")
        }
        return canonical
    }

    suspend fun canonicalizeDecision(decision: ReleaseDecision): ReleaseDecision {
        suspend fun resolve(ids: List<String>): List<String> = ids.map { id ->
            val evidence = resolveStoredId(id) ?: error("Decision references missing Evidence: $id")
            require(evidence.identityKey == decision.identityKey) {
                "Decision references Evidence for another identity"
            }
            evidence.id
        }.distinct()

        val contributing = resolve(decision.contributingEvidenceIds)
        val authoritative = resolve(decision.authoritativeEvidenceIds)
        require(authoritative.all(contributing::contains)) {
            "Decision authoritative Evidence is not contributing"
        }
        return decision.copy(
            contributingEvidenceIds = contributing,
            authoritativeEvidenceIds = authoritative,
        )
    }

    suspend fun ensureForecastRevision(evidence: ReleaseEvidence) {
        val derived = ReleaseForecastRevision.fromEvidence(evidence) ?: return
        val expected = derived.toEntityOrNull()
            ?: error("canonical ForecastRevision exceeds persistence bounds")
        val existing = dao.getForecastRevisionByEvidenceId(evidence.id)
        if (existing != null) {
            val stored = existing.toDomainOrNull() ?: error("malformed stored ForecastRevision")
            check(stored == derived) { "stored ForecastRevision disagrees with canonical Evidence" }
            return
        }
        try {
            dao.insertForecastRevision(expected)
        } catch (error: SQLiteConstraintException) {
            val raced = dao.getForecastRevisionByEvidenceId(evidence.id)
                ?: throw error
            val stored = raced.toDomainOrNull() ?: error("malformed raced ForecastRevision")
            check(stored == derived) { "raced ForecastRevision disagrees with canonical Evidence" }
        }
    }

    suspend fun canonicalDecisionOrThrow(row: com.axiel7.anihyou.release.data.db.ReleaseDecisionEntity): ReleaseDecision {
        val decoded = row.toDomainOrNull() ?: error("malformed stored Decision")
        return canonicalizeDecision(decoded)
    }

    private fun decodeActive(row: ReleaseEvidenceEntity): ReleaseEvidence =
        row.toDomainOrNull() ?: error("malformed active Evidence row: ${row.id}")

    private suspend fun verifyMigratedAlias(
        alias: ReleaseEvidenceAliasEntity,
        canonical: ReleaseEvidence,
    ) {
        val archive = dao.getEvidenceDuplicateArchive(alias.aliasId)
            ?: error("migrated alias has no displaced Evidence archive")
        check(archive.canonicalEvidenceId == alias.canonicalEvidenceId &&
            archive.canonicalFingerprint == alias.canonicalFingerprint
        ) { "migrated alias disagrees with its archive" }
        val original = archive.toEvidenceEntity().toDomainOrNull()
            ?: error("malformed displaced Evidence archive")
        check(ReleaseEvidenceFingerprintV2.compute(original) == alias.canonicalFingerprint &&
            ReleaseEvidenceFingerprintV2.isSemanticallyCompatible(canonical, original)
        ) { "displaced Evidence archive disagrees with its canonical row" }
        when (alias.aliasKind) {
            "MIGRATED_LEGACY" -> check(legacyEvidenceId(original) == alias.aliasId) {
                "legacy alias ID disagrees with its archive"
            }
            "MIGRATED_V2" -> check(ReleaseEvidenceFingerprintV2.evidenceId(original) == alias.aliasId) {
                "v2 alias ID disagrees with its archive"
            }
        }
        check(canonical.id == alias.canonicalEvidenceId)
    }

    private fun ReleaseEvidenceDuplicateArchiveEntity.toEvidenceEntity() = ReleaseEvidenceEntity(
        id = originalId,
        canonicalFingerprint = canonicalFingerprint,
        identityKey = identityKey,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        sourceHash = sourceHash,
        parserVersion = parserVersion,
        observedAt = observedAt,
        sourceReportedAt = sourceReportedAt,
        approximateTime = approximateTime,
        siteIdentifierPayload = siteIdentifierPayload,
        sourceSeason = sourceSeason,
        navigationSeason = navigationSeason,
        installmentPayload = installmentPayload,
        languageTrack = languageTrack,
        evidenceType = evidenceType,
        scheduleCondition = scheduleCondition,
        confidenceSource = confidenceSource,
        confidenceIdentity = confidenceIdentity,
        confidenceInstallment = confidenceInstallment,
        confidenceLanguageTrack = confidenceLanguageTrack,
        confidenceTiming = confidenceTiming,
    )

    private fun requireCompatible(stored: ReleaseEvidence, incoming: ReleaseEvidence) {
        check(ReleaseEvidenceFingerprintV2.compute(stored) == ReleaseEvidenceFingerprintV2.compute(incoming) &&
            ReleaseEvidenceFingerprintV2.isSemanticallyCompatible(stored, incoming)
        ) { "Evidence fingerprint collision or non-hashed semantic mismatch" }
    }
}

class RoomReleaseEvidenceRepository(
    private val database: ReleaseDatabase,
) : ReleaseEvidenceRepository, ReleaseForecastRevisionRepository {
    private val dao = database.releaseDao()
    private val store = RoomReleaseEvidenceStore(database)

    override suspend fun append(evidence: ReleaseEvidence): Boolean = database.withTransaction {
        check(database.reconciliationDao().baselineMarker() == null) {
            "new Evidence must be committed through the canonical cycle path"
        }
        when (val resolved = store.resolveOrAppend(evidence)) {
            is EvidenceAppendResolution.Inserted -> {
                store.ensureForecastRevision(resolved.canonicalEvidence)
                true
            }
            is EvidenceAppendResolution.Existing -> {
                store.ensureForecastRevision(resolved.canonicalEvidence)
                false
            }
            is EvidenceAppendResolution.Rejected -> false
        }
    }

    override suspend fun findById(id: String): ReleaseEvidence? =
        database.withTransaction { store.resolveStoredId(id) }

    override fun observeFor(identityKey: String): Flow<List<ReleaseEvidence>> =
        dao.observeReleaseEvidence(identityKey).map { rows ->
            rows.map { it.toDomainOrNull() ?: error("malformed active Evidence row: ${it.id}") }
        }

    override suspend fun append(revision: ReleaseForecastRevision): Boolean = database.withTransaction {
        check(database.reconciliationDao().baselineMarker() == null) {
            "ForecastRevision must be committed through the canonical cycle path"
        }
        val evidence = store.resolveStoredId(revision.evidenceId) ?: return@withTransaction false
        val derived = ReleaseForecastRevision.fromEvidence(evidence) ?: return@withTransaction false
        if (derived != revision) return@withTransaction false
        store.ensureForecastRevision(evidence)
        true
    }

    override fun observeForecastFor(identityKey: String): Flow<List<ReleaseForecastRevision>> =
        dao.observeForecastRevisions(identityKey).map { rows ->
            database.withTransaction {
                rows.map { row ->
                    val revision = row.toDomainOrNull() ?: error("malformed ForecastRevision")
                    val evidence = store.resolveStoredId(revision.evidenceId)
                        ?: error("ForecastRevision references missing Evidence")
                    ReleaseForecastRevision.fromEvidence(evidence)
                        ?: error("ForecastRevision references non-forecast Evidence")
                }
            }
        }
}

class RoomReleaseDecisionRepository(
    private val database: ReleaseDatabase,
) : ReleaseDecisionRepository {
    private val dao = database.releaseDao()
    private val store = RoomReleaseEvidenceStore(database)

    override suspend fun get(identityKey: String): ReleaseDecision? = database.withTransaction {
        dao.getReleaseDecision(identityKey)?.let { store.canonicalDecisionOrThrow(it) }
    }

    override fun observe(identityKey: String): Flow<ReleaseDecision?> =
        dao.observeReleaseDecision(identityKey).map { row ->
            if (row == null) null else {
                database.withTransaction { store.canonicalDecisionOrThrow(row) }
            }
        }

    override suspend fun put(decision: ReleaseDecision): Boolean = database.withTransaction {
        check(database.reconciliationDao().baselineMarker() == null) {
            "legacy Decisions are read-only after canonical baseline import"
        }
        val incoming = try {
            store.canonicalizeDecision(decision)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IllegalArgumentException) {
            return@withTransaction false
        } catch (_: IllegalStateException) {
            return@withTransaction false
        }
        val previousEntity = dao.getReleaseDecision(decision.identityKey)
        val current = previousEntity?.let { store.canonicalDecisionOrThrow(it) }
        when (ReleaseDecisionPersistencePolicy.evaluate(current, incoming)) {
            DecisionPersistenceResult.ACCEPT -> {
                val entity = incoming.toEntityOrNull() ?: return@withTransaction false
                dao.upsertReleaseDecision(entity)
                true
            }
            DecisionPersistenceResult.IDEMPOTENT -> true
            DecisionPersistenceResult.REJECT -> false
        }
    }
}

class RoomSourceHealthRepository(
    private val database: ReleaseDatabase,
) : SourceHealthRepository {
    private val dao = database.releaseDao()

    override suspend fun get(sourceType: ReleaseSourceType): SourceHealth? =
        dao.getSourceHealth(sourceType.name)?.toDomainOrNull()

    override fun observe(sourceType: ReleaseSourceType): Flow<SourceHealth?> =
        dao.observeSourceHealth(sourceType.name).map { it?.toDomainOrNull() }

    override suspend fun put(health: SourceHealth) {
        database.withTransaction {
            val current = dao.getSourceHealth(health.sourceType.name)?.toDomainOrNull()
            SourceHealthPersistencePolicy.merge(current, health)
                .toEntityOrNull()
                ?.let { dao.upsertSourceHealth(it) }
        }
    }
}

/** Atomic V3 write boundary for a batch of source results. */
class RoomReleaseIntelligencePersistence(
    private val database: ReleaseDatabase,
    private val reducer: ReleaseAuthorityReducer,
) {
    private val dao = database.releaseDao()
    private val store = RoomReleaseEvidenceStore(database)

    suspend fun persist(
        results: Collection<SourceResult<List<ReleaseEvidence>>>,
    ): List<ReleaseDecision> = database.withTransaction {
        check(database.reconciliationDao().baselineMarker() == null) {
            "legacy ingestion is read-only after canonical baseline import"
        }
        val decisions = linkedMapOf<String, ReleaseDecision>()
        results.forEach { result ->
            val sourceHealth = when (result) {
                is SourceResult.Success -> result.sourceHealth
                is SourceResult.PartialSuccess -> result.sourceHealth
                is SourceResult.Failure -> result.sourceHealth
            }
            sourceHealth?.let { incoming ->
                val current = dao.getSourceHealth(incoming.sourceType.name)?.toDomainOrNull()
                SourceHealthPersistencePolicy.merge(current, incoming)
                    .toEntityOrNull()
                    ?.let { dao.upsertSourceHealth(it) }
            }
            val evidence = when (result) {
                is SourceResult.Success -> result.value
                is SourceResult.PartialSuccess -> result.value
                is SourceResult.Failure -> emptyList()
            }
            evidence.forEach { item ->
                val resolution = store.resolveOrAppend(item)
                val canonicalEvidence = when (resolution) {
                    is EvidenceAppendResolution.Inserted -> resolution.canonicalEvidence
                    is EvidenceAppendResolution.Existing -> resolution.canonicalEvidence
                    is EvidenceAppendResolution.Rejected -> return@forEach
                }
                store.ensureForecastRevision(canonicalEvidence)

                val previousEntity = dao.getReleaseDecision(canonicalEvidence.identityKey)
                val previous = previousEntity?.let { store.canonicalDecisionOrThrow(it) }
                if (previous != null &&
                    canonicalEvidence.id in previous.contributingEvidenceIds
                ) {
                    decisions[canonicalEvidence.identityKey] = previous
                    return@forEach
                }

                val next = try {
                    reducer.reduce(previous, canonicalEvidence)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    null
                }
                    ?: return@forEach
                val canonicalNext = store.canonicalizeDecision(next)
                when (ReleaseDecisionPersistencePolicy.evaluate(previous, canonicalNext)) {
                    DecisionPersistenceResult.ACCEPT -> {
                        canonicalNext.toEntityOrNull()?.let {
                            dao.upsertReleaseDecision(it)
                            decisions[canonicalEvidence.identityKey] = canonicalNext
                        }
                    }
                    DecisionPersistenceResult.IDEMPOTENT -> {
                        previous?.let { decisions[canonicalEvidence.identityKey] = it }
                    }
                    DecisionPersistenceResult.REJECT -> Unit
                }
            }
        }
        decisions.values.toList()
    }
}

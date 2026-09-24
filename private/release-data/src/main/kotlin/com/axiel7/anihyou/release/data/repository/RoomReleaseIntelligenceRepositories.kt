package com.axiel7.anihyou.release.data.repository

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
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.db.toEntityOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomReleaseEvidenceRepository(
    private val database: ReleaseDatabase,
) : ReleaseEvidenceRepository, ReleaseForecastRevisionRepository {
    private val dao = database.releaseDao()

    override suspend fun append(evidence: ReleaseEvidence): Boolean =
        database.withTransaction {
            val entity = evidence.toEntityOrNull() ?: return@withTransaction false
            val inserted = dao.insertReleaseEvidence(entity) != -1L
            if (inserted) {
                ReleaseForecastRevision.fromEvidence(evidence)?.let { revision ->
                    revision.toEntityOrNull()?.let(dao::insertForecastRevision)
                }
            }
            inserted
        }

    override suspend fun findById(id: String): ReleaseEvidence? =
        dao.getReleaseEvidence(id)?.toDomainOrNull()

    override fun observeFor(identityKey: String): Flow<List<ReleaseEvidence>> =
        dao.observeReleaseEvidence(identityKey).map { rows ->
            rows.mapNotNull { it.toDomainOrNull() }
        }

    override suspend fun append(revision: ReleaseForecastRevision): Boolean =
        database.withTransaction {
            val evidence = dao.getReleaseEvidence(revision.evidenceId)
                ?.toDomainOrNull()
                ?: return@withTransaction false
            val derived = ReleaseForecastRevision.fromEvidence(evidence)
                ?: return@withTransaction false
            if (derived != revision || derived.identityKey != revision.identityKey) {
                return@withTransaction false
            }
            val entity = revision.toEntityOrNull() ?: return@withTransaction false
            if (dao.insertForecastRevision(entity) != -1L) {
                true
            } else {
                dao.getForecastRevisionByEvidenceId(revision.evidenceId)
                    ?.toDomainOrNull() == revision
            }
        }

    override fun observeForecastFor(identityKey: String): Flow<List<ReleaseForecastRevision>> =
        dao.observeForecastRevisions(identityKey).map { rows ->
            rows.mapNotNull { it.toDomainOrNull() }
        }
}

class RoomReleaseDecisionRepository(
    private val database: ReleaseDatabase,
) : ReleaseDecisionRepository {
    private val dao = database.releaseDao()

    override suspend fun get(identityKey: String): ReleaseDecision? =
        dao.getReleaseDecision(identityKey)?.toDomainOrNull()

    override fun observe(identityKey: String): Flow<ReleaseDecision?> =
        dao.observeReleaseDecision(identityKey).map { it?.toDomainOrNull() }

    override suspend fun put(decision: ReleaseDecision): Boolean =
        database.withTransaction {
            val current = dao.getReleaseDecision(decision.identityKey)?.toDomainOrNull()
            when (ReleaseDecisionPersistencePolicy.evaluate(current, decision)) {
                DecisionPersistenceResult.ACCEPT -> {
                    val entity = decision.toEntityOrNull() ?: return@withTransaction false
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

    override suspend fun get(sourceType: com.axiel7.anihyou.release.core.model.ReleaseSourceType): SourceHealth? =
        dao.getSourceHealth(sourceType.name)?.toDomainOrNull()

    override fun observe(
        sourceType: com.axiel7.anihyou.release.core.model.ReleaseSourceType,
    ): Flow<SourceHealth?> =
        dao.observeSourceHealth(sourceType.name).map { it?.toDomainOrNull() }

    override suspend fun put(health: SourceHealth) {
        database.withTransaction {
            val current = dao.getSourceHealth(health.sourceType.name)?.toDomainOrNull()
            SourceHealthPersistencePolicy.merge(current, health)
                .toEntityOrNull()
                ?.let(dao::upsertSourceHealth)
        }
    }
}

/**
 * Atomic V3 write boundary for a batch of source results. It persists source
 * health even when a source fails, appends each new evidence row, records a
 * forecast revision and materializes the reducer decision in one transaction.
 */
class RoomReleaseIntelligencePersistence(
    private val database: ReleaseDatabase,
    private val reducer: ReleaseAuthorityReducer,
) {
    private val dao = database.releaseDao()

    suspend fun persist(
        results: Collection<SourceResult<List<ReleaseEvidence>>>,
    ): List<ReleaseDecision> = database.withTransaction {
        val decisions = linkedMapOf<String, ReleaseDecision>()
        results.forEach { result ->
            result.sourceHealth?.let { incoming ->
                val current = dao.getSourceHealth(incoming.sourceType.name)?.toDomainOrNull()
                SourceHealthPersistencePolicy.merge(current, incoming)
                    .toEntityOrNull()
                    ?.let(dao::upsertSourceHealth)
            }
            val evidence = when (result) {
                is SourceResult.Success -> result.value
                is SourceResult.PartialSuccess -> result.value
                is SourceResult.Failure -> emptyList()
            }
            evidence.forEach { item ->
                val entity = item.toEntityOrNull() ?: return@forEach
                if (dao.insertReleaseEvidence(entity) == -1L) return@forEach
                ReleaseForecastRevision.fromEvidence(item)?.let { revision ->
                    revision.toEntityOrNull()?.let(dao::insertForecastRevision)
                }
                val previous = dao.getReleaseDecision(item.identityKey)?.toDomainOrNull()
                val next = runCatching { reducer.reduce(previous, item) }.getOrNull()
                    ?: return@forEach
                when (ReleaseDecisionPersistencePolicy.evaluate(previous, next)) {
                    DecisionPersistenceResult.ACCEPT -> {
                        next.toEntityOrNull()?.let {
                            dao.upsertReleaseDecision(it)
                            decisions[item.identityKey] = next
                        }
                    }
                    DecisionPersistenceResult.IDEMPOTENT -> {
                        previous?.let { decisions[item.identityKey] = it }
                    }
                    DecisionPersistenceResult.REJECT -> Unit
                }
            }
        }
        decisions.values.toList()
    }
}

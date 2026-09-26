package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.state.ReleaseConflictPolicy
import com.axiel7.anihyou.release.core.state.ReleaseCycleReconciler
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import com.axiel7.anihyou.release.data.db.*
import java.security.MessageDigest
import java.time.Instant

/** The sole writer for canonical V3 projections after the v11 snapshot has been imported. */
class RoomReleaseReconciliationRepository(private val database: ReleaseDatabase) {
    private val dao = database.reconciliationDao()
    private val evidenceStore = RoomReleaseEvidenceStore(database)
    private val reconciler = ReleaseCycleReconciler()

    suspend fun importBaseline(): Int = database.withTransaction {
        dao.baselineMarker()?.let {
            check(it.schemaVersion == 12 && it.value == "v1")
            return@withTransaction 0
        }
        val evidence = pageAll(dao::evidencePage).map { row ->
            val decoded = row.toDomainOrNull() ?: error("malformed baseline Evidence ${row.id}")
            check(row.canonicalFingerprint == ReleaseEvidenceFingerprintV2.compute(decoded))
            decoded
        }
        val states = linkedMapOf<String, CanonicalReleaseState>()
        val buckets = linkedMapOf<String, String>()
        val exactEvidence = evidence.groupBy { CanonicalReleaseIdentity.from(it) }
        exactEvidence.forEach { (identity, items) ->
            if (identity == null) return@forEach
            val positive = items.filter { it.positive() }.sortedWith(compareBy({ it.observedAt }, { it.id }))
            val forecasts = items.filter { it.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                it.evidenceType == ReleaseEvidenceType.FORECAST }
                .sortedWith(compareBy({ it.observedAt }, { it.id }))
            val latest = forecasts.lastOrNull()
            var state = CanonicalReleaseState(identity.key,
                underlyingPhase = if (positive.isNotEmpty()) ReleasePhase.RELEASED
                    else if (latest != null) ReleasePhase.EXPECTED else ReleasePhase.UNKNOWN,
                phase = if (positive.isNotEmpty()) ReleasePhase.RELEASED
                    else if (latest != null) ReleasePhase.EXPECTED else ReleasePhase.UNKNOWN,
                authority = if (positive.isNotEmpty()) ReleaseAuthority.ANIWORLD else ReleaseAuthority.NONE,
                releaseAt = positive.firstOrNull { !it.approximateTime }?.sourceReportedAt,
                forecastAt = latest?.sourceReportedAt,
                forecastEvidenceId = latest?.id,
                expectationEvidenceId = latest?.takeIf { !it.approximateTime && it.sourceReportedAt != null }?.id,
            )
            val timeClaims = positive.filter { !it.approximateTime }.mapNotNull { it.sourceReportedAt }.distinct()
            if (timeClaims.size > 1) {
                state = ReleaseConflictPolicy.open(state, ReleaseConflict(
                    "legacy-time:${identity.key}", ReleaseConflictKind.PUBLICATION_TIME_DISAGREEMENT,
                    positive.map { it.id }.toSet(), true,
                ))
            }
            states[identity.key] = state
            buckets[identity.key] = identity.bucketKey
        }
        val partial = evidence.filter { it.identityCompleteness() == ReleaseIdentityCompleteness.PARTIAL }
        partial.forEach { item ->
            val bucket = bucketOf(item)
            val key = "partial-v1:${item.id}"
            states[key] = CanonicalReleaseState(key,
                underlyingPhase = if (item.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                    item.evidenceType == ReleaseEvidenceType.FORECAST) ReleasePhase.EXPECTED else ReleasePhase.UNKNOWN,
                phase = if (item.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                    item.evidenceType == ReleaseEvidenceType.FORECAST) ReleasePhase.EXPECTED else ReleasePhase.UNKNOWN,
            )
            buckets[key] = bucket
        }
        // Preserve every old Decision row byte for byte. Its authority is independently
        // checked against physical canonical Evidence before it can affect the new view.
        val old = pageAll(dao::decisionPage).map { raw ->
            raw to evidenceStore.canonicalDecisionOrThrow(raw)
        }.sortedWith(compareBy({ it.second.decidedAt }, { it.first.identityKey }))
        for ((raw, legacy) in old) {
            val referenced = legacy.authoritativeEvidenceIds.map { id ->
                evidenceStore.resolveStoredId(id) ?: error("dangling legacy authority reference")
            }
            val valid = referenced.filter { it.positive() }
            val key = valid.mapNotNull(CanonicalReleaseIdentity::from).map { it.key }.distinct()
            if (legacy.phase == ReleasePhase.RELEASED && key.size == 1 &&
                valid.size == referenced.size && valid.isNotEmpty()) {
                val target = key.single()
                val previous = states[target] ?: error("missing exact Evidence projection")
                states[target] = previous.copy(
                    underlyingPhase = ReleasePhase.RELEASED, phase = ReleasePhase.RELEASED,
                    authority = ReleaseAuthority.ANIWORLD,
                    releaseAt = previous.releaseAt ?: legacy.releaseAt,
                )
            } else if (legacy.phase == ReleasePhase.RELEASED && key.isEmpty()) {
                val holder = referenced.firstOrNull() ?: continue
                val partialKey = "partial-v1:${holder.id}"
                val before = states[partialKey] ?: CanonicalReleaseState(partialKey)
                states[partialKey] = ReleaseConflictPolicy.open(before, ReleaseConflict(
                    "legacy-incomplete:${raw.identityKey}", ReleaseConflictKind.LEGACY_INCOMPLETE_AUTHORITY,
                    referenced.map { it.id }.toSet(), true,
                ))
                buckets[partialKey] = bucketOf(holder)
            }
        }
        // Baseline events are individually replayable; no invented historical cycles.
        states.toSortedMap().entries.forEachIndexed { ordinal, (key, state) ->
            val snapshot = state.copy(revision = 1)
            val row = ReleaseReconciliationMapper.projection(snapshot, buckets.getValue(key), 0)
            dao.insertEvent(ReconciliationEventEntity(
                eventId = digest("baseline:$key"), commitSequence = 0,
                eventOrdinal = ordinal, kind = "BASELINE_IMPORT", payloadVersion = 1,
                projectionKey = key, partialEvidenceId = key.removePrefix("partial-v1:")
                    .takeIf { key.startsWith("partial-v1:") },
                fromKey = null, toKey = null, causeCycleId = null, policyVersion = 1,
                beforeRevision = 0, afterRevision = 1,
                payload = ReleaseReconciliationMapper.eventPayload(row),
            ))
            dao.upsertProjection(row)
        }
        dao.insertMarker(SchemaMetaEntity("BASELINE_IMPORT_COMPLETE", 12, "v1",
            Instant.EPOCH.toString()))
        states.size
    }

    suspend fun persistCompletedCycle(cycle: CompletedObservationCycle): Map<String, CanonicalReleaseState> =
        database.withTransaction {
            check(dao.baselineMarker()?.let { it.schemaVersion == 12 && it.value == "v1" } == true) {
                "canonical path requires complete baseline import"
            }
            val requestDigest = digest(cycleRequest(cycle))
            dao.cycle(cycle.id)?.let { stored ->
                check(stored.requestDigest == requestDigest && stored.policyVersion == cycle.policy.version) {
                    "cycle ID reused for a different request"
                }
                return@withTransaction emptyMap()
            }
            val latest = dao.latestCompletion(cycle.scopeId)?.let(Instant::parse)
            val late = latest != null && cycle.completedAt < latest
            val resolved = cycle.sources.map { source ->
                check(source.supersedesEvidenceIds.size <= 1) {
                    "multiple supersession claims require separate typed receipts"
                }
                val observations = source.evidence.map { incoming ->
                    check(incoming.sourceType == source.sourceType) { "source instance role mismatch" }
                    val canonical = when (val result = evidenceStore.resolveOrAppend(incoming)) {
                        is EvidenceAppendResolution.Inserted -> result.canonicalEvidence
                        is EvidenceAppendResolution.Existing -> result.canonicalEvidence
                        is EvidenceAppendResolution.Rejected -> error(result.reason)
                    }
                    evidenceStore.ensureForecastRevision(canonical)
                    check(canonical.id == incoming.id ||
                        evidenceStore.resolveStoredId(incoming.id)?.id == canonical.id)
                    canonical
                }.distinctBy { it.id }
                source.copy(evidence = observations)
            }
            val affectedBuckets = resolved.flatMap { it.evidence }.mapNotNull { item ->
                if (item.identityCompleteness() == ReleaseIdentityCompleteness.NON_BINDABLE) null
                else bucketOf(item)
            }.toSet()
            val previousRows = affectedBuckets.flatMap { bucket ->
                pageAll { limit, offset -> dao.projectionsForBucket(bucket, limit, offset) }
            }
            val previous = previousRows.associate { row ->
                row.projectionKey to ReleaseReconciliationMapper.state(row)
            }
            val storedPartials = previousRows.filter { it.projectionKey.startsWith("partial-v1:") }
                .map { row ->
                    val id = row.projectionKey.removePrefix("partial-v1:")
                    dao.evidenceById(id)?.toDomainOrNull() ?: error("partial projection has no Evidence")
                }
            val newPartials = resolved.flatMap { it.evidence }
                .filter { it.identityCompleteness() == ReleaseIdentityCompleteness.PARTIAL }
            val normalized = cycle.copy(sources = resolved)
            val plan = reconciler.reconcile(previous, normalized,
                (storedPartials + newPartials).distinctBy { it.id }, true, late)
            val sequence = dao.lastSequence() + 1
            val metadata = "v1:${cycle.policy.grace.seconds}:${cycle.policy.minimumSeparation.seconds}:" +
                cycle.policy.maximumCycleDuration.seconds
            dao.insertCycle(ObservationCycleEntity(cycle.id, sequence, cycle.scopeId,
                cycle.policy.version, metadata, cycle.startedAt.toString(), cycle.completedAt.toString(),
                if (cycle.sources.any { it.result == CycleResult.INCOMPLETE }) "INCOMPLETE" else "COMPLETE",
                requestDigest))
            resolved.forEach { source ->
                dao.insertSource(CycleSourceObservationEntity(cycle.id, source.instanceId,
                    source.sourceType.name, source.targetKey, source.track?.name, source.result.name,
                    source.health.name, source.coverage.name, source.presence.name,
                    source.observedAt?.toString(), source.supersedesEvidenceIds.singleOrNull()))
                source.evidence.forEach { item ->
                    dao.insertReceipt(CycleEvidenceReceiptEntity(cycle.id, source.instanceId, item.id))
                }
            }
            plan.changes.forEachIndexed { ordinal, change ->
                val bucket = previousRows.firstOrNull { it.projectionKey == change.key }?.bucketKey
                    ?: resolved.flatMap { it.evidence }.firstOrNull {
                        CanonicalReleaseIdentity.from(it)?.key == change.key ||
                            "partial-v1:${it.id}" == change.key
                    }?.let(::bucketOf) ?: error("missing candidate bucket for changed projection")
                val row = ReleaseReconciliationMapper.projection(change.after, bucket, sequence)
                dao.insertEvent(ReconciliationEventEntity(
                    digest("${cycle.id}:$ordinal"), sequence, ordinal, change.kind, 1, change.key,
                    change.evidenceId?.takeIf { change.key.startsWith("partial-v1:") },
                    change.before?.bindingKey, change.after.bindingKey, cycle.id, cycle.policy.version,
                    change.before?.revision ?: 0, change.after.revision,
                    ReleaseReconciliationMapper.eventPayload(row),
                ))
                dao.upsertProjection(row)
            }
            plan.states
        }

    suspend fun get(key: String): CanonicalReleaseState? = database.withTransaction {
        check(dao.baselineMarker() != null)
        dao.projection(key)?.let(ReleaseReconciliationMapper::state)
    }

    suspend fun history(key: String, limit: Int, offset: Int): List<ReconciliationEventEntity> {
        require(limit in 1..256 && offset >= 0)
        check(dao.baselineMarker() != null)
        return dao.eventsFor(key, limit, offset).onEach { event ->
            check(event.payloadVersion == 1)
            check(ReleaseReconciliationMapper.eventProjection(event.payload).projectionKey == key)
        }
    }

    suspend fun rebuildProjections(): Int = database.withTransaction {
        check(dao.baselineMarker() != null)
        val current = pageAll(dao::projectionPage).associateBy { it.projectionKey }
        val replay = linkedMapOf<String, CanonicalReleaseProjectionEntity>()
        pageAll(dao::eventPage).forEach { event ->
            check(event.payloadVersion == 1)
            val row = ReleaseReconciliationMapper.eventProjection(event.payload)
            check(row.projectionKey == event.projectionKey &&
                row.revision == event.afterRevision && row.lastAppliedSequence == event.commitSequence)
            check((replay[row.projectionKey]?.revision ?: 0) == event.beforeRevision)
            replay[row.projectionKey] = row
        }
        check(current.isEmpty() || replay == current) {
            "materialized projection differs from journal"
        }
        dao.clearProjections()
        replay.values.forEach { dao.upsertProjection(it) }
        replay.size
    }

    private suspend fun <T> pageAll(fetch: suspend (Int, Int) -> List<T>): List<T> {
        val result = ArrayList<T>()
        while (true) {
            val page = fetch(256, result.size)
            result.addAll(page)
            if (page.size < 256) break
            check(result.size < 1_000_000) { "unbounded reconciliation history" }
        }
        return result
    }

    private fun bucketOf(e: ReleaseEvidence): String {
        val path = e.siteIdentifier?.canonicalSeriesPath ?: error("candidate lacks canonical series")
        val part = e.installment
        return lengthKey("canonical-bucket-v1", "aniworld", path, part.stableKey)
    }

    private fun lengthKey(vararg fields: String): String = buildString {
        fields.forEach { field -> append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field) }
    }

    private fun ReleaseEvidence.positive(): Boolean = CanonicalReleaseIdentity.from(this) != null &&
        confidence.overall > 0.0 &&
        ((sourceType == ReleaseSourceType.ANIWORLD_RECENT && evidenceType == ReleaseEvidenceType.CONFIRMATION) ||
            (sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE && evidenceType == ReleaseEvidenceType.VERIFICATION))

    private fun cycleRequest(cycle: CompletedObservationCycle): String = lengthKey(
        cycle.id, cycle.scopeId, cycle.startedAt.toString(), cycle.completedAt.toString(),
        cycle.policy.version.toString(), cycle.policy.grace.seconds.toString(),
        cycle.policy.minimumSeparation.seconds.toString(), cycle.policy.maximumCycleDuration.seconds.toString(),
        *cycle.sources.sortedBy { it.instanceId }.map { source ->
            lengthKey(source.instanceId, source.sourceType.name, source.targetKey,
                source.track?.name ?: "", source.result.name, source.health.name, source.coverage.name,
                source.presence.name, source.observedAt?.toString() ?: "",
                *source.evidence.map { lengthKey(it.id, ReleaseEvidenceFingerprintV2.compute(it),
                    it.toString()) }.sorted().toTypedArray(),
                *source.supersedesEvidenceIds.sorted().toTypedArray())
        }.toTypedArray(),
    )

    private fun digest(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

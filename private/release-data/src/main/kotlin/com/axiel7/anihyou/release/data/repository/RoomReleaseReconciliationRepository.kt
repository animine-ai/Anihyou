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
            val corrections = items.filter { it.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT &&
                it.evidenceType == ReleaseEvidenceType.CORRECTION &&
                it.scheduleCondition != ScheduleCondition.UNKNOWN }
                .sortedWith(compareBy({ it.observedAt }, { it.id }))
            val schedule = corrections.map { it.scheduleCondition }.distinct()
            var state = CanonicalReleaseState(identity.key,
                underlyingPhase = if (positive.isNotEmpty()) ReleasePhase.RELEASED
                    else if (latest != null) ReleasePhase.EXPECTED else ReleasePhase.UNKNOWN,
                phase = if (positive.isNotEmpty()) ReleasePhase.RELEASED
                    else if (latest != null) ReleasePhase.EXPECTED else ReleasePhase.UNKNOWN,
                authority = if (positive.isNotEmpty()) ReleaseAuthority.ANIWORLD else ReleaseAuthority.NONE,
                scheduleCondition = if (schedule.size == 1) schedule.single() else ScheduleCondition.UNKNOWN,
                scheduleEvidenceId = if (schedule.size == 1) corrections.first().id else null,
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
            if (schedule.size > 1) {
                state = ReleaseConflictPolicy.open(state, ReleaseConflict(
                    "legacy-schedule:${identity.key}", ReleaseConflictKind.SCHEDULE_DISAGREEMENT,
                    corrections.map { it.id }.toSet(), true,
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
        val legacyAnchors = mutableMapOf<String, Instant>()
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
                val anchor = legacyAnchors[target]
                var merged = previous.copy(
                    underlyingPhase = ReleasePhase.RELEASED, phase = ReleasePhase.RELEASED,
                    authority = ReleaseAuthority.ANIWORLD,
                    releaseAt = anchor ?: legacy.releaseAt ?: previous.releaseAt,
                )
                if (legacy.releaseAt != null) {
                    if (anchor == null) {
                        legacyAnchors[target] = legacy.releaseAt
                        if (previous.releaseAt != null && previous.releaseAt != legacy.releaseAt) {
                            merged = ReleaseConflictPolicy.open(merged, ReleaseConflict(
                                "legacy-evidence-time:$target:${raw.identityKey}",
                                ReleaseConflictKind.PUBLICATION_TIME_DISAGREEMENT,
                                valid.map { it.id }.toSet(), true,
                            ))
                        }
                    } else if (anchor != legacy.releaseAt) {
                        merged = ReleaseConflictPolicy.open(merged, ReleaseConflict(
                            "legacy-time:$target:${raw.identityKey}",
                            ReleaseConflictKind.PUBLICATION_TIME_DISAGREEMENT,
                            valid.map { it.id }.toSet(), true,
                        ))
                    }
                }
                states[target] = merged
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
                resolutionActor = null, resolutionReason = null,
            ))
            dao.upsertProjection(row)
        }
        old.forEachIndexed { index, (raw, legacy) ->
            val contributing = legacy.contributingEvidenceIds.mapNotNull { id ->
                evidenceStore.resolveStoredId(id)
            }
            val keys = contributing.mapNotNull(CanonicalReleaseIdentity::from).map { it.key }.distinct()
            val target = if (keys.size == 1) keys.single() else contributing.firstOrNull()?.let {
                "partial-v1:${it.id}"
            }
            val projection = target?.let { dao.projection(it) } ?: return@forEachIndexed
            dao.insertEvent(ReconciliationEventEntity(
                eventId = digest("legacy-decision:${raw.identityKey}"), commitSequence = 0,
                eventOrdinal = states.size + index, kind = "BASELINE_LEGACY_DECISION",
                payloadVersion = 1, projectionKey = projection.projectionKey,
                partialEvidenceId = null, fromKey = raw.identityKey,
                toKey = projection.projectionKey, causeCycleId = null,
                policyVersion = 1, beforeRevision = projection.revision,
                afterRevision = projection.revision,
                payload = ReleaseReconciliationMapper.eventPayload(projection),
                resolutionActor = null, resolutionReason = null,
            ))
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
                if (source.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE) {
                    check(CanonicalReleaseIdentity.decode(source.targetKey)?.track == source.track) {
                        "direct source target/track mismatch"
                    }
                }
                val observations = source.evidence.map { incoming ->
                    check(incoming.sourceType == source.sourceType) { "source instance role mismatch" }
                    if (source.sourceType == ReleaseSourceType.ANIWORLD_DIRECT_PAGE) {
                        check(CanonicalReleaseIdentity.from(incoming)?.key == source.targetKey) {
                            "direct Evidence belongs to another target"
                        }
                    }
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
                source.supersedesEvidenceIds.forEach { id ->
                    val superseded = evidenceStore.resolveStoredId(id)
                        ?: error("supersession references missing Evidence")
                    check(source.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT &&
                        superseded.sourceType == source.sourceType && observations.any {
                            CanonicalReleaseIdentity.from(it) != null &&
                                CanonicalReleaseIdentity.from(it) == CanonicalReleaseIdentity.from(superseded)
                        }) { "unsafe correction supersession" }
                }
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
                row.projectionKey to verifyStateReferences(row)
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
            val bucketByKey = previousRows.associate { it.projectionKey to it.bucketKey } +
                resolved.flatMap { it.evidence }.mapNotNull { item ->
                    val key = CanonicalReleaseIdentity.from(item)?.key ?: if (
                        item.identityCompleteness() == ReleaseIdentityCompleteness.PARTIAL)
                        "partial-v1:${item.id}" else null
                    key?.let { it to bucketOf(item) }
                }.toMap()
            val sequence = dao.lastSequence() + 1
            val metadata = "v1:${cycle.policy.grace.seconds}:${cycle.policy.minimumSeparation.seconds}:" +
                cycle.policy.maximumCycleDuration.seconds
            dao.insertCycle(ObservationCycleEntity(cycle.id, sequence, cycle.scopeId,
                cycle.policy.version, metadata, cycle.startedAt.toString(), cycle.completedAt.toString(),
                if (cycle.sources.any { it.result == CycleResult.INCOMPLETE }) "INCOMPLETE" else "COMPLETE",
                requestDigest))
            resolved.forEach { source ->
                val expected = cycle.manifest.singleOrNull { it.instanceId == source.instanceId }
                dao.insertSource(CycleSourceObservationEntity(cycle.id, source.instanceId,
                    source.sourceType.name, source.targetKey, source.track?.name,
                    expected?.negativeRequired ?: false, source.result.name,
                    source.health.name, source.coverage.name, source.presence.name,
                    source.observedAt?.toString(), source.supersedesEvidenceIds.singleOrNull()))
                source.evidence.forEach { item ->
                    val projectionKey = CanonicalReleaseIdentity.from(item)?.key
                        ?: "partial-v1:${item.id}"
                    dao.insertReceipt(CycleEvidenceReceiptEntity(cycle.id, source.instanceId,
                        item.id, projectionKey))
                }
            }
            plan.changes.forEachIndexed { ordinal, change ->
                val bucket = bucketByKey[change.key]
                    ?: error("missing candidate bucket for changed projection")
                val row = ReleaseReconciliationMapper.projection(change.after, bucket, sequence)
                dao.insertEvent(ReconciliationEventEntity(
                    digest("${cycle.id}:$ordinal"), sequence, ordinal, change.kind, 1, change.key,
                    change.evidenceId?.takeIf { change.key.startsWith("partial-v1:") },
                    change.before?.bindingKey, change.after.bindingKey, cycle.id, cycle.policy.version,
                    change.before?.revision ?: 0, change.after.revision,
                    ReleaseReconciliationMapper.eventPayload(row),
                    null, null,
                ))
                dao.upsertProjection(row)
            }
            plan.states
        }

    suspend fun get(key: String): CanonicalReleaseState? = database.withTransaction {
        check(dao.baselineMarker() != null)
        dao.projection(key)?.let { verifyStateReferences(it) }
    }

    /** Explicit administrative resolution with durable actor and reason provenance. */
    suspend fun resolveConflict(key: String, conflictId: String,
                                actor: String, reason: String): CanonicalReleaseState =
        database.withTransaction {
            check(dao.baselineMarker() != null)
            require(actor.isNotBlank() && actor.length <= 256 &&
                reason.isNotBlank() && reason.length <= 4096)
            val priorRow = dao.projection(key) ?: error("unknown projection")
            val prior = ReleaseReconciliationMapper.state(priorRow)
            val next = ReleaseConflictPolicy.resolve(prior, conflictId, actor, reason)
                .copy(revision = prior.revision + 1)
            val sequence = dao.lastSequence() + 1
            val row = ReleaseReconciliationMapper.projection(next, priorRow.bucketKey, sequence)
            dao.insertEvent(ReconciliationEventEntity(
                digest("resolve:$sequence:$key:$conflictId"), sequence, 0,
                "RESOLVE_CONFLICT", 1, key, null, null, null, null, 1,
                prior.revision, next.revision,
                ReleaseReconciliationMapper.eventPayload(row), actor, reason,
            ))
            dao.upsertProjection(row)
            next
        }

    suspend fun history(key: String, limit: Int, offset: Int): List<ReconciliationEventEntity> {
        require(limit in 1..256 && offset >= 0)
        check(dao.baselineMarker() != null)
        return dao.eventsFor(key, limit, offset).onEach { event ->
            check(event.payloadVersion == 1)
            if (event.kind == "RESOLVE_CONFLICT") check(!event.resolutionActor.isNullOrBlank() &&
                !event.resolutionReason.isNullOrBlank())
            check(ReleaseReconciliationMapper.eventProjection(event.payload).projectionKey == key)
        }
    }

    suspend fun evidenceReceipts(key: String, limit: Int, offset: Int): List<CycleEvidenceReceiptEntity> {
        require(limit in 1..256 && offset >= 0)
        check(dao.baselineMarker() != null)
        return dao.receiptsForProjection(key, limit, offset).onEach { receipt ->
            val evidence = dao.evidenceById(receipt.canonicalEvidenceId)?.toDomainOrNull()
                ?: error("orphan cycle Evidence receipt")
            check((CanonicalReleaseIdentity.from(evidence)?.key ?: "partial-v1:${evidence.id}") == key)
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
        return CanonicalReleaseIdentity.bucketOf(e) ?: error("candidate lacks canonical bucket")
    }

    private suspend fun verifyStateReferences(row: CanonicalReleaseProjectionEntity): CanonicalReleaseState {
        val state = ReleaseReconciliationMapper.state(row)
        if (state.key.startsWith("partial-v1:")) {
            val evidenceId = state.key.removePrefix("partial-v1:")
            val partial = dao.evidenceById(evidenceId)?.toDomainOrNull()
                ?: error("partial projection references missing Evidence")
            check(partial.identityCompleteness() == ReleaseIdentityCompleteness.PARTIAL &&
                bucketOf(partial) == row.bucketKey)
            state.bindingKey?.let { bound ->
                val target = CanonicalReleaseIdentity.decode(bound) ?: error("invalid bound identity")
                check(com.axiel7.anihyou.release.core.state.ReleaseIdentityCompatibilityPolicy
                    .compatible(partial, target) && dao.projection(bound) != null) {
                    "partial binding references a foreign or missing candidate"
                }
            }
        }
        state.forecastEvidenceId?.let { id ->
            val forecast = dao.evidenceById(id)?.toDomainOrNull()
                ?: error("projection references missing forecast")
            check(forecast.sourceType == ReleaseSourceType.ANIWORLD_CALENDAR &&
                forecast.evidenceType == ReleaseEvidenceType.FORECAST &&
                (CanonicalReleaseIdentity.from(forecast)?.key == state.key ||
                    CanonicalReleaseIdentity.decode(state.key)?.let { key ->
                        forecast.identityCompleteness() == ReleaseIdentityCompleteness.PARTIAL &&
                            bucketOf(forecast) == key.bucketKey &&
                            dao.projection("partial-v1:$id")?.bindingKey == state.key
                    } == true)) { "forecast reference belongs to another release" }
        }
        state.scheduleEvidenceId?.let { id ->
            val correction = dao.evidenceById(id)?.toDomainOrNull()
                ?: error("projection references missing schedule correction")
            check(correction.sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT &&
                correction.evidenceType == ReleaseEvidenceType.CORRECTION &&
                CanonicalReleaseIdentity.from(correction)?.key == state.key)
        }
        return state
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
        *cycle.manifest.sortedBy { it.instanceId }.map { expected ->
            lengthKey(expected.instanceId, expected.sourceType.name, expected.targetKey,
                expected.track?.name ?: "", expected.negativeRequired.toString())
        }.toTypedArray(),
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

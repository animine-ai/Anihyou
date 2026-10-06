package com.axiel7.anihyou.release.data.db

import com.axiel7.anihyou.release.core.model.*
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Versioned, bounded state snapshots in the append-only event journal. */
internal object ReleaseReconciliationMapper {
    fun projection(state: CanonicalReleaseState, bucket: String, sequence: Long): CanonicalReleaseProjectionEntity {
        require(state.key.length <= 2048 && bucket.length <= 2048 && state.revision >= 0)
        val conflicts = JSONArray()
        state.conflicts.sortedBy { it.id }.forEach { conflict ->
            require(conflict.id.length <= 4096 && conflict.evidenceIds.size <= 256)
            conflicts.put(JSONObject().put("id", conflict.id).put("kind", conflict.kind.name)
                .put("open", conflict.open).put("evidenceIds", JSONArray(conflict.evidenceIds.sorted())))
        }
        val payload = conflicts.toString()
        require(payload.length <= 65536)
        return CanonicalReleaseProjectionEntity(
            state.key, bucket, state.underlyingPhase.name, state.phase.name, state.authority.name,
            state.scheduleCondition.name, state.scheduleEvidenceId,
            state.releaseAt?.toString(), state.forecastAt?.toString(),
            state.forecastEvidenceId, state.bindingKey, payload, state.revision, sequence,
            state.absenceCount, state.lastAbsenceAt?.toString(), state.expectationEvidenceId,
            JSONArray(state.navigationSeasons.sorted()).toString(),
            state.latestCompletedAt?.toString(),
        )
    }

    fun state(row: CanonicalReleaseProjectionEntity): CanonicalReleaseState {
        require(row.projectionKey.length <= 2048 && row.bucketKey.length <= 2048 &&
            row.conflictIdsPayload.length <= 65536 && row.revision >= 0 && row.absenceCount >= 0)
        val exact = CanonicalReleaseIdentity.decode(row.projectionKey)
        check(exact != null && exact.bucketKey == row.bucketKey ||
            row.projectionKey.startsWith("partial-v1:") && row.projectionKey.length > 11) {
            "invalid projection identity"
        }
        val array = JSONArray(row.conflictIdsPayload)
        require(row.navigationPayload.length <= 2048)
        val navigation = JSONArray(row.navigationPayload)
        check(navigation.length() <= 256)
        val seasons = (0 until navigation.length()).map(navigation::getInt)
        check(seasons.all { it >= 0 } && seasons.distinct().size == seasons.size)
        check(array.length() <= 256)
        val conflicts = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val refs = item.getJSONArray("evidenceIds")
            check(refs.length() <= 256)
            val ids = (0 until refs.length()).map(refs::getString)
            check(ids.distinct().size == ids.size && ids.all { it.isNotBlank() && it.length <= 256 })
            ReleaseConflict(item.getString("id"), ReleaseConflictKind.valueOf(item.getString("kind")),
                ids.toSet(), item.getBoolean("open"))
        }
        check(conflicts.map { it.id }.distinct().size == conflicts.size)
        val state = CanonicalReleaseState(
            key = row.projectionKey,
            underlyingPhase = ReleasePhase.valueOf(row.underlyingPhase),
            phase = ReleasePhase.valueOf(row.phase),
            authority = ReleaseAuthority.valueOf(row.authority),
            scheduleCondition = ScheduleCondition.valueOf(row.scheduleCondition),
            scheduleEvidenceId = row.scheduleEvidenceId,
            releaseAt = row.releaseAt?.let(Instant::parse),
            forecastAt = row.forecastAt?.let(Instant::parse),
            forecastEvidenceId = row.forecastEvidenceId,
            bindingKey = row.bindingKey,
            conflicts = conflicts,
            revision = row.revision,
            absenceCount = row.absenceCount,
            lastAbsenceAt = row.lastAbsenceAt?.let(Instant::parse),
            expectationEvidenceId = row.expectationEvidenceId,
            navigationSeasons = seasons.toSet(),
            latestCompletedAt = row.latestCompletedAt?.let(Instant::parse),
        )
        check(ReleaseConflictPolicyCheck.effective(state) == state.phase)
        check(state.authority != ReleaseAuthority.ANIWORLD ||
            (exact != null && state.underlyingPhase == ReleasePhase.RELEASED))
        return state
    }

    fun eventPayload(row: CanonicalReleaseProjectionEntity): String {
        val value = JSONObject().put("v", 1).put("key", row.projectionKey).put("bucket", row.bucketKey)
            .put("underlying", row.underlyingPhase).put("phase", row.phase)
            .put("authority", row.authority).put("schedule", row.scheduleCondition)
            .put("scheduleId", row.scheduleEvidenceId ?: JSONObject.NULL)
            .put("release", row.releaseAt ?: JSONObject.NULL)
            .put("forecast", row.forecastAt ?: JSONObject.NULL)
            .put("forecastId", row.forecastEvidenceId ?: JSONObject.NULL)
            .put("binding", row.bindingKey ?: JSONObject.NULL)
            .put("conflicts", row.conflictIdsPayload).put("revision", row.revision)
            .put("sequence", row.lastAppliedSequence).put("absence", row.absenceCount)
            .put("lastAbsence", row.lastAbsenceAt ?: JSONObject.NULL)
            .put("expectation", row.expectationEvidenceId ?: JSONObject.NULL)
            .put("navigation", row.navigationPayload)
            .put("latestCompleted", row.latestCompletedAt ?: JSONObject.NULL).toString()
        require(value.length <= 65536)
        return value
    }

    fun eventProjection(payload: String): CanonicalReleaseProjectionEntity {
        require(payload.length <= 65536)
        val json = JSONObject(payload)
        check(json.getInt("v") == 1 && json.length() == 20)
        fun optional(name: String): String? = if (json.isNull(name)) null else json.getString(name)
        val row = CanonicalReleaseProjectionEntity(
            json.getString("key"), json.getString("bucket"), json.getString("underlying"),
            json.getString("phase"), json.getString("authority"), json.getString("schedule"),
            optional("scheduleId"),
            optional("release"), optional("forecast"), optional("forecastId"), optional("binding"),
            json.getString("conflicts"), json.getLong("revision"), json.getLong("sequence"),
            json.getInt("absence"), optional("lastAbsence"), optional("expectation"),
            json.getString("navigation"),
            optional("latestCompleted"),
        )
        state(row)
        return row
    }
}

private object ReleaseConflictPolicyCheck {
    fun effective(state: CanonicalReleaseState): ReleasePhase = when {
        state.underlyingPhase == ReleasePhase.RELEASED -> ReleasePhase.RELEASED
        state.conflicts.any { it.open } -> ReleasePhase.CONFLICT
        else -> state.underlyingPhase
    }
}

package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.data.extension.ExtensionAcquisitionTarget
import com.axiel7.anihyou.release.core.extension.ExtensionExecutionReceipt
import com.axiel7.anihyou.release.core.model.CompletedObservationCycle
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.data.db.PollGenerationEntity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.RequestStateEntity
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface ExtensionShadowGenerationClaim {
    data class Acquired(val token: ExtensionShadowGenerationToken) : ExtensionShadowGenerationClaim
    object AlreadyCommitted : ExtensionShadowGenerationClaim
    object Busy : ExtensionShadowGenerationClaim
}

data class ExtensionShadowGenerationToken internal constructor(
    val workId: String,
    val cycleId: String,
    val executionGenerationId: String,
    val ownerToken: String,
    val processEpoch: String,
    val startedAt: Instant,
)

/** Durable one-owner generation fence for the generic extension Shadow route. */
class RoomExtensionShadowGenerationStore(
    private val database: ReleaseDatabase,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val processEpoch: String = PROCESS_EPOCH,
) {
    private val poll = database.aniworldPollDao()

    suspend fun claim(workId: String, now: Instant = clock.instant()): ExtensionShadowGenerationClaim =
        database.withTransaction {
            require(workId.matches(WORK_ID_PATTERN))
            require(processEpoch.matches(OWNER_PATTERN))
            val cycleId = cycleIdFor(workId)
            if (poll.committedCycleGeneration(SCOPE_ID, cycleId) != null ||
                reconciliation.hasCompletedCycle(cycleId)) {
                return@withTransaction ExtensionShadowGenerationClaim.AlreadyCommitted
            }

            val active = poll.activeGeneration(SCOPE_ID)
            if (active != null) {
                if (active.processEpoch == processEpoch) return@withTransaction ExtensionShadowGenerationClaim.Busy
                check(poll.finishGeneration(active.generationId, active.ownerToken, "ABORTED",
                    now.toString(), "ABORTED", "process-restart", null) == 1) {
                    "stale extension generation could not be fenced"
                }
                poll.clearActiveGeneration(active.generationId)
            }

            val executionGenerationId = "aw-ext-shadow-run-v1:${UUID.randomUUID()}"
            val ownerToken = UUID.randomUUID().toString()
            val payload = leasePayload(workId, cycleId)
            poll.insertGeneration(PollGenerationEntity(
                generationId = executionGenerationId,
                scopeId = SCOPE_ID,
                ownerToken = ownerToken,
                processEpoch = processEpoch,
                policyVersion = 1,
                startedAt = now.toString(),
                deadlineAt = now.plus(GENERATION_TIMEOUT).toString(),
                completedAt = null,
                state = "RUNNING",
                manifestVersion = 1,
                manifestPayload = payload,
                manifestDigest = sha256(payload),
                directUrlCount = 0,
                directReserved = 0,
                listReserved = 0,
                outcome = null,
                reason = null,
                cycleId = null,
            ))

            val current = poll.requestState(SCOPE_ID) ?: RequestStateEntity(
                scopeKey = SCOPE_ID,
                firstEligibleAt = null,
                lastAttemptAt = null,
                lastSuccessAt = null,
                failureCount = 0,
                nextEligibleAt = null,
                lastAppliedGeneration = null,
                lastAppliedOrdinal = null,
                activeGenerationId = null,
                policyVersion = 1,
            )
            poll.putRequestState(current.copy(
                lastAttemptAt = now.toString(),
                activeGenerationId = executionGenerationId,
                policyVersion = 1,
            ))

            ExtensionShadowGenerationClaim.Acquired(ExtensionShadowGenerationToken(
                workId = workId,
                cycleId = cycleId,
                executionGenerationId = executionGenerationId,
                ownerToken = ownerToken,
                processEpoch = processEpoch,
                startedAt = now,
            ))
        }

    suspend fun commit(
        token: ExtensionShadowGenerationToken,
        cycle: CompletedObservationCycle,
        receipt: ExtensionExecutionReceipt,
        sourceHealth: List<SourceHealth>,
        targets: List<ExtensionAcquisitionTarget> = emptyList(),
    ): Boolean = database.withTransaction {
        val row = poll.generation(token.executionGenerationId) ?: return@withTransaction false
        if (row.scopeId != SCOPE_ID || row.ownerToken != token.ownerToken ||
            row.processEpoch != token.processEpoch || row.state != "RUNNING" ||
            poll.requestState(SCOPE_ID)?.activeGenerationId != token.executionGenerationId ||
            cycle.id != token.cycleId || cycle.scopeId != SCOPE_ID ||
            receipt.generationId != token.executionGenerationId) return@withTransaction false

        val receiptPayload = receiptPayload(token.workId, receipt)
        if (poll.recordExecutionReceipt(token.executionGenerationId, token.ownerToken, token.processEpoch,
                receiptPayload, sha256(receiptPayload)) != 1) return@withTransaction false

        require(targets.size <= 8)
        reconciliation.persistCompletedCycle(cycle)
        // The caller supplies host-selected protocol coordinates. Persist logical attempt
        // history in the same fenced transaction, even when no requested track was found.
        // The transport ledger independently retains all physical URL cooldowns.
        val selectedKeys = targets.mapNotNull { selected ->
            val target = selected.target
            if (target.installment.kind != ObservationInstallmentKind.EPISODE) return@mapNotNull null
            val number = target.installment.number ?: return@mapNotNull null
            val episode = number.toIntOrNull()?.takeIf { it.toString() == number } ?: return@mapNotNull null
            DirectTargetSelectionPolicy.mappedCoordinateKey(
                target.providerSeriesKey ?: return@mapNotNull null,
                target.navigationSeason ?: return@mapNotNull null, episode)
        }.distinct()
        selectedKeys.forEach { key ->
            val stateKey = "url:$key"
            val current = poll.requestState(stateKey) ?: RequestStateEntity(
                scopeKey = stateKey, firstEligibleAt = cycle.startedAt.toString(),
                lastAttemptAt = null, lastSuccessAt = null, failureCount = 0,
                nextEligibleAt = null, lastAppliedGeneration = null, lastAppliedOrdinal = null,
                activeGenerationId = null, policyVersion = 1,
            )
            poll.putRequestState(current.copy(
                lastAttemptAt = cycle.completedAt.toString(),
                lastAppliedGeneration = cycle.id, policyVersion = 1,
            ))
        }
        sourceHealth.forEach { incoming ->
            val current = poll.health(incoming.sourceType.name)?.toDomainOrNull()
            poll.putHealth(SourceHealthPersistencePolicy.merge(current, incoming).toEntity())
        }
        check(poll.finishGeneration(token.executionGenerationId, token.ownerToken, "COMMITTED",
            cycle.completedAt.toString(), "COMMITTED", null, cycle.id) == 1)
        poll.clearActiveGeneration(token.executionGenerationId)
        poll.requestState(SCOPE_ID)?.let { previous ->
            poll.putRequestState(previous.copy(
                lastSuccessAt = cycle.completedAt.toString(),
                failureCount = 0,
                lastAppliedGeneration = token.cycleId,
                lastAppliedOrdinal = null,
                activeGenerationId = null,
            ))
        }
        true
    }

    suspend fun abort(
        token: ExtensionShadowGenerationToken,
        reason: String,
        now: Instant = clock.instant(),
        sourceHealth: List<SourceHealth> = emptyList(),
    ) {
        database.withTransaction {
            val row = poll.generation(token.executionGenerationId) ?: return@withTransaction
            if (row.scopeId != SCOPE_ID || row.ownerToken != token.ownerToken ||
                row.processEpoch != token.processEpoch || row.state != "RUNNING" ||
                poll.requestState(SCOPE_ID)?.activeGenerationId != token.executionGenerationId) return@withTransaction

            val safeReason = reason.filter { it.code in 0x20..0x7e }.take(120).ifBlank { "extension-failure" }
            check(poll.finishGeneration(token.executionGenerationId, token.ownerToken, "ABORTED",
                now.toString(), "ABORTED", safeReason, null) == 1)
            poll.clearActiveGeneration(token.executionGenerationId)
            poll.requestState(SCOPE_ID)?.let { previous ->
                poll.putRequestState(previous.copy(
                    failureCount = (previous.failureCount + 1).coerceAtMost(5),
                    activeGenerationId = null,
                ))
            }
            sourceHealth.forEach { incoming ->
                val current = poll.health(incoming.sourceType.name)?.toDomainOrNull()
                poll.putHealth(SourceHealthPersistencePolicy.merge(current, incoming).toEntity())
            }
        }
    }

    private fun leasePayload(workId: String, cycleId: String): String =
        """{"schema":"extension-shadow-lease-v1","workId":"$workId","cycleId":"$cycleId"}"""

    private fun receiptPayload(workId: String, receipt: ExtensionExecutionReceipt): String =
        """{"schema":"extension-shadow-receipt-v1","workId":"$workId","receiptId":"${json(receipt.receiptId)}","packageDigest":"${receipt.packageDigest}","manifestDigest":"${receipt.manifestDigest}","moduleDigest":"${receipt.moduleDigest}","releaseSequence":${receipt.releaseSequence},"trustRootVersion":${receipt.trustRootVersion},"completedAt":"${json(receipt.completedAt)}"}"""

    private fun json(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun cycleIdFor(workId: String): String = "aw-ext-shadow-v1:${sha256(workId)}"

    companion object {
        const val SCOPE_ID = "aniworld:extension:shadow:v1"
        private val WORK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,111}")
        private val OWNER_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val GENERATION_TIMEOUT: Duration = Duration.ofMinutes(30)
        private val PROCESS_EPOCH = UUID.randomUUID().toString()
    }
}

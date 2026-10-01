package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.*
import com.axiel7.anihyou.release.core.state.ShadowFact
import com.axiel7.anihyou.release.core.state.ShadowComparison
import com.axiel7.anihyou.release.core.state.ShadowComparisonPolicy
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.data.aniworld.AniWorldCanonicalRouteParser
import com.axiel7.anihyou.release.data.aniworld.AniWorldCanonicalRouteResult
import com.axiel7.anihyou.release.data.aniworld.AniWorldDirectTargetResolver
import com.axiel7.anihyou.release.data.aniworld.AniWorldParser
import com.axiel7.anihyou.release.data.db.*
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

private val POLL_TIMESTAMP_FORMAT = DateTimeFormatterBuilder().appendInstant(9).toFormatter()
private fun Instant.toPollTimestamp(): String = POLL_TIMESTAMP_FORMAT.format(this)

/** Durable generation fencing, request budgets and T4 ownership for the V3 shadow path. */
class RoomAniWorldPollStore(
    private val database: ReleaseDatabase,
    private val reconciliation: RoomReleaseReconciliationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : AniWorldShadowPollStore {
    private val poll = database.aniworldPollDao()
    private val reconciliationDao = database.reconciliationDao()
    private val releaseDao = database.releaseDao()

    override suspend fun eligibleDirectTargets(now: Instant): List<DirectTargetCandidate> =
        database.withTransaction { readCandidates(now, rememberFirstEligibility = true) }

    override suspend fun eligibleMappedDirectTargets(now: Instant): List<DirectTargetCandidate> =
        database.withTransaction { readCandidates(now, rememberFirstEligibility = true, mappedOnly = true) }

    override suspend fun shadowComparison(now: Instant): ShadowComparison =
        database.withTransaction { compareInTransaction(now) }

    override suspend fun beginGeneration(manifest: ShadowGenerationManifest, candidateSnapshotDigest: String): Boolean =
        database.withTransaction {
            require(candidateSnapshotDigest.matches(SHA256))
            require(manifest.digest == AniWorldShadowManifestCodec.digest(manifest.sources))
            val now = clock.instant()
            val scopeKey = scopeKey(SCOPE_ID)
            val previousScope = poll.requestState(scopeKey)
            if (previousScope?.nextEligibleAt?.let(::instantOrNull)?.isAfter(now) == true) return@withTransaction false

            val active = poll.activeGeneration(SCOPE_ID)
            if (active != null) {
                if (active.processEpoch == manifest.token.processEpoch) return@withTransaction false
                poll.finishGeneration(active.generationId, active.ownerToken, "ABORTED", now.toPollTimestamp(),
                    "ABORTED", "process-restart", null)
                poll.clearActiveGeneration(active.generationId)
                val completed = poll.completedAttemptCount(active.generationId)
                val reserved = poll.attemptCount(active.generationId)
                poll.insertMetric(ShadowMetricEntity(active.generationId, now.toPollTimestamp(), 1,
                    metricPayload(ShadowRunMetrics(0, 0, 0, 0, 0, 0, 0, 0, reserved.coerceAtMost(MAX_WIRE_CALLS),
                        poll.redirectCount(active.generationId), 0, 0, 0, 0,
                        completedWireCalls = completed.coerceAtMost(MAX_WIRE_CALLS),
                        uncompletedReservedCalls = (reserved - completed).coerceAtLeast(0).coerceAtMost(MAX_WIRE_CALLS)))))
            }

            val currentCandidates = readCandidates(now, rememberFirstEligibility = false)
            if (DirectTargetSelectionPolicy.snapshotDigest(currentCandidates) != candidateSnapshotDigest ||
                !manifestMatchesSelection(manifest, currentCandidates, now)) return@withTransaction false
            val hostState = poll.requestState(hostKey("aniworld"))
            if (hostState?.nextEligibleAt?.let(::instantOrNull)?.isAfter(now) == true) return@withTransaction false
            val anySourceEligible = manifest.sources.any { source ->
                val key = urlKey(normalizeAniWorldUrl(source.requestUrl) ?: return@any false)
                poll.requestState(key)?.nextEligibleAt?.let(::instantOrNull)?.isAfter(now) != true
            }
            if (!anySourceEligible) return@withTransaction false
            val payload = AniWorldShadowManifestCodec.encode(manifest, candidateSnapshotDigest)
            require(payload.length <= MAX_MANIFEST_CHARS)
            poll.insertGeneration(PollGenerationEntity(
                generationId = manifest.token.generationId, scopeId = SCOPE_ID,
                ownerToken = manifest.token.ownerToken, processEpoch = manifest.token.processEpoch,
                policyVersion = 1, startedAt = manifest.startedAt.toPollTimestamp(), deadlineAt = manifest.deadlineAt.toPollTimestamp(),
                completedAt = null, state = "RUNNING", manifestVersion = 1, manifestPayload = payload,
                manifestDigest = manifest.digest,
                directUrlCount = manifest.sources.filter { it.sourceType == DIRECT_TYPE }.map { it.requestUrl }.distinct().size,
                directReserved = 0, listReserved = 0, outcome = null, reason = null, cycleId = null,
            ))
            poll.putRequestState((previousScope ?: emptyState(scopeKey)).copy(
                lastAttemptAt = now.toPollTimestamp(),
                nextEligibleAt = maxInstant(previousScope?.nextEligibleAt?.let(::instantOrNull), now.plus(MINIMUM_BACKOFF)).toPollTimestamp(),
                activeGenerationId = manifest.token.generationId, policyVersion = 1,
            ))
            poll.pruneMetrics(now.minus(Duration.ofDays(30)).toPollTimestamp())
            poll.pruneAttempts(now.minus(Duration.ofDays(30)).toPollTimestamp())
            poll.pruneGenerations(now.minus(Duration.ofDays(30)).toPollTimestamp())
            poll.pruneExpiredUrlStates(now.minus(Duration.ofDays(30)).toPollTimestamp(), now.toPollTimestamp())
            true
        }

    override suspend fun currentGeneration(token: ShadowGenerationToken): ShadowGenerationSnapshot? =
        database.withTransaction {
            val row = fencedRunning(token) ?: return@withTransaction null
            val manifest = validatedManifest(row) ?: return@withTransaction null
            if (row.directReserved != poll.attemptCount(row.generationId, DIRECT_ROLE) ||
                row.listReserved != poll.listAttemptCount(row.generationId) ||
                row.directReserved + row.listReserved > MAX_WIRE_CALLS ||
                row.directReserved > MAX_DIRECT_CALLS || row.listReserved > MAX_LIST_CALLS) return@withTransaction null
            ShadowGenerationSnapshot(ShadowGenerationState.RUNNING, manifest,
                row.directReserved, row.listReserved, poll.redirectCount(row.generationId))
        }

    override suspend fun reserveRequest(
        token: ShadowGenerationToken, role: String, rootUrl: String, requestUrl: String, now: Instant,
    ): ShadowRequestReservation? = database.withTransaction {
        if (role !in setOf(DIRECT_ROLE, "CALENDAR", "RECENT", "POSTPONEMENT")) return@withTransaction null
        val root = normalizeAniWorldUrl(rootUrl) ?: return@withTransaction null
        val url = normalizeAniWorldUrl(requestUrl) ?: return@withTransaction null
        val generation = fencedRunning(token) ?: return@withTransaction null
        val deadline = instantOrNull(generation.deadlineAt) ?: return@withTransaction null
        if (!now.isBefore(deadline)) return@withTransaction null
        val manifest = validatedManifest(generation) ?: return@withTransaction null
        val planned = manifest.sources.any { source ->
            source.requestUrl == root && when (role) {
                DIRECT_ROLE -> source.sourceType == DIRECT_TYPE
                "CALENDAR" -> source.instanceId == "aw:list:calendar:v1"
                "RECENT" -> source.instanceId == "aw:list:recent:v1"
                else -> source.instanceId == "aw:list:postponement:v1"
            }
        }
        if (!planned) return@withTransaction null
        val total = poll.attemptCount(generation.generationId)
        val roleCount = poll.attemptCount(generation.generationId, role)
        if (total >= MAX_WIRE_CALLS ||
            (role == DIRECT_ROLE && (roleCount >= MAX_DIRECT_CALLS ||
                poll.directAttemptCount(generation.generationId, root) >= MAX_DIRECT_CALLS)) ||
            (role != DIRECT_ROLE && poll.listAttemptCount(generation.generationId) >= MAX_LIST_CALLS)) return@withTransaction null

        val keys = listOf(urlKey(root), urlKey(url), hostKey("aniworld")).distinct()
        if (keys.any { key ->
                val state = poll.requestState(key)
                state?.nextEligibleAt?.let(::instantOrNull)?.isAfter(now) == true &&
                    state.activeGenerationId != token.generationId
            }) return@withTransaction null

        val ordinal = total
        val reservation = ShadowRequestReservation(token, ordinal, role, root, url, now)
        poll.insertAttempt(HttpAttemptEntity(generation.generationId, ordinal, root, url, role,
            now.toPollTimestamp(), null, "RESERVED", null, null))
        val direct = poll.attemptCount(generation.generationId, DIRECT_ROLE)
        check(poll.updateReservationCounts(generation.generationId, direct, total + 1 - direct) == 1)
        keys.filterNot { it.startsWith("host:") }.forEach { key ->
            val old = poll.requestState(key) ?: emptyState(key)
            poll.putRequestState(old.copy(firstEligibleAt = old.firstEligibleAt ?: now.toPollTimestamp(),
                lastAttemptAt = now.toPollTimestamp(),
                nextEligibleAt = maxInstant(old.nextEligibleAt?.let(::instantOrNull), now.plus(MINIMUM_BACKOFF)).toPollTimestamp(),
                activeGenerationId = token.generationId, lastAppliedGeneration = token.generationId,
                lastAppliedOrdinal = ordinal, policyVersion = 1))
        }
        reservation
    }

    override suspend fun completeRequest(reservation: ShadowRequestReservation, outcome: ShadowRequestOutcome) {
        database.withTransaction {
            val attempt = poll.attempt(reservation.token.generationId, reservation.ordinal) ?: return@withTransaction
            if (attempt.completedAt != null) return@withTransaction
            val generation = fencedRunning(reservation.token)
            val live = generation != null
            poll.completeAttempt(reservation.token.generationId, reservation.ordinal, outcome.completedAt.toPollTimestamp(),
                if (live) outcome.status.take(40) else "STALE_RESULT",
                outcome.retryAfterSeconds?.coerceIn(0, 21_600), outcome.elapsedMillis.coerceAtLeast(0))
            if (!live) return@withTransaction

            val now = outcome.completedAt
            val is429 = outcome.status == "HTTP_429"
            val success = outcome.status == "HTTP_2XX" || outcome.status == "REDIRECT"
            val priorFailureCount = poll.requestState(
                if (is429) hostKey("aniworld") else urlKey(reservation.rootUrl),
            )?.failureCount ?: 0
            val next = when {
                success -> now.plus(SUCCESS_COOLDOWN)
                is429 -> maxInstant(now.plus(backoff(priorFailureCount + 1)),
                    now.plusSeconds(outcome.retryAfterSeconds ?: 0))
                outcome.status == "HTTP_304" -> now.plus(MINIMUM_BACKOFF)
                else -> now.plus(backoff(priorFailureCount + 1))
            }
            listOf(urlKey(reservation.rootUrl), urlKey(reservation.requestUrl)).distinct().forEach { key ->
                val old = poll.requestState(key) ?: emptyState(key)
                if (old.lastAppliedGeneration == reservation.token.generationId &&
                    old.lastAppliedOrdinal?.let { it > reservation.ordinal } == true) return@forEach
                poll.putRequestState(old.copy(
                    lastSuccessAt = if (success) now.toPollTimestamp() else old.lastSuccessAt,
                    failureCount = if (success) 0 else (old.failureCount + 1).coerceAtMost(5),
                    nextEligibleAt = maxInstant(old.nextEligibleAt?.let(::instantOrNull), next).toPollTimestamp(),
                    lastAppliedGeneration = reservation.token.generationId,
                    lastAppliedOrdinal = reservation.ordinal,
                    activeGenerationId = if (is429) null else reservation.token.generationId,
                    policyVersion = 1,
                ))
            }
            if (is429) {
                val key = hostKey("aniworld")
                val old = poll.requestState(key) ?: emptyState(key)
                poll.putRequestState(old.copy(failureCount = (old.failureCount + 1).coerceAtMost(5),
                    nextEligibleAt = maxInstant(old.nextEligibleAt?.let(::instantOrNull), next).toPollTimestamp(),
                    lastAppliedGeneration = reservation.token.generationId,
                    lastAppliedOrdinal = reservation.ordinal, activeGenerationId = null, policyVersion = 1))
            }
        }
    }

    override suspend fun commitGeneration(
        token: ShadowGenerationToken, cycle: CompletedObservationCycle, metrics: ShadowRunMetrics,
    ): Boolean = database.withTransaction {
        val row = poll.generation(token.generationId) ?: return@withTransaction false
        if (row.state == "COMMITTED") {
            if (row.cycleId != cycle.id) return@withTransaction false
            reconciliation.persistCompletedCycle(cycle) // verifies duplicate request digest without a second write
            return@withTransaction true
        }
        if (fencedRunning(token) == null) return@withTransaction false
        val manifest = validatedManifest(row) ?: error("invalid stored shadow manifest")
        require(cycle.id == token.generationId && cycle.scopeId == SCOPE_ID)
        require(cycle.startedAt == manifest.startedAt && cycle.completedAt <= manifest.deadlineAt)
        val expected = manifest.sources.map { source ->
            ExpectedSourceInstance(source.instanceId, ReleaseSourceType.valueOf(source.sourceType),
                source.targetKey, source.track?.let(LanguageTrack::valueOf), source.negativeRequired)
        }
        require(cycle.manifest == expected)
        require(cycle.sources.map { it.instanceId }.toSet() == expected.map { it.instanceId }.toSet())
        if (!clock.instant().isBefore(manifest.deadlineAt)) error("generation deadline elapsed before T4")

        reconciliation.persistCompletedCycle(cycle)
        mergeSourceHealth(cycle)
        val comparison = compareInTransaction(clock.instant())
        val finalMetrics = metrics.copy(comparableKeys = comparison.comparable,
            disagreements = comparison.disagreements, r2OnlyKeys = comparison.r2Only,
            v3OnlyKeys = comparison.v3Only, staleKeys = comparison.stale,
            uncomparableKeys = comparison.uncomparable,
            reservedWireCalls = poll.attemptCount(token.generationId).coerceAtMost(MAX_WIRE_CALLS),
            redirectCalls = poll.redirectCount(token.generationId).coerceAtMost(MAX_WIRE_CALLS),
            completedWireCalls = poll.completedAttemptCount(token.generationId).coerceAtMost(MAX_WIRE_CALLS),
            uncompletedReservedCalls = poll.uncompletedAttemptCount(token.generationId).coerceAtMost(MAX_WIRE_CALLS))
        poll.insertMetric(ShadowMetricEntity(token.generationId, clock.instant().toPollTimestamp(), 1,
            metricPayload(finalMetrics)))
        if (!clock.instant().isBefore(manifest.deadlineAt)) error("generation deadline elapsed inside T4")
        check(poll.finishGeneration(token.generationId, token.ownerToken, "COMMITTED",
            clock.instant().toPollTimestamp(), "COMMITTED", null, cycle.id) == 1)
        poll.clearActiveGeneration(token.generationId)
        true
    }

    override suspend fun abortGeneration(
        token: ShadowGenerationToken, reason: String, now: Instant, metrics: ShadowRunMetrics,
    ) = database.withTransaction {
        if (fencedRunning(token) == null) return@withTransaction
        val reserved = poll.attemptCount(token.generationId)
        val completed = poll.completedAttemptCount(token.generationId)
        val finalMetrics = metrics.copy(
            reservedWireCalls = reserved.coerceAtMost(MAX_WIRE_CALLS),
            redirectCalls = poll.redirectCount(token.generationId).coerceAtMost(MAX_WIRE_CALLS),
            completedWireCalls = completed.coerceAtMost(MAX_WIRE_CALLS),
            uncompletedReservedCalls = poll.uncompletedAttemptCount(token.generationId).coerceAtMost(MAX_WIRE_CALLS),
        )
        check(poll.finishGeneration(token.generationId, token.ownerToken, "ABORTED", now.toPollTimestamp(),
            "ABORTED", reason.take(160), null) == 1)
        poll.clearActiveGeneration(token.generationId)
        poll.insertMetric(ShadowMetricEntity(token.generationId, now.toPollTimestamp(), 1, metricPayload(finalMetrics)))
        val key = scopeKey(SCOPE_ID)
        val old = poll.requestState(key) ?: emptyState(key)
        poll.putRequestState(old.copy(activeGenerationId = null,
            nextEligibleAt = maxInstant(old.nextEligibleAt?.let(::instantOrNull), now.plus(MINIMUM_BACKOFF)).toPollTimestamp()))
    }

    private suspend fun fencedRunning(token: ShadowGenerationToken): PollGenerationEntity? {
        val row = poll.generation(token.generationId) ?: return null
        if (row.state != "RUNNING" || row.ownerToken != token.ownerToken || row.processEpoch != token.processEpoch)
            return null
        return row.takeIf { poll.requestState(scopeKey(SCOPE_ID))?.activeGenerationId == token.generationId }
    }

    private fun validatedManifest(row: PollGenerationEntity): ShadowGenerationManifest? {
        val decoded = AniWorldShadowManifestCodec.decode(row.manifestPayload) ?: return null
        val manifest = decoded.first
        if (manifest.token.generationId != row.generationId || manifest.token.ownerToken != row.ownerToken ||
            manifest.token.processEpoch != row.processEpoch || manifest.policyVersion != row.policyVersion ||
            row.manifestVersion != 1 || manifest.digest != row.manifestDigest || manifest.sources.filter { it.sourceType == DIRECT_TYPE }
                .map { it.requestUrl }.distinct().size != row.directUrlCount) return null
        return manifest
    }

    private suspend fun readCandidates(now: Instant, rememberFirstEligibility: Boolean, mappedOnly: Boolean = false): List<DirectTargetCandidate> {
        val candidates = mutableListOf<DirectTargetCandidate>()
        var after: String? = null
        while (true) {
            val page = poll.projectionPageAfter(PAGE_SIZE, after)
            if (page.isEmpty()) break
            for (projection in page) {
                after = projection.projectionKey
                if (CanonicalReleaseIdentity.decode(projection.projectionKey) == null) continue
                val receipts = loadReceipts(projection.projectionKey) ?: continue
                val evidence = receipts.mapNotNull { receipt ->
                    reconciliationDao.evidenceById(receipt.canonicalEvidenceId)?.toDomainOrNull()
                }
                val initial = if (mappedOnly) AniWorldDirectTargetResolver.resolveMapped(projection, evidence, null, now)
                    else AniWorldDirectTargetResolver.resolve(projection, evidence, null, now)
                if (initial == null) continue
                val stateKey = urlKey(initial.canonicalUrl)
                var requestState = poll.requestState(stateKey)
                if (rememberFirstEligibility && requestState?.firstEligibleAt == null) {
                    val row = requestState ?: emptyState(stateKey)
                    poll.putRequestState(row.copy(firstEligibleAt = now.toPollTimestamp(), policyVersion = 1))
                    requestState = row.copy(firstEligibleAt = now.toPollTimestamp(), policyVersion = 1)
                }
                val candidate = if (mappedOnly) AniWorldDirectTargetResolver.resolveMapped(projection, evidence, requestState, now)
                    else AniWorldDirectTargetResolver.resolve(projection, evidence, requestState, now)
                if (candidate == null) continue
                candidates += candidate
            }
            if (page.size < PAGE_SIZE) break
        }
        require(candidates.flatMap { it.exactTargetKeys }.distinct().size ==
            candidates.sumOf { it.exactTargetKeys.size }) { "duplicate exact Direct candidate" }
        return candidates
    }

    private suspend fun loadReceipts(key: String): List<CycleEvidenceReceiptEntity>? {
        val result = mutableListOf<CycleEvidenceReceiptEntity>()
        var offset = 0
        while (true) {
            val page = reconciliationDao.receiptsForProjection(key, RECEIPT_PAGE_SIZE, offset)
            result += page
            if (result.size > MAX_PROVENANCE_ROWS) return null
            if (page.size < RECEIPT_PAGE_SIZE) return result
            offset += page.size
        }
    }

    private fun manifestMatchesSelection(
        manifest: ShadowGenerationManifest, allCandidates: List<DirectTargetCandidate>, now: Instant,
    ): Boolean {
        val selected = DirectTargetSelectionPolicy.select(allCandidates, now)
        val expected = selected.flatMap { candidate ->
            candidate.exactTargetKeys.map { it to candidate.canonicalUrl }
        }.toSet()
        val directSources = manifest.sources.filter { it.sourceType == DIRECT_TYPE }
        val actual = directSources.map { it.targetKey to it.requestUrl }.toSet()
        if (expected != actual || directSources.size > 8 ||
            directSources.groupBy { it.requestUrl }.any { it.value.size > 2 }) return false
        if (directSources.any { source ->
                val identity = CanonicalReleaseIdentity.decode(source.targetKey)
                identity == null || source.track != identity.track.name || source.negativeRequired ||
                    source.requiredForRun || source.instanceId != directTargetId(source.requestUrl, source.targetKey) ||
                    source.physicalRequestId != physicalRequestId(source.requestUrl)
            }) return false
        val fixed = mapOf(
            "aw:list:calendar:v1" to Triple("ANIWORLD_CALENDAR", "aniworld:list:calendar",
                "https://aniworld.to/animekalender"),
            "aw:list:recent:v1" to Triple("ANIWORLD_RECENT", "aniworld:list:recent",
                "https://aniworld.to/neue-episoden"),
            "aw:list:postponement:v1" to Triple("ANIWORLD_POSTPONEMENT", "aniworld:list:postponement",
                "https://aniworld.to/support/frage/anime-verschiebungen"),
        )
        if (fixed.any { (id, values) ->
                val spec = manifest.sources.singleOrNull { it.instanceId == id }
                spec == null || spec.sourceType != values.first || spec.targetKey != values.second ||
                    spec.requestUrl != values.third || spec.track != null || spec.negativeRequired ||
                    spec.physicalRequestId != null ||
                    spec.requiredForRun != (id != "aw:list:postponement:v1")
            }) return false
        return manifest.sources.size == 3 + directSources.size
    }

    private suspend fun mergeSourceHealth(cycle: CompletedObservationCycle) {
        cycle.sources.forEach { source ->
            if (source.result == CycleResult.INCOMPLETE) return@forEach
            val current = poll.health(source.sourceType.name)?.toDomainOrNull()
            val successful = source.result == CycleResult.SUCCESS || source.result == CycleResult.PARTIAL_SUCCESS
            val sample = source.evidence.firstOrNull()
            val incoming = SourceHealth(
                sourceType = source.sourceType, status = source.health,
                lastAttemptAt = source.observedAt, lastSuccessAt = source.observedAt.takeIf { successful },
                consecutiveFailures = if (successful) 0 else ((current?.consecutiveFailures ?: 0) + 1).coerceAtMost(5),
                parserVersion = sample?.parserVersion, sourceHash = sample?.sourceHash,
            )
            poll.putHealth(SourceHealthPersistencePolicy.merge(current, incoming).toEntity())
        }
    }

    private suspend fun compareInTransaction(now: Instant): ShadowComparison {
        val r2 = mutableListOf<ShadowFact>()
        releaseDao.getProviderSnapshots(AniWorldParser.PROVIDER_ID).forEach { raw ->
            val snapshot = raw.toDomainOrNull()
            if (snapshot == null) {
                r2 += ShadowFact("", false, null)
                return@forEach
            }
            val route = (AniWorldCanonicalRouteParser.parseSourceKey(snapshot.stream.stableSeriesKey.value)
                as? AniWorldCanonicalRouteResult.Success)?.route
            val site = route?.let { runCatching { AniWorldSiteIdentifier(it.slug) }.getOrNull() }
            if (site == null) {
                r2 += ShadowFact("", false, snapshot.freshness.lastSuccessAt)
                return@forEach
            }
            val facts = linkedMapOf<String, Boolean>()
            snapshot.forecasts.forEach { forecast ->
                canonicalR2Key(site, snapshot.stream.sourceSeason, snapshot.stream.languageTrack,
                    forecast.identity.installment)?.let { facts.putIfAbsent(it, false) }
            }
            snapshot.confirmations.forEach { confirmation ->
                canonicalR2Key(site, snapshot.stream.sourceSeason, snapshot.stream.languageTrack,
                    confirmation.identity.installment)?.let { facts[it] = true }
            }
            if (facts.isEmpty()) r2 += ShadowFact("", false, snapshot.freshness.lastSuccessAt)
            facts.forEach { (key, released) -> r2 += ShadowFact(key, released, snapshot.freshness.lastSuccessAt) }
        }

        val v3 = mutableListOf<ShadowFact>()
        var after: String? = null
        while (true) {
            val page = poll.projectionPageAfter(PAGE_SIZE, after)
            if (page.isEmpty()) break
            page.forEach { row ->
                after = row.projectionKey
                val identity = CanonicalReleaseIdentity.decode(row.projectionKey)
                val state = runCatching { ReleaseReconciliationMapper.state(row) }.getOrNull()
                if (identity == null || state == null) v3 += ShadowFact("", false, null)
                else v3 += ShadowFact(identity.key, state.underlyingPhase == ReleasePhase.RELEASED,
                    state.latestCompletedAt ?: state.forecastAt)
            }
            if (page.size < PAGE_SIZE) break
        }
        return ShadowComparisonPolicy.compare(r2, v3, now)
    }

    private fun canonicalR2Key(
        site: AniWorldSiteIdentifier, season: Int?, track: LanguageTrack, installment: Installment,
    ): String? {
        val evidence = runCatching {
            ReleaseEvidence("r2-shadow-fact", ReleaseSourceType.ANIWORLD_RECENT,
                site.canonicalUrl, "r2-shadow", "r2-shadow", Instant.EPOCH, null, true, site,
                season, null, installment, track, ReleaseEvidenceType.CONFIRMATION,
                confidence = ConfidenceVector.unknown())
        }.getOrNull() ?: return null
        return CanonicalReleaseIdentity.from(evidence)?.key
    }

    private fun metricPayload(m: ShadowRunMetrics): String = buildString {
        append("{\"v\":1")
        append(",\"planned\":").append(m.planned)
        append(",\"attempted\":").append(m.attempted)
        append(",\"succeeded\":").append(m.succeeded)
        append(",\"partial\":").append(m.partial)
        append(",\"failed\":").append(m.failed)
        append(",\"skipped\":").append(m.skipped)
        append(",\"directEligible\":").append(m.directEligibleUrls)
        append(",\"directSelected\":").append(m.directSelectedUrls)
        append(",\"reserved\":").append(m.reservedWireCalls)
        append(",\"redirects\":").append(m.redirectCalls)
        append(",\"postponementUnbound\":").append(m.postponementUnboundRows)
        append(",\"postponementAmbiguous\":").append(m.postponementAmbiguousRows)
        append(",\"postponementRejected\":").append(m.postponementRejectedRows)
        append(",\"comparable\":").append(m.comparableKeys)
        append(",\"r2Only\":").append(m.r2OnlyKeys)
        append(",\"v3Only\":").append(m.v3OnlyKeys)
        append(",\"disagreements\":").append(m.disagreements)
        append(",\"stale\":").append(m.staleKeys)
        append(",\"uncomparable\":").append(m.uncomparableKeys)
        append(",\"elapsedMillis\":").append(m.elapsedMillis)
        append(",\"completedWireCalls\":").append(m.completedWireCalls)
        append(",\"uncompletedReservedCalls\":").append(m.uncompletedReservedCalls)
        append(",\"same\":").append((m.comparableKeys - m.disagreements).coerceAtLeast(0))
        m.postponementSnapshotHash?.let { append(",\"postponementSnapshotHash\":\"").append(it).append('"') }
        m.postponementParserVersion?.let {
            append(",\"postponementParserVersion\":\"").append(jsonString(it)).append('"')
        }
        append(",\"sourceMetrics\":[")
        append(m.sourceMetrics.joinToString(",") { source ->
            "{\"instanceId\":\"${jsonString(source.instanceId)}\",\"type\":\"${source.sourceType.name}\"," +
                "\"outcome\":\"${source.outcome.name}\",\"elapsedMillis\":${source.elapsedMillis}," +
                "\"failureKind\":${source.failureKind?.let { "\"${it.name}\"" } ?: "null"}}"
        })
        append(']')
        append(",\"postponementReasons\":{")
        append(m.postponementReasonCounts.toSortedMap().entries.joinToString(",") { (key, value) ->
            val safeKey = key.filter { it.isLetterOrDigit() || it == '_' }.take(64)
            "\"$safeKey\":$value"
        })
        append("}}")
        check(length <= MAX_METRIC_CHARS)
    }

    private fun jsonString(value: String): String = buildString {
        value.take(64).forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code >= 0x20) append(char)
            }
        }
    }

    private fun emptyState(key: String) =
        RequestStateEntity(key, null, null, null, 0, null, null, null, null, 1)
    private fun scopeKey(scope: String) = "scope:" + scope
    private fun hostKey(host: String) = "host:" + host
    private fun urlKey(url: String) = "url:" + url
    private fun physicalRequestId(url: String) = "aw:direct-url:v1:" + sha256(url)
    private fun directTargetId(url: String, key: String) = "aw:direct-target:v1:" + sha256(url + key)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun instantOrNull(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
    private fun maxInstant(a: Instant?, b: Instant): Instant = if (a == null || b.isAfter(a)) b else a
    private fun backoff(failures: Int): Duration {
        val exponentialMinutes = 30L * (1L shl (failures.coerceIn(1, 5) - 1))
        return Duration.ofMinutes(exponentialMinutes.coerceAtMost(6 * 60L))
    }
    private fun normalizeAniWorldUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("https", true) || uri.host?.lowercase() !in setOf("aniworld.to", "www.aniworld.to") ||
            uri.userInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath.contains("%2f", true) || uri.rawPath.split('/').any { it == "." || it == ".." } ||
            uri.rawPath.any { it.code < 0x20 || it.code == 0x7f } || value.length > 2048) return null
        "https://aniworld.to" + uri.rawPath
    }.getOrNull()

    companion object {
        const val SCOPE_ID = "aniworld-shadow-v1"
        private const val DIRECT_TYPE = "ANIWORLD_DIRECT_PAGE"
        private const val DIRECT_ROLE = "DIRECT"
        private const val MAX_WIRE_CALLS = 22
        private const val MAX_DIRECT_CALLS = 4
        private const val MAX_LIST_CALLS = 18
        private const val PAGE_SIZE = 256
        private const val RECEIPT_PAGE_SIZE = 256
        private const val MAX_PROVENANCE_ROWS = 512
        private const val MAX_MANIFEST_CHARS = 65_536
        private const val MAX_METRIC_CHARS = 16_384
        private val MINIMUM_BACKOFF = Duration.ofMinutes(30)
        private val SUCCESS_COOLDOWN = Duration.ofHours(6)
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Length-prefixed versioned payload; never uses Java object or class-name deserialization. */
internal object AniWorldShadowManifestCodec {
    fun encode(manifest: ShadowGenerationManifest, candidateDigest: String): String {
        val fields = mutableListOf("aw-shadow-manifest-v1", manifest.token.generationId,
            manifest.token.ownerToken, manifest.token.processEpoch, manifest.policyVersion.toString(),
            manifest.startedAt.toPollTimestamp(), manifest.deadlineAt.toPollTimestamp(), manifest.digest,
            candidateDigest, manifest.sources.size.toString())
        manifest.sources.forEach { source ->
            fields += listOf(source.instanceId, source.sourceType, source.targetKey, source.track.orEmpty(),
                source.requestUrl, source.requiredForRun.toString(), source.negativeRequired.toString(),
                source.physicalRequestId.orEmpty())
        }
        return pack(fields)
    }

    fun decode(payload: String): Pair<ShadowGenerationManifest, String>? {
        val fields = unpack(payload) ?: return null
        if (fields.size < 10 || fields[0] != "aw-shadow-manifest-v1") return null
        val count = fields[9].toIntOrNull() ?: return null
        if (count !in 3..11 || fields.size != 10 + count * 8) return null
        val sources = (0 until count).map { index ->
            val i = 10 + index * 8
            ShadowSourceSpec(fields[i], fields[i + 1], fields[i + 2], fields[i + 3].ifEmpty { null },
                fields[i + 4], fields[i + 5].toBooleanStrictOrNull() ?: return null,
                fields[i + 6].toBooleanStrictOrNull() ?: return null,
                fields[i + 7].ifEmpty { null })
        }
        val manifest = runCatching {
            ShadowGenerationManifest(ShadowGenerationToken(fields[1], fields[2], fields[3]),
                fields[4].toInt(), Instant.parse(fields[5]), Instant.parse(fields[6]), sources, fields[7])
        }.getOrNull() ?: return null
        if (manifest.digest != digest(sources)) return null
        val candidateDigest = fields[8]
        if (!candidateDigest.matches(Regex("[0-9a-f]{64}"))) return null
        return manifest to candidateDigest
    }

    fun digest(sources: List<ShadowSourceSpec>): String = sha256(pack(sources.flatMap { source ->
        listOf(source.instanceId, source.sourceType, source.targetKey, source.track.orEmpty(),
            source.requestUrl, source.requiredForRun.toString(), source.negativeRequired.toString(),
            source.physicalRequestId.orEmpty())
    }))

    private fun pack(fields: List<String>) = buildString {
        fields.forEach { append(it.length).append(':').append(it) }
    }
    private fun unpack(input: String): List<String>? {
        if (input.length > 65_536) return null
        val fields = mutableListOf<String>()
        var offset = 0
        while (offset < input.length) {
            val colon = input.indexOf(':', offset)
            if (colon <= offset || colon - offset > 6) return null
            val length = input.substring(offset, colon).toIntOrNull() ?: return null
            if (length < 0 || length > 65_536 || colon + 1 + length > input.length) return null
            fields += input.substring(colon + 1, colon + 1 + length)
            offset = colon + 1 + length
        }
        return fields
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

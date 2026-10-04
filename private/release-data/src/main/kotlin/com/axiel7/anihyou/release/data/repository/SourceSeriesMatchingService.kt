package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.log.AppLog
import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.MappingMutationResult
import com.axiel7.anihyou.release.core.api.MappingRematchOutcome
import com.axiel7.anihyou.release.core.api.UnmatchedSeries
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.matching.MatchDecision
import com.axiel7.anihyou.release.core.matching.MatchTier
import com.axiel7.anihyou.release.core.matching.ReleaseMatchRequest
import com.axiel7.anihyou.release.core.matching.ReleaseMatcher
import com.axiel7.anihyou.release.core.matching.SeasonCandidateRule
import com.axiel7.anihyou.release.core.sync.CandidatePoolRequest
import com.axiel7.anihyou.release.core.sync.CandidatePoolWindows
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.core.matching.TitleNormalizer
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.SourceMappingEntity
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import com.axiel7.anihyou.release.data.db.SourceSeriesLabelEntity
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.db.toEntity
import com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Targeted matching of one source series to one AniList entry, for the explicit rematch of the settings and for the
 * first resolution when Anime Details opens. It reuses the accepted matcher and, where it needs candidates, the
 * existing identity candidate source. It never sweeps a catalog and never overwrites an accepted binding on its own.
 */
class SourceSeriesMatchingService(
    private val database: ReleaseDatabase,
    private val policy: ExtensionProductPolicyRepository,
    private val navigation: FileProviderNavigationStateStore,
    private val candidates: IdentityCandidateSource,
    private val fence: MappingWriterFence,
    private val matcher: ReleaseMatcher = ReleaseMatcher(MATCHER_VERSION),
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dao = database.matchingDao()
    private val releaseDao = database.releaseDao()
    private val flightMutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    /**
     * First resolution for the media that Anime Details just opened. Persisted bindings come first and end the call
     * without any matcher run; callers that ask for the same source and media at the same time share one run.
     */
    suspend fun ensureForMedia(request: DetailMappingRequest) {
        val active = policy.policy.value.activeReleaseSource ?: return
        val key = "${MappingEntryIds.sourceKey(active)}|${request.mediaId}"
        val flight: CompletableDeferred<Unit>
        val owner: Boolean
        flightMutex.withLock {
            val running = inFlight[key]
            if (running != null) { flight = running; owner = false }
            else { flight = CompletableDeferred(); inFlight[key] = flight; owner = true }
        }
        if (owner) scope.launch {
            val failure = try { resolve(active, request); null } catch (failed: Throwable) { failed }
            // Leave the map first: a caller that resumes right after completion must start a fresh run, not find this one.
            flightMutex.withLock { inFlight.remove(key) }
            if (failure == null) flight.complete(Unit) else flight.completeExceptionally(failure)
        }
        flight.await()
    }

    private suspend fun resolve(active: ExtensionSelectionKey, request: DetailMappingRequest) {
        val sourceKey = MappingEntryIds.sourceKey(active)
        val media = request.mediaId
        if (dao.sourceBoundCount(active.sourceId, active.extensionId, active.publisherId, active.providerId,
                request.mediaId.toString()) > 0) {
            AppLog.d("matching") { "media=$media: a persisted mapping exists, no matcher run" }
            return
        }
        if (navigation.state.value.segments.any { it.key == active && it.mediaId == request.mediaId }) {
            AppLog.d("matching") { "media=$media: navigation segments already bind it" }
            return
        }
        if (request.format == "MOVIE") {
            AppLog.d("matching") { "media=$media: a movie is left to the explicit settings" }
            return
        }

        val titles = request.titles.ifEmpty { cachedTitles(request.mediaId) }
        if (titles.isEmpty()) {
            AppLog.d("matching") { "media=$media: no titles known" }
            return
        }
        val wanted = titles.map(TitleNormalizer::normalize)
        // A cour or part cannot be told apart by a season subject, so it is left to the explicit settings.
        if (wanted.any { it.part != null }) {
            AppLog.d("matching") { "media=$media: a cour/part title is left to the explicit settings" }
            return
        }
        val season = wanted.firstNotNullOfOrNull { it.season } ?: 1
        val wantedBases = wanted.map { it.base }.filter { it.isNotBlank() }.toSet()
        if (wantedBases.isEmpty()) {
            AppLog.d("matching") { "media=$media: titles normalise to nothing" }
            return
        }

        val hits = mutableListOf<SourceSeriesLabelEntity>()
        var offset = 0
        var scanned = 0
        val wantedTokens = wantedBases.flatMap { it.split(' ') }.filter { it.length > 2 }.toSet()
        val closest = ArrayList<Pair<Int, String>>()
        while (offset < MAX_LABELS) {
            val page = dao.labelsOf(active.sourceId, active.extensionId, active.publisherId, active.providerId, PAGE, offset)
            scanned += page.size
            page.forEach { label ->
                if (names(label).any { TitleNormalizer.normalize(it).base in wantedBases }) hits += label
                else if (AppLog.enabled) {
                    val overlap = names(label).maxOf { name ->
                        TitleNormalizer.normalize(name).base.split(' ').count { it in wantedTokens }
                    }
                    if (overlap > 0) closest += overlap to label.title
                }
            }
            if (page.size < PAGE) break
            offset += PAGE
        }
        // Exactly one source series may fit; two are ambiguous and stay unmatched.
        AppLog.i("matching") {
            "media=$media season=$season label hits=${hits.size} of $scanned source series for bases=$wantedBases" +
                if (hits.isEmpty() && closest.isNotEmpty())
                    " | closest source titles: " + closest.sortedByDescending { it.first }.take(3).joinToString { "'${it.second}'" }
                else ""
        }
        val label = hits.singleOrNull() ?: return
        val subject = runCatching {
            AniWorldMappingSubject.Season(AniWorldSiteIdentifier(label.providerSeriesKey), season)
        }.getOrNull() ?: return
        if (alreadyAccepted(active, subject)) {
            AppLog.d("matching") { "media=$media: the source series is already accepted for another entry" }
            return
        }
        // The writer epoch of this exact entry when the work begins; a reset or correction later raises it.
        val epoch = fence.epoch(MappingEntryRef.FENCE_V3_SOURCE, "$sourceKey|${subject.stableKey}|${ExternalProvider.ANILIST.value}")

        val decision = decide(
            ReleaseMatchRequest(sourceIdentity(active.providerId, label.providerSeriesKey, season), label.title,
                aliases = names(label).toSet() - label.title, season = season.takeIf { it in 1..99 }),
            listOf(IdentityCandidate(request.mediaId, titles, request.format,
                request.startYear?.let { runCatching { LocalDate.of(it, 1, 1) }.getOrNull() })),
        )
        val matched = decision as? MatchDecision.Matched ?: run {
            AppLog.i("matching") { "media=$media: matcher did not accept (${decision.javaClass.simpleName})" }
            return
        }
        if (matched.mediaId != request.mediaId || matched.tier !in AUTO_TIERS) {
            AppLog.i("matching") { "media=$media: matcher result media=${matched.mediaId} tier=${matched.tier} is not an automatic accept" }
            return
        }
        AppLog.i("matching") { "media=$media: automatic mapping written tier=${matched.tier} series=${label.providerSeriesKey}" }
        writeAuto(active, subject, matched, epoch)
    }

    /**
     * Binds the series of the active source's rows that have no binding yet to AniList entries, so that the calendar,
     * Behind and the details present them with AniList metadata. It uses the same matcher and the same automatic tiers
     * as [resolve]; a fuzzy hit is never written, and a series the user reset or corrected is left alone. One run spends
     * at most [maxSearches] AniList searches (the lookup cache answers a repeated title without the network), nearest
     * releases first, so the series the user looks at are bound first and the rest follow with the next refresh.
     */
    /** The unbound (series, season) pairs of this source's rows, nearest releases first, plus what is already taken. */
    private class Pending(val subjects: Int, val bound: Int, val series: List<Pair<String, Int>>, val takenMedia: Set<Int>)

    private suspend fun findPending(active: ExtensionSelectionKey): Pending {
        val now = clock.instant()
        val rows = database.reconciliationDao()
            .observeSourceProjections(active.sourceId, active.extensionId, active.publisherId, active.providerId).first()
        // (series, season) -> days between now and the nearest release or forecast of that series.
        val subjects = LinkedHashMap<Pair<String, Int>, Long>()
        rows.forEach { source ->
            val row = source.asCanonical()
            val identity = CanonicalReleaseIdentity.decode(row.projectionKey) ?: return@forEach
            if (identity.installment !is Installment.Episode) return@forEach
            val state = runCatching { ReleaseReconciliationMapper.state(row) }.getOrNull() ?: return@forEach
            val slug = identity.seriesPath.removePrefix("/anime/stream/")
            val at = state.releaseAt ?: state.forecastAt
            val distance = at?.let { kotlin.math.abs(java.time.Duration.between(now, it).toDays()) } ?: Long.MAX_VALUE
            // A forecast row proves no navigation season, so its source season stands in (see MappingLookup).
            state.navigationSeasons.ifEmpty { setOfNotNull(identity.sourceSeason) }.filter { it in 1..99 }.forEach { season ->
                subjects.merge(slug to season, distance) { a, b -> minOf(a, b) }
            }
        }
        val bindings = dao.observeSourceBoundAniListMappings(active.sourceId, active.extensionId, active.publisherId,
            active.providerId).first()
        val bound = bindings.filter { it.subjectType == "SEASON" && it.navigationSeason != null }
            .map { it.siteSlug to it.navigationSeason!! }.toSet()
        return Pending(subjects.size, bound.size,
            subjects.entries.filter { it.key !in bound }.sortedBy { it.value }.map { it.key },
            bindings.mapNotNull { it.externalId?.toIntOrNull() }.toSet())
    }

    private suspend fun seasonPool(): List<IdentityCandidate> {
        val windows = CandidatePoolWindows.currentAndPrevious(clock.instant().atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate())
        return windows.flatMap { window ->
            attempt { candidates.boundedSeasonPool(CandidatePoolRequest(window = window, maxPages = POOL_PAGES)).candidates }.orEmpty()
        }.distinctBy { it.mediaId }
    }

    /** The accepted matcher on the candidates that the season rule lets through. */
    private fun decide(request: ReleaseMatchRequest, candidates: List<IdentityCandidate>): MatchDecision {
        val today = clock.instant().atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        return matcher.match(request, SeasonCandidateRule.filter(request.season, candidates, today))
    }

    /** The active source (null without one), for the management list. */
    val activeSource: kotlinx.coroutines.flow.Flow<ExtensionSelectionKey?> =
        policy.policy.map { it.activeReleaseSource }.distinctUntilChanged()

    /** The series of the active source that no binding covers, with the title the source gave them. Local reads only. */
    suspend fun unmatched(active: ExtensionSelectionKey): List<UnmatchedSeries> = findPending(active).series.map { (slug, season) ->
        val label = dao.label(active.sourceId, active.extensionId, active.publisherId, active.providerId, slug)
        UnmatchedSeries(active, slug, season, label?.title ?: slug.replace(Regex("[-_]+"), " ").trim())
    }

    /**
     * The user's own choice for one unbound series. An existing binding (also one of the older provider-wide rows) is
     * never overwritten here; the correction of a binding has its own path.
     */
    suspend fun assign(series: UnmatchedSeries, mediaId: Int): MappingMutationResult {
        val active = policy.policy.value.activeReleaseSource?.takeIf { it == series.source } ?: return MappingMutationResult.STALE
        if (mediaId <= 0 || series.season !in 1..99) return MappingMutationResult.UNAVAILABLE
        val subject = runCatching { AniWorldMappingSubject.Season(AniWorldSiteIdentifier(series.seriesKey), series.season) }
            .getOrNull() ?: return MappingMutationResult.UNAVAILABLE
        return database.withTransaction {
            if (alreadyAccepted(active, subject)) return@withTransaction MappingMutationResult.STALE
            val now = clock.instant()
            val domain = ExternalMapping(subject, ExternalProvider.ANILIST, mediaId.toString(), MappingSource.MANUAL,
                MappingConfidence.EXACT, now, now, MappingStatus.ACTIVE, provenance = "settings-assign",
                parserVersion = "manual-v1")
            dao.upsertSourceMapping(domain.toEntity().forSource(active, now))
            AppLog.i("matching") { "user: assigned series=${series.seriesKey} season=${series.season} -> media=$mediaId" }
            MappingMutationResult.APPLIED
        }
    }

    suspend fun autoMatchPending(maxSearches: Int = AUTO_SEARCHES_PER_RUN): AutoMatchReport {
        val active = policy.policy.value.activeReleaseSource ?: return AutoMatchReport()
        val sourceKey = MappingEntryIds.sourceKey(active)
        // Decisions of an earlier matcher version are taken again: the first one matched later seasons to the first season.
        val dropped = dao.deleteAutoMappingsOtherThan(active.sourceId, active.extensionId, active.publisherId,
            active.providerId, MATCHER_VERSION)
        if (dropped > 0) AppLog.i("matching") { "auto match: $dropped automatic bindings of an earlier matcher version dropped" }
        val found = findPending(active)
        val takenMedia = found.takenMedia.toMutableSet()
        // The AniList entries of the current and the last season, loaded once and matched locally (the cache answers when
        // the pool is complete): a later season of a series airs now, the first season of it is not in this pool.
        val pool = seasonPool()
        AppLog.i("matching") { "auto match: candidate pool of the current and last season holds ${pool.size} entries" }
        val pending = found.series
        AppLog.i("matching") {
            "auto match start: series seasons=${found.subjects} bound=${found.bound} pending=${pending.size} budget=$maxSearches"
        }
        var examined = 0
        var matched = 0
        var searches = 0
        for ((slug, season) in pending) {
            if (searches >= maxSearches) break
            val subject = runCatching { AniWorldMappingSubject.Season(AniWorldSiteIdentifier(slug), season) }.getOrNull()
                ?: continue
            // A reset or corrected entry is the user's decision; the automatic pass never brings it back.
            if (dao.fence(MappingEntryRef.FENCE_V3_SOURCE, "$sourceKey|${subject.stableKey}|${ExternalProvider.ANILIST.value}") != null) continue
            if (alreadyAccepted(active, subject)) continue
            val label = dao.label(active.sourceId, active.extensionId, active.publisherId, active.providerId, slug)
            val title = label?.title ?: slug.replace(Regex("[-_]+"), " ").trim().ifBlank { continue }
            val identity = sourceIdentity(active.providerId, slug, season)
            val request = ReleaseMatchRequest(identity, title,
                aliases = label?.let { names(it).toSet() - it.title }.orEmpty(), season = season)
            val epoch = fence.epoch(MappingEntryRef.FENCE_V3_SOURCE, "$sourceKey|${subject.stableKey}|${ExternalProvider.ANILIST.value}")
            examined++
            val local = (attempt { candidates.localCandidates(setOf(identity)).candidates }.orEmpty() + pool)
                .distinctBy { it.mediaId }
            var decision = decide(request, local)
            val localHit = decision as? MatchDecision.Matched
            if (localHit == null || localHit.tier !in AUTO_TIERS) {
                val started = System.nanoTime()
                val found = attempt {
                    candidates.targetedSearch(TargetedIdentityQuery(identity, title, AUTO_FORMATS,
                        signature = "auto-match|${identity.stableKey}|$title")).candidates
                }.orEmpty()
                // A cached answer is instant; only a real request counts against the budget and is paced.
                if ((System.nanoTime() - started) / 1_000_000 > 150) {
                    searches++
                    delay(AUTO_SEARCH_PACE_MS)
                }
                decision = decide(request, (local + found).distinctBy { it.mediaId })
            }
            val accepted = decision as? MatchDecision.Matched
            if (accepted == null || accepted.tier !in AUTO_TIERS) {
                AppLog.d("matching") { "auto: series=$slug season=$season title='$title' -> no automatic match (${decision.javaClass.simpleName})" }
                continue
            }
            if (!takenMedia.add(accepted.mediaId)) {
                AppLog.i("matching") { "auto: series=$slug season=$season -> media=${accepted.mediaId} is already bound to another series, left for the user" }
                continue
            }
            AppLog.i("matching") { "auto: series=$slug season=$season title='$title' -> media=${accepted.mediaId} tier=${accepted.tier}" }
            writeAuto(active, subject, accepted, epoch)
            matched++
        }
        AppLog.i("matching") { "auto match done: examined=$examined matched=$matched searches=$searches pendingLeft=${pending.size - matched}" }
        return AutoMatchReport(pending.size, examined, matched, searches)
    }

    /**
     * The explicit rematch of one captured entry. The accepted binding stays until a valid replacement exists; a
     * replacement is written only if the entry was neither reset nor corrected since the epoch the work began with.
     */
    internal suspend fun rematch(ref: MappingEntryRef, currentMediaId: Int, epochAtStart: Long,
                                 searches: SearchBudget = SearchBudget(Int.MAX_VALUE)): MappingRematchOutcome {
        val (slug, season, source) = when (ref) {
            is MappingEntryRef.V3Source -> {
                val (subject, provider) = splitEntryKey(ref.entryKey)
                val row = dao.sourceMapping(ref.key.sourceId, ref.key.extensionId, ref.key.publisherId,
                    ref.key.providerId, subject, provider) ?: return MappingRematchOutcome.UNAVAILABLE
                Triple(row.siteSlug, row.navigationSeason.takeIf { row.subjectType == "SEASON" }, ref.key)
            }
            is MappingEntryRef.V3Legacy -> {
                val (subject, provider) = splitEntryKey(ref.entryKey)
                val row = releaseDao.getExternalMapping(subject, provider) ?: return MappingRematchOutcome.UNAVAILABLE
                Triple(row.siteSlug, row.navigationSeason.takeIf { row.subjectType == "SEASON" }, null)
            }
            // The older lane and manual episode offsets have no source title to match from.
            is MappingEntryRef.R2, is MappingEntryRef.Navigation -> return MappingRematchOutcome.UNAVAILABLE
        }
        if (season == null) return MappingRematchOutcome.UNAVAILABLE
        val label = source?.let { dao.label(it.sourceId, it.extensionId, it.publisherId, it.providerId, slug) }
        val title = label?.title ?: slug.replace(Regex("[-_]+"), " ").trim().ifBlank { return MappingRematchOutcome.UNAVAILABLE }
        val identity = sourceIdentity(source?.providerId ?: "aniworld", slug, season)
        val request = ReleaseMatchRequest(identity, title, aliases = label?.let { names(it).toSet() - it.title }.orEmpty(),
            season = season.takeIf { it in 1..99 })
        // The local pool first; only if it settles nothing, one bounded, cached AniList search for this one title.
        val local = attempt { candidates.localCandidates(setOf(identity)).candidates }.orEmpty()
        var decision = decide(request, local)
        val localHit = decision as? MatchDecision.Matched
        if (localHit == null || localHit.tier !in AUTO_TIERS) {
            // The explicit run has a bounded number of AniList searches; an entry it cannot examine stays as it is.
            if (searches.remaining <= 0) return MappingRematchOutcome.UNAVAILABLE
            searches.remaining--
            val searched = attempt {
                candidates.targetedSearch(TargetedIdentityQuery(identity, title, setOf("TV"),
                    signature = "settings-rematch|${identity.stableKey}|$title")).candidates
            }.orEmpty()
            delay(SEARCH_PACE_MS)
            decision = decide(request, (local + searched).distinctBy { it.mediaId })
        }
        val matched = decision as? MatchDecision.Matched ?: return MappingRematchOutcome.RETAINED
        if (matched.tier !in AUTO_TIERS) return MappingRematchOutcome.RETAINED
        if (matched.mediaId == currentMediaId) return MappingRematchOutcome.RETAINED
        return replace(ref, matched, epochAtStart)
    }

    private suspend fun replace(ref: MappingEntryRef, matched: MatchDecision.Matched, epochAtStart: Long): MappingRematchOutcome =
        database.withTransaction {
            val (kind, fenceKey) = ref.fence ?: return@withTransaction MappingRematchOutcome.UNAVAILABLE
            if (!fence.allowsEpoch(kind, fenceKey, epochAtStart)) return@withTransaction MappingRematchOutcome.STALE
            val now = clock.instant()
            val confidence = matched.tier.confidence()
            when (ref) {
                is MappingEntryRef.V3Source -> {
                    val (subject, provider) = splitEntryKey(ref.entryKey)
                    val row = dao.sourceMapping(ref.key.sourceId, ref.key.extensionId, ref.key.publisherId,
                        ref.key.providerId, subject, provider) ?: return@withTransaction MappingRematchOutcome.STALE
                    dao.upsertSourceMapping(row.copy(
                        externalId = matched.mediaId.toString(), mappingSource = MappingSource.PERSISTED.name,
                        mappingStatus = MappingStatus.ACTIVE.name, confidence = confidence.name, validatedAt = now.toString(),
                        staleAt = null, provenance = provenance(matched), revision = row.revision + 1, updatedAt = now.toString()))
                }
                is MappingEntryRef.V3Legacy -> {
                    val (subject, provider) = splitEntryKey(ref.entryKey)
                    val row = releaseDao.getExternalMapping(subject, provider)
                        ?: return@withTransaction MappingRematchOutcome.STALE
                    releaseDao.upsertExternalMapping(row.copy(
                        externalId = matched.mediaId.toString(), mappingSource = MappingSource.PERSISTED.name,
                        mappingStatus = MappingStatus.ACTIVE.name, confidence = confidence.name, validatedAt = now.toString(),
                        staleAt = null, provenance = provenance(matched)))
                }
                else -> return@withTransaction MappingRematchOutcome.UNAVAILABLE
            }
            fence.bump(kind, fenceKey, now)
            MappingRematchOutcome.REPLACED
        }

    private suspend fun writeAuto(active: ExtensionSelectionKey, subject: AniWorldMappingSubject.Season,
                                  matched: MatchDecision.Matched, epochAtStart: Long) {
        database.withTransaction {
            val entryKey = "${subject.stableKey}|${ExternalProvider.ANILIST.value}"
            if (!fence.allowsEpoch(MappingEntryRef.FENCE_V3_SOURCE, "${MappingEntryIds.sourceKey(active)}|$entryKey", epochAtStart)) {
                return@withTransaction
            }
            // Accepted bindings are kept; a concurrent writer that got there first wins.
            if (dao.sourceMapping(active.sourceId, active.extensionId, active.publisherId, active.providerId,
                    subject.stableKey, ExternalProvider.ANILIST.value) != null) return@withTransaction
            val now = clock.instant()
            val domain = ExternalMapping(subject, ExternalProvider.ANILIST, matched.mediaId.toString(),
                MappingSource.PERSISTED, matched.tier.confidence(), now, now, MappingStatus.ACTIVE,
                provenance = provenance(matched), parserVersion = matcher.matcherVersion)
            dao.upsertSourceMapping(domain.toEntity().forSource(active, now))
        }
    }

    private suspend fun alreadyAccepted(active: ExtensionSelectionKey, subject: AniWorldMappingSubject): Boolean {
        val own = dao.sourceMapping(active.sourceId, active.extensionId, active.publisherId, active.providerId,
            subject.stableKey, ExternalProvider.ANILIST.value)
        if (own != null) return true
        // After this source reset its own binding the old provider-wide row is hidden for it; resolve afresh.
        val fenced = dao.fence(MappingEntryRef.FENCE_V3_SOURCE,
            "${MappingEntryIds.sourceKey(active)}|${subject.stableKey}|${ExternalProvider.ANILIST.value}")
        if (fenced != null) return false
        return releaseDao.getExternalMapping(subject.stableKey, ExternalProvider.ANILIST.value)
            ?.toDomainOrNull()?.status == MappingStatus.ACTIVE
    }

    /** A failed lookup is just no candidates; only cancellation must propagate. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private suspend fun cachedTitles(mediaId: Int): Set<String> =
        dao.cachedCandidates(listOf(mediaId)).firstNotNullOfOrNull { it.toDomainOrNull() }?.titles.orEmpty()

    private fun names(label: SourceSeriesLabelEntity): List<String> =
        listOf(label.title) + label.aliasesPayload.lines().filter { it.isNotBlank() }

    private fun sourceIdentity(providerId: String, slug: String, season: Int) = SourceIdentity(
        ReleaseStreamKey(ProviderId(providerId), SourceSeriesKey(slug), ReleaseKind.EPISODE, season, LanguageTrack.DE_SUB),
        Installment.Episode(1))

    private fun provenance(matched: MatchDecision.Matched) = "targeted:${matched.tier.name}:${matcher.matcherVersion}"

    private fun MatchTier.confidence() = if (this == MatchTier.EXACT_NORMALIZED) MappingConfidence.EXACT else MappingConfidence.HIGH

    private fun com.axiel7.anihyou.release.data.db.ExternalMappingEntity.forSource(key: ExtensionSelectionKey, at: Instant) =
        SourceMappingEntity(key.sourceId, key.extensionId, key.publisherId, key.providerId, mappingSubjectKey,
            externalProvider, seriesStableKey, siteSlug, subjectType, navigationSeason, filmNumber, externalId,
            mappingSource, mappingStatus, confidence, createdAt, validatedAt, staleAt, provenance, parserVersion, 1L, at.toString())

    private companion object {
        const val PAGE = 500
        const val MAX_LABELS = 20_000
        /** Spacing between two network searches of one explicit rematch run. */
        const val SEARCH_PACE_MS = 700L
        /** AniList answers about 90 requests a minute; the automatic pass stays far below it and spreads over refreshes. */
        const val AUTO_SEARCHES_PER_RUN = 12
        /** Bumped with every change of the rules; automatic bindings of another version are decided again. */
        const val MATCHER_VERSION = "v3-season-strict-1"
        const val POOL_PAGES = 4
        const val AUTO_SEARCH_PACE_MS = 1_100L
        val AUTO_FORMATS = setOf("TV", "TV_SHORT", "ONA", "OVA")
        /** A fuzzy hit is never accepted without the user. */
        val AUTO_TIERS = setOf(MatchTier.EXACT_NORMALIZED, MatchTier.EXACT_BASE_SEASON, MatchTier.ALIAS)
    }
}

/** What one automatic matching pass saw and did; for the log and the tests. */
data class AutoMatchReport(val pending: Int = 0, val examined: Int = 0, val matched: Int = 0, val searches: Int = 0)

/** The AniList searches one explicit rematch run may still spend; local candidates never cost any. */
internal class SearchBudget(var remaining: Int)

/** An entry key is "<subject key>|<external provider>"; the subject key itself never contains the last bar. */
internal fun splitEntryKey(entryKey: String): Pair<String, String> =
    entryKey.substringBeforeLast('|') to entryKey.substringAfterLast('|')

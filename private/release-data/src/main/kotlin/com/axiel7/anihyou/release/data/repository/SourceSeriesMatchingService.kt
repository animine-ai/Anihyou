package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.log.AppLog
import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.MappingMutationResult
import com.axiel7.anihyou.release.core.api.MappingRematchOutcome
import com.axiel7.anihyou.release.core.api.UnmatchedSeries
import com.axiel7.anihyou.release.core.api.UnmatchedSuggestion
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        request.episodeExtent?.takeIf { it in 1..9999 }?.let { extent ->
            navigation.rememberNumbering(com.axiel7.anihyou.release.data.extension.ProviderMediaNumbering(
                media, request.titles, extent))
        }
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
        if (writeAuto(active, subject, matched, epoch)) {
            AppLog.i("matching") { "media=$media: automatic mapping written tier=${matched.tier} series=${label.providerSeriesKey}" }
        }
    }

    /**
     * Binds the series of the active source's rows that have no binding yet to AniList entries, so that the calendar,
     * Behind and the details present them with AniList metadata. It uses the same matcher and the same automatic tiers
     * as [resolve]; a fuzzy hit is never written. Background runs leave reset entries alone; an explicit user run
     * may retry them at their current writer epoch. A run asks
     * AniList for the calendar and the season pools; it spends at most [maxSearches] targeted lookups (none unless the
     * user asked for them), nearest releases first.
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

    /** The accepted matcher on the candidates that the season rule lets through. */
    private fun decide(request: ReleaseMatchRequest, candidates: List<IdentityCandidate>): MatchDecision {
        val today = clock.instant().atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        // A series that is released episode by episode is an episodic series whether AniList files it as TV or as ONA
        // (many currently airing ones are ONA). The accepted matcher takes a TV entry for an episode stream only.
        val episodic = SeasonCandidateRule.filter(request.season, candidates, today)
            .map { if (it.format.equals("ONA", ignoreCase = true)) it.copy(format = "TV") else it }
        return matcher.match(request, episodic)
    }

    /** The active source (null without one), for the management list. */
    val activeSource: kotlinx.coroutines.flow.Flow<ExtensionSelectionKey?> =
        policy.policy.map { it.activeReleaseSource }.distinctUntilChanged()

    /** Local overview of the exact same source series/season scope as the unmatched list. */
    fun observeStatistics(active: ExtensionSelectionKey) = observeStatisticsSnapshot(active)
        .map { it.second }.distinctUntilChanged()

    /** Pair counts with their receipt so a new refresh cannot display the prior refresh's cached counts. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeStatisticsSnapshot(active: ExtensionSelectionKey) = kotlinx.coroutines.flow.combine(
        database.reconciliationDao().observeSourceProjections(active.sourceId, active.extensionId, active.publisherId, active.providerId),
        dao.observeSourceBoundAniListMappings(active.sourceId, active.extensionId, active.publisherId, active.providerId),
        navigation.state,
    ) { _, _, receipt -> receipt }.mapLatest { receipt -> receipt to matchingStatistics(active) }.distinctUntilChanged()

    suspend fun matchingStatistics(active: ExtensionSelectionKey) = database.withTransaction {
        val pending = findPending(active)
        val open = pending.series.size
        // Historical bindings outside today's accepted source data do not inflate the matched count.
        com.axiel7.anihyou.release.core.source.ExtensionMatchingStatistics(pending.subjects,
            pending.subjects - open, open)
    }

    /** The series of the active source that no binding covers, with the title the source gave them. Local reads only. */
    suspend fun unmatched(active: ExtensionSelectionKey): List<UnmatchedSeries> = findPending(active).series.map { (slug, season) ->
        val label = dao.label(active.sourceId, active.extensionId, active.publisherId, active.providerId, slug)
        UnmatchedSeries(active, slug, season, label?.title ?: slug.replace(Regex("[-_]+"), " ").trim(),
            suggestions["${MappingEntryIds.sourceKey(active)}|$slug|$season"])
    }

    /**
     * The nearest AniList entry the last run saw for a series it could not match, to offer the user: it is never written
     * without the user. Kept in memory; the next run replaces it.
     */
    private val suggestions = java.util.concurrent.ConcurrentHashMap<String, UnmatchedSuggestion>()

    /** Counts the runs that changed the offers, so the list of the unmatched reads them again when a run is over. */
    val suggestionRevision = kotlinx.coroutines.flow.MutableStateFlow(0)

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

    private val runLock = Mutex()

    /**
     * One run at a time: a refresh, a button and the details may ask together, and a second run only waits and then finds
     * nothing left to do instead of spending the same AniList requests again.
     */
    suspend fun autoMatchPending(maxSearches: Int = 0, force: Boolean = false): AutoMatchReport =
        runLock.withLock { autoMatchLocked(maxSearches, force) }

    /** Explicit retry, including reset entries: the AniList calendar and pools, never a search per title. */
    suspend fun matchPendingNow(): AutoMatchReport = autoMatchPending(force = true)

    /**
     * The user's explicit wish (opt-in): after the calendar and the pools, ask AniList for the title of the series that are
     * still open, a few per run. It is slow, it mostly guesses and it costs AniList requests, so nothing starts it by itself.
     */
    suspend fun searchPendingNow(): AutoMatchReport = autoMatchPending(maxSearches = SEARCHES_PER_USER_RUN, force = true)

    /** When a series was last tried without a result, so the automatic runs after a refresh do not try it again and again. */
    private val lastTried = java.util.concurrent.ConcurrentHashMap<String, java.time.Instant>()
    /** Resume explicit bounded searches after the last attempted subject, so cached failures cannot starve the rest. */
    private val lastTargetedSubject = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun autoMatchLocked(maxSearches: Int, force: Boolean): AutoMatchReport {
        val active = policy.policy.value.activeReleaseSource ?: return AutoMatchReport()
        // Decisions of an earlier matcher version are taken again: the first one matched later seasons to the first season.
        val dropped = dao.deleteAutoMappingsOtherThan(active.sourceId, active.extensionId, active.publisherId,
            active.providerId, MATCHER_VERSION)
        if (dropped > 0) AppLog.i("matching") { "auto match: $dropped automatic bindings of an earlier matcher version dropped" }
        val found = findPending(active)
        val takenMedia = found.takenMedia.toMutableSet()
        // A series is matched once, as an anime (series and season), and the binding stays. Only the ones without a binding
        // are looked at, and one that found nothing is tried again after a few hours at the earliest (or by the button).
        val now = clock.instant()
        val retryAfter = now.minus(RETRY_AFTER)
        val open = if (force) found.series else found.series.filter { (slug, season) ->
            lastTried["${MappingEntryIds.sourceKey(active)}|$slug|$season"]?.isBefore(retryAfter) ?: true
        }
        AppLog.i("matching") {
            "auto match start: series seasons=${found.subjects} bound=${found.bound} unbound=${found.series.size} " +
                "tried=${open.size} (rest tried within ${RETRY_AFTER.toHours()} h) single-search budget=$maxSearches"
        }
        val today = now.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        val pool = PoolIndex()
        var matched = 0
        // 0. The user's own AniList list, then
        // 1. The AniList airing calendar, as far ahead as it goes: the data the calendar tab shows without a release source.
        //    It holds what airs now and what premieres soon, so the next season needs no search of its own. Day by day,
        //    today first, and it stops as soon as nothing is open.
        // 2. The current season, then one after the other further back (a year), as pools: for what does not air in these days.
        // 3. A single search is the last resort and only on the user's wish: it is slow, it mostly guesses, and it costs
        //    AniList requests. The automatic run passes a budget of none.
        // The database is asked once per series for the whole run (fence, binding, label, epoch, local candidates). The
        // rounds below only compare titles in memory: a round per calendar day and per season used to ask again each time.
        val runStart = System.nanoTime()
        fun since(start: Long) = (System.nanoTime() - start) / 1_000_000
        val permits = Semaphore(PREPARE_PARALLEL)
        var left: List<Prepared> = coroutineScope {
            open.map { (slug, season) -> async { permits.withPermit { prepare(active, slug, season, allowReset = force) } } }.awaitAll()
        }.filterNotNull()
        val examined = left.size
        AppLog.i("matching") {
            "auto match: prepared $examined series in ${since(runStart)} ms (${open.size - examined} skipped)"
        }
        suspend fun round(via: String) {
            val still = ArrayList<Prepared>()
            for (series in left) {
                val relevant = pool.sharing(series.tokens, series.compacts)
                // Nothing new for this series since the last round: the answer is the same, so it is not asked again.
                if (relevant.size == series.compared) { still += series; continue }
                series.compared = relevant.size
                if (bind(active, series, relevant, takenMedia, search = null, via = via) == Bind.BOUND) matched++ else still += series
            }
            left = still
        }
        // 0. The user's own AniList list first, as the older matcher did with its local active library: what the user
        //    watches or plans is the likeliest match, and it holds shows that no season of the last year does.
        if (left.isNotEmpty()) {
            val loadStart = System.nanoTime()
            val library = attempt { candidates.libraryCandidates() }.orEmpty()
            val loadMs = since(loadStart)
            pool.add(library)
            val roundStart = System.nanoTime()
            val before = matched
            round("list")
            AppLog.i("matching") {
                "auto match: AniList list +${library.size} entries (pool ${pool.size}), ${left.size} series open " +
                    "after ${matched - before} bound (load $loadMs ms, match ${since(roundStart)} ms)"
            }
        }
        for (offset in CALENDAR_DAY_OFFSETS) {
            if (left.isEmpty()) break
            val day = today.plusDays(offset.toLong())
            AppLog.d("matching") { "auto match: asking AniList for the calendar of $day" }
            val loadStart = System.nanoTime()
            val loaded = attempt { candidates.airingCandidates(day) }.orEmpty()
            val loadMs = since(loadStart)
            pool.add(loaded)
            val roundStart = System.nanoTime()
            val before = matched
            round("calendar $day")
            AppLog.i("matching") {
                "auto match: AniList calendar $day +${loaded.size} entries (pool ${pool.size}), ${left.size} series open " +
                    "after ${matched - before} bound (load $loadMs ms, match ${since(roundStart)} ms)"
            }
        }
        // The last seasons: a dub that runs weeks behind the original airs on the source when AniList is done with it.
        // They load two at a time (as the older matcher did, for the same kindness to AniList) while the first one is
        // already compared; a season is still compared in its order, and the loads that are not needed are dropped.
        val windows = CandidatePoolWindows.lastSeasons(today, POOL_PAGES.size)
        if (left.isNotEmpty()) coroutineScope {
            val poolPermits = Semaphore(POOL_PARALLEL)
            val loads = windows.mapIndexed { index, window ->
                async {
                    poolPermits.withPermit {
                        AppLog.d("matching") { "auto match: asking AniList for the season pool ${window.cacheKey} (up to ${POOL_PAGES[index]} pages)" }
                        val loadStart = System.nanoTime()
                        val batch = attempt {
                            candidates.boundedSeasonPool(CandidatePoolRequest(window = window, maxPages = POOL_PAGES[index])).candidates
                        }.orEmpty()
                        batch to since(loadStart)
                    }
                }
            }
            try {
                for ((index, window) in windows.withIndex()) {
                    if (left.isEmpty()) break
                    val (loaded, loadMs) = loads[index].await()
                    pool.add(loaded)
                    val roundStart = System.nanoTime()
                    val before = matched
                    round(window.cacheKey)
                    AppLog.i("matching") {
                        "auto match: pool ${window.cacheKey} +${loaded.size} entries (pool ${pool.size}), ${left.size} series open " +
                            "after ${matched - before} bound (load $loadMs ms, match ${since(roundStart)} ms)"
                    }
                }
            } finally {
                loads.forEach { it.cancel() }
            }
        }
        var searches = 0
        val stillOpen = ArrayList<Prepared>()
        val sourceKey = MappingEntryIds.sourceKey(active)
        val resumeAfter = left.indexOfFirst { "${it.slug}|${it.season}" == lastTargetedSubject[sourceKey] } + 1
        val searchOrder = if (maxSearches > 0 && resumeAfter in 1 until left.size)
            left.drop(resumeAfter) + left.take(resumeAfter) else left
        for (series in searchOrder) {
            if (searches < maxSearches && bind(active, series, pool.sharing(series.tokens, series.compacts), takenMedia,
                    search = {
                        searches++
                        lastTargetedSubject[sourceKey] = "${series.slug}|${series.season}"
                    }, via = "single search") == Bind.BOUND) matched++
            else stillOpen += series
        }
        left = stillOpen
        left.forEach { lastTried["${MappingEntryIds.sourceKey(active)}|${it.slug}|${it.season}"] = now }
        // Why a series stays open: its title and the nearest entries of everything that was looked at. The nearest one is
        // offered to the user (never written by itself), and a series that is matched now loses an older offer.
        val everything = pool.all
        val openKeys = left.mapTo(HashSet()) { "${MappingEntryIds.sourceKey(active)}|${it.slug}|${it.season}" }
        // A series of this run that is settled loses its offer; the ones this run did not look at keep theirs.
        open.forEach { (slug, season) ->
            val key = "${MappingEntryIds.sourceKey(active)}|$slug|$season"
            if (key !in openKeys) suggestions.remove(key)
        }
        left.forEachIndexed { index, series ->
            val nearest = closestEntries(series.request, everything, takenMedia)
            val key = "${MappingEntryIds.sourceKey(active)}|${series.slug}|${series.season}"
            val best = nearest.firstOrNull()?.takeIf { it.second >= SUGGESTION_MIN_SCORE }
            if (best != null) suggestions[key] = UnmatchedSuggestion(best.first.mediaId, best.first.titles.first(), best.second)
            else suggestions.remove(key)
            if (index < OPEN_SERIES_LOGGED) AppLog.i("matching") {
                "auto match: open series=${series.slug} season=${series.season} title='${series.title}' closest: " +
                    nearest.joinToString("; ") { (candidate, score) ->
                        "'${candidate.titles.first()}' (${candidate.mediaId}, ${candidate.format ?: "-"}, ${candidate.startDate ?: "-"}, ${"%.2f".format(score)})"
                    }.ifEmpty { "none shares a word" }
            }
        }
        suggestionRevision.value += 1
        AppLog.i("matching") {
            "auto match done: examined=$examined matched=$matched single searches=$searches open=${left.size} in ${since(runStart)} ms"
        }
        return AutoMatchReport(found.series.size, examined, matched, searches)
    }

    private enum class Bind { BOUND, NONE }

    /** What the database says about one series, read once per run; null when the series is not to be matched. */
    private class Prepared(
        val slug: String, val season: Int, val subject: AniWorldMappingSubject.Season, val title: String,
        val identity: SourceIdentity, val request: ReleaseMatchRequest, val epoch: Long, val local: List<IdentityCandidate>,
        /** The words and the space-less forms of the titles of the series: only entries that share one can match it. */
        val tokens: Set<String>, val compacts: Set<String>,
    ) {
        /** How many entries of the pool were compared the last time; a round without a new one gives the same answer. */
        var compared = -1
    }

    /**
     * The entries of the pool by the words of their titles. A series can only match an entry that shares a word (or the
     * space-less form of a title) with it, so each round compares a series with those few instead of with the whole pool.
     */
    private class PoolIndex {
        private val byId = LinkedHashMap<Int, IdentityCandidate>()
        private val byToken = HashMap<String, MutableList<IdentityCandidate>>()
        private val byCompact = HashMap<String, MutableList<IdentityCandidate>>()
        val size: Int get() = byId.size
        val all: List<IdentityCandidate> get() = byId.values.toList()

        fun add(entries: Collection<IdentityCandidate>) {
            for (entry in entries) {
                if (byId.putIfAbsent(entry.mediaId, entry) != null) continue
                val tokens = HashSet<String>()
                val compacts = HashSet<String>()
                entry.titles.forEach { title ->
                    val normalized = TitleNormalizer.normalize(title)
                    tokens += normalized.tokens
                    if (normalized.compact.isNotEmpty()) compacts += normalized.compact
                }
                tokens.forEach { byToken.getOrPut(it) { ArrayList() } += entry }
                compacts.forEach { byCompact.getOrPut(it) { ArrayList() } += entry }
            }
        }

        fun sharing(tokens: Set<String>, compacts: Set<String>): List<IdentityCandidate> {
            if (tokens.isEmpty() && compacts.isEmpty()) return all
            val found = LinkedHashMap<Int, IdentityCandidate>()
            tokens.forEach { token -> byToken[token]?.forEach { found.putIfAbsent(it.mediaId, it) } }
            compacts.forEach { compact -> byCompact[compact]?.forEach { found.putIfAbsent(it.mediaId, it) } }
            return found.values.toList()
        }
    }

    private suspend fun prepare(active: ExtensionSelectionKey, slug: String, season: Int, allowReset: Boolean): Prepared? {
        val sourceKey = MappingEntryIds.sourceKey(active)
        val subject = runCatching { AniWorldMappingSubject.Season(AniWorldSiteIdentifier(slug), season) }.getOrNull()
            ?: return null
        // Keep the fence, including for explicit retries: a later reset must still block this run's write.
        // Read its epoch once before any candidate work, so a reset during preparation cannot be absorbed.
        val initialFence = dao.fence(MappingEntryRef.FENCE_V3_SOURCE,
            "$sourceKey|${subject.stableKey}|${ExternalProvider.ANILIST.value}")
        if (!allowReset && initialFence != null) return null
        val epoch = initialFence?.epoch ?: 0L
        if (alreadyAccepted(active, subject)) return null
        val label = dao.label(active.sourceId, active.extensionId, active.publisherId, active.providerId, slug)
        val title = label?.title ?: slug.replace(Regex("[-_]+"), " ").trim().ifBlank { return null }
        val identity = sourceIdentity(active.providerId, slug, season)
        val request = ReleaseMatchRequest(identity, title,
            aliases = label?.let { names(it).toSet() - it.title }.orEmpty(), season = season)
        val local = attempt { candidates.localCandidates(setOf(identity)).candidates }.orEmpty()
        val normalizedNames = (listOf(title) + request.aliases).map { TitleNormalizer.normalize(it) }
        return Prepared(slug, season, subject, title, identity, request, epoch, local,
            tokens = normalizedNames.flatMapTo(HashSet()) { it.tokens },
            compacts = normalizedNames.mapNotNullTo(HashSet()) { it.compact.ifEmpty { null } })
    }

    /** One prepared series against the candidates at hand; [search] non-null also asks AniList for this one title (last resort). */
    private suspend fun bind(
        active: ExtensionSelectionKey, series: Prepared, pool: List<IdentityCandidate>,
        takenMedia: MutableSet<Int>, search: (() -> Unit)?, via: String,
    ): Bind {
        val slug = series.slug
        val season = series.season
        val title = series.title
        val identity = series.identity
        val request = series.request
        val local = (series.local + pool).distinctBy { it.mediaId }
        var decision: MatchDecision = decide(request, local)
        var accepted = (decision as? MatchDecision.Matched)?.takeIf { it.tier in AUTO_TIERS }
        // The same title with other spaces or hyphens ("Kurumichan", "Kurumi-chan") is the same title.
        if (accepted == null) accepted = compactTitle(request, local)
        // The numbering of the source differs from AniList for long franchises (season 5 there, "Season 2" here): the one
        // entry of that title that airs now is the continuation.
        if (accepted == null) accepted = continuation(request, pool)
        if (accepted == null && search != null) {
            // Count dispatches, not elapsed time: a fast network response is still a lookup. The cache remains
            // inside the candidate source, so this conservative bound also covers instant cached answers.
            search()
            val searched = attempt {
                candidates.targetedSearch(TargetedIdentityQuery(identity, title, AUTO_FORMATS,
                    signature = "auto-match|${identity.stableKey}|$title")).candidates
            }.orEmpty()
            delay(AUTO_SEARCH_PACE_MS)
            decision = decide(request, (local + searched).distinctBy { it.mediaId })
            accepted = (decision as? MatchDecision.Matched)?.takeIf { it.tier in AUTO_TIERS }
        }
        if (accepted == null) {
            if (search != null) AppLog.d("matching") { "auto: series=$slug season=$season title='$title' -> no automatic match (${decision.javaClass.simpleName})" }
            return Bind.NONE
        }
        if (!takenMedia.add(accepted.mediaId)) {
            AppLog.i("matching") { "auto: series=$slug season=$season -> media=${accepted.mediaId} is already bound to another series, left for the user" }
            return Bind.NONE
        }
        if (!writeAuto(active, series.subject, accepted, series.epoch)) {
            takenMedia.remove(accepted.mediaId)
            AppLog.i("matching") { "auto: series=$slug season=$season write skipped after reset or concurrent binding" }
            return Bind.NONE
        }
        AppLog.i("matching") { "auto: series=$slug season=$season title='$title' -> media=${accepted.mediaId} tier=${accepted.tier} via $via" }
        return Bind.BOUND
    }

    /** The one entry that carries the title of the series up to its spaces and hyphens and that the season rule lets through. */
    private fun compactTitle(request: ReleaseMatchRequest, pool: List<IdentityCandidate>): MatchDecision.Matched? {
        val today = clock.instant().atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        val wanted = (listOf(request.title) + request.aliases).map { TitleNormalizer.normalize(it).compact }
            .filter { it.length >= COMPACT_MIN_LENGTH }.toSet()
        if (wanted.isEmpty()) return null
        val only = SeasonCandidateRule.filter(request.season, pool, today).filter { candidate ->
            (candidate.format == null || candidate.format in CONTINUATION_FORMATS) &&
                candidate.titles.any { TitleNormalizer.normalize(it).compact in wanted }
        }.singleOrNull() ?: return null
        return MatchDecision.Matched(only.mediaId, MatchTier.ALIAS, 0.93, null, "compact-title", only)
    }

    /**
     * The one recent entry of the pool that carries the title of the series, whatever season number either side gives it.
     * An entry whose title only begins with the title of the series counts when no entry carries exactly that title: the
     * source says "Bleach" where AniList calls the continuation "Bleach: Thousand-Year Blood War".
     */
    private fun continuation(request: ReleaseMatchRequest, pool: List<IdentityCandidate>): MatchDecision.Matched? {
        val season = request.season ?: return null
        if (season <= 1) return null
        val today = clock.instant().atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        // The source marks a remake with its year ("JoJo's Bizarre Adventure (2012)"); AniList does not.
        val wanted = (listOf(request.title) + request.aliases).map { TitleNormalizer.normalize(it).base }
            .flatMap { listOf(it, it.replace(YEAR_SUFFIX, "")) }.filter { it.isNotBlank() }.toSet()
        val airing = pool.filter { candidate ->
            SeasonCandidateRule.isRecent(candidate.startDate, today) &&
                (candidate.format == null || candidate.format in CONTINUATION_FORMATS)
        }
        val exact = airing.filter { candidate -> candidate.titles.any { TitleNormalizer.normalize(it).base in wanted } }
        val only = when {
            exact.size == 1 -> exact.single()
            exact.size > 1 -> return null
            else -> airing.filter { candidate ->
                candidate.titles.any { title ->
                    val base = TitleNormalizer.normalize(title).base
                    wanted.any { it.length >= CONTINUATION_MIN_PREFIX && base.startsWith("$it ") }
                }
            }.singleOrNull() ?: return null
        }
        return MatchDecision.Matched(only.mediaId, MatchTier.ALIAS, 0.9, null, "airing-continuation", only)
    }

    /** For a series that stays open: the entries of the pool whose titles share the most words with it, nearest first. */
    private fun closestEntries(
        request: ReleaseMatchRequest, pool: List<IdentityCandidate>, taken: Set<Int>, limit: Int = 2,
    ): List<Pair<IdentityCandidate, Double>> {
        val wanted = (listOf(request.title) + request.aliases).map { TitleNormalizer.normalize(it).tokens.toSet() }
            .filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        return pool.mapNotNull { candidate ->
            if (candidate.mediaId in taken) return@mapNotNull null
            val best = candidate.titles.maxOfOrNull { title ->
                val tokens = TitleNormalizer.normalize(title).tokens.toSet()
                wanted.maxOf { words ->
                    val shared = words.intersect(tokens).size
                    if (shared == 0) 0.0 else shared.toDouble() / words.union(tokens).size
                }
            } ?: 0.0
            if (best > 0.0) candidate to best else null
        }.sortedByDescending { it.second }.take(limit)
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
                                  matched: MatchDecision.Matched, epochAtStart: Long): Boolean =
        database.withTransaction {
            val entryKey = "${subject.stableKey}|${ExternalProvider.ANILIST.value}"
            if (!fence.allowsEpoch(MappingEntryRef.FENCE_V3_SOURCE, "${MappingEntryIds.sourceKey(active)}|$entryKey", epochAtStart)) {
                return@withTransaction false
            }
            // Accepted bindings are kept; a concurrent writer that got there first wins.
            if (dao.sourceMapping(active.sourceId, active.extensionId, active.publisherId, active.providerId,
                    subject.stableKey, ExternalProvider.ANILIST.value) != null) return@withTransaction false
            val now = clock.instant()
            val domain = ExternalMapping(subject, ExternalProvider.ANILIST, matched.mediaId.toString(),
                MappingSource.PERSISTED, matched.tier.confidence(), now, now, MappingStatus.ACTIVE,
                provenance = provenance(matched), parserVersion = matcher.matcherVersion)
            dao.upsertSourceMapping(domain.toEntity().forSource(active, now))
            true
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
        /** AniList answers about 90 requests a minute; one explicit search run stays far below it. */
        const val SEARCHES_PER_USER_RUN = 8
        val RETRY_AFTER: java.time.Duration = java.time.Duration.ofHours(6)
        /** Bumped with every change of the rules; automatic bindings of another version are decided again. */
        const val MATCHER_VERSION = "v3-season-strict-1"
        /** Pages of the season pools, the current season first and then one after the other further back (a year in all). */
        val POOL_PAGES = listOf(8, 8, 5, 4, 4)
        const val PREPARE_PARALLEL = 4
        /** Season pools loaded at the same time: two, like the older matcher (and like the request budget of AniList allows). */
        const val POOL_PARALLEL = 2
        const val COMPACT_MIN_LENGTH = 6
        val YEAR_SUFFIX = Regex(" (19|20)\\d{2}$")
        const val SUGGESTION_MIN_SCORE = 0.4
        /** The calendar days asked for, in order: today, then the week ahead, then yesterday (the source lists recent releases too). */
        val CALENDAR_DAY_OFFSETS = listOf(0, 1, 2, 3, 4, 5, 6, 7, -1)
        const val AUTO_SEARCH_PACE_MS = 1_100L
        const val OPEN_SERIES_LOGGED = 40
        const val CONTINUATION_MIN_PREFIX = 5
        val CONTINUATION_FORMATS = setOf("TV", "TV_SHORT", "ONA")
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

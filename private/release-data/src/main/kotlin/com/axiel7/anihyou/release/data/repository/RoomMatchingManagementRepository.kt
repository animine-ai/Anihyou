package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.log.AppLog
import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import com.axiel7.anihyou.release.core.api.ManagedMapping
import com.axiel7.anihyou.release.core.api.MappingActionToken
import com.axiel7.anihyou.release.core.api.MappingMutationResult
import com.axiel7.anihyou.release.core.api.MappingPage
import com.axiel7.anihyou.release.core.api.MappingQuery
import com.axiel7.anihyou.release.core.api.MappingRematchOutcome
import com.axiel7.anihyou.release.core.api.MappingRematchProgress
import com.axiel7.anihyou.release.core.api.MappingScope
import com.axiel7.anihyou.release.core.api.MappingSourceFacet
import com.axiel7.anihyou.release.core.api.MatcherOption
import com.axiel7.anihyou.release.core.api.MatchingManagementRepository
import com.axiel7.anihyou.release.core.api.UnmatchedSeries
import com.axiel7.anihyou.release.core.matching.SearchTitleFolding
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.data.db.ManagedMappingRow
import com.axiel7.anihyou.release.data.db.MappingActionEntity
import com.axiel7.anihyou.release.data.db.MappingActionEntryEntity
import com.axiel7.anihyou.release.data.db.MappingRefRow
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Settings-only management of every accepted persistent binding: source-bound rows of Room v14, the older
 * provider-wide rows and the R2 lane (both of unknown origin, shown as such, never attributed to a source) and the
 * source-bound manual episode segments of the navigation store.
 *
 * Browsing is a bounded indexed read: no network, no matcher, no metadata hydration. A confirmed scope is frozen in
 * Room as ids with revisions and is never expanded again. Reset and correction raise the writer fence of the entry in
 * the same transaction, so a job that began before cannot bring a removed or corrected binding back.
 */
class RoomMatchingManagementRepository(
    private val database: ReleaseDatabase,
    private val navigation: FileProviderNavigationStateStore,
    private val sources: ExtensionSourceRepository,
    private val service: SourceSeriesMatchingService,
    private val fence: MappingWriterFence = MappingWriterFence(database),
    private val clock: Clock = Clock.systemUTC(),
    /** AniList searches one confirmed rematch run may spend; the rest of the scope is still examined locally. */
    private val searchesPerRun: Int = MAX_SEARCHES_PER_RUN,
) : MatchingManagementRepository {
    private val dao = database.matchingDao()
    private val releaseDao = database.releaseDao()
    private val mutation = Mutex()

    // --- browsing ---------------------------------------------------------------------------------------------------
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePage(query: MappingQuery): Flow<MappingPage> {
        val triggers: List<Flow<Any>> = listOf(
            dao.observeSourceMappingTrigger(), dao.observeLabelTrigger(), dao.observeLegacyV3Trigger(),
            dao.observeR2Trigger(), navigation.state.map { it.segments }, sources.sources.map { labelsOf(it) },
        )
        return combine(triggers) { it.size }.mapLatest { load(query) }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }

    private suspend fun load(query: MappingQuery): MappingPage {
        val raw = query.text.trim()
        val folded = SearchTitleFolding.fold(raw)
        val labels = labelsOf(sources.sources.value)
        val key = query.source
        val filter = if (key == null) 0 else 1
        val roomTotal = dao.managedCount(folded, raw, filter, key?.sourceId.orEmpty(), key?.extensionId.orEmpty(),
            key?.publisherId.orEmpty(), key?.providerId.orEmpty())
        val roomRows = if (query.offset < roomTotal) dao.managedPage(folded, raw, filter, key?.sourceId.orEmpty(),
            key?.extensionId.orEmpty(), key?.publisherId.orEmpty(), key?.providerId.orEmpty(), query.limit, query.offset)
        else emptyList()
        val segments = matchingSegments(key, folded, raw)
        val segmentSlice = segments.drop((query.offset - roomTotal).coerceAtLeast(0)).take(query.limit - roomRows.size)
        val targets = targetTitles(roomRows.mapNotNull { it.externalId.toIntOrNull() } + segmentSlice.map { it.mediaId })
        val entries = roomRows.mapNotNull { it.toManaged(labels, targets) } +
            segmentSlice.map { it.toManaged(labels, targets) }
        return MappingPage(entries, roomTotal + segments.size, facets(labels))
    }

    /** The manual episode segments that fit the filter, in a stable order. At most a few hundred. */
    private suspend fun matchingSegments(key: ExtensionSelectionKey?, folded: String, raw: String):
        List<ProviderEpisodeSegment> = navigation.state.value.segments
        .filter { key == null || it.key == key }
        .filter { segment ->
            if (raw.isEmpty()) return@filter true
            val label = dao.label(segment.key.sourceId, segment.key.extensionId, segment.key.publisherId,
                segment.key.providerId, segment.seriesKey)
            segment.mediaId.toString() == raw || SearchTitleFolding.fold(segment.seriesKey).contains(folded) ||
                label?.titleNormalized?.contains(folded) == true || label?.aliasesPayload?.contains(folded) == true
        }
        .sortedWith(compareBy({ SearchTitleFolding.fold(it.seriesKey) }, { MappingEntryIds.sourceKey(it.key) },
            { it.sourceSeason }, { it.providerFirst }, { it.canonicalFirst }))

    private suspend fun facets(labels: Map<ExtensionSelectionKey, String>): List<MappingSourceFacet> {
        val counts = LinkedHashMap<ExtensionSelectionKey, Int>()
        dao.sourceFacets().forEach { row ->
            val key = runCatching { ExtensionSelectionKey(row.sourceId, row.extensionId, row.publisherId, row.providerId) }
                .getOrNull() ?: return@forEach
            counts[key] = (counts[key] ?: 0) + row.total
        }
        navigation.state.value.segments.forEach { counts[it.key] = (counts[it.key] ?: 0) + 1 }
        return counts.map { (key, total) -> MappingSourceFacet(key, labels[key] ?: key.extensionId, total) }
            .sortedWith(compareBy({ it.label.lowercase() }, { MappingEntryIds.sourceKey(it.key) }))
    }

    private suspend fun targetTitles(mediaIds: List<Int>): Map<Int, String> {
        if (mediaIds.isEmpty()) return emptyMap()
        val titles = HashMap<Int, String>()
        dao.cachedCandidates(mediaIds.distinct()).forEach { row ->
            if (row.mediaId !in titles) row.toDomainOrNull()?.titles?.firstOrNull()?.let { titles[row.mediaId] = it }
        }
        return titles
    }

    private fun labelsOf(list: List<com.axiel7.anihyou.release.core.source.ExtensionSource>):
        Map<ExtensionSelectionKey, String> {
        val labels = HashMap<ExtensionSelectionKey, String>()
        list.forEach { source ->
            source.extensions.forEach { extension ->
                val key = runCatching {
                    ExtensionSelectionKey(source.id, extension.extensionId, extension.publisherId, extension.providerId)
                }.getOrNull() ?: return@forEach
                labels[key] = extension.displayName
            }
        }
        return labels
    }

    private fun ManagedMappingRow.toManaged(labels: Map<ExtensionSelectionKey, String>, targets: Map<Int, String>):
        ManagedMapping? {
        val media = externalId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return when (kind) {
            MappingEntryRef.FENCE_V3_SOURCE -> {
                val source = runCatching { ExtensionSelectionKey(sourceId, extensionId, publisherId, providerId) }.getOrNull()
                    ?: return null
                ManagedMapping(
                    id = MappingEntryIds.encode(MappingEntryRef.V3Source(source, entryKey)), revision = revisionKey,
                    source = source, sourceLabel = labels[source] ?: source.extensionId, sourceTitle = labelTitle,
                    sourceIdentity = seriesKey, partLabel = subjectPart(), mediaId = media, targetTitle = targets[media],
                    manual = mappingSource == MappingSource.MANUAL.name)
            }
            MappingEntryRef.FENCE_V3_LEGACY -> ManagedMapping(
                id = MappingEntryIds.encode(MappingEntryRef.V3Legacy(entryKey)), revision = revisionKey, source = null,
                sourceLabel = "", sourceTitle = null, sourceIdentity = seriesKey, partLabel = subjectPart(),
                mediaId = media, targetTitle = targets[media], manual = mappingSource == MappingSource.MANUAL.name)
            MappingEntryRef.FENCE_R2 -> {
                val parts = entryKey.split('/')
                ManagedMapping(
                    id = MappingEntryIds.encode(MappingEntryRef.R2(entryKey)), revision = revisionKey, source = null,
                    sourceLabel = "", sourceTitle = null, sourceIdentity = parts.getOrNull(1) ?: entryKey,
                    partLabel = r2Part(parts), mediaId = media, targetTitle = targets[media],
                    manual = mappingSource == MappingOrigin.MANUAL.name)
            }
            else -> null
        }
    }

    private fun ManagedMappingRow.subjectPart() = when (subjectType) {
        "SEASON" -> navigationSeason?.let { "S$it" }.orEmpty()
        "FILM" -> filmNumber?.let { "Film $it" }.orEmpty()
        else -> ""
    }

    private fun r2Part(parts: List<String>): String {
        val season = parts.getOrNull(3)?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.let { "S$it" }
        return listOfNotNull(season, parts.getOrNull(4)?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun ProviderEpisodeSegment.toManaged(labels: Map<ExtensionSelectionKey, String>, targets: Map<Int, String>) =
        ManagedMapping(
            id = MappingEntryIds.encode(MappingEntryIds.navigation(this)),
            revision = MappingEntryIds.navigationRevision(this), source = key, sourceLabel = labels[key] ?: key.extensionId,
            sourceTitle = null, sourceIdentity = seriesKey,
            partLabel = "S$sourceSeason · E$providerFirst-${providerFirst + count - 1} -> E$canonicalFirst-${canonicalFirst + count - 1}",
            mediaId = mediaId, targetTitle = targets[mediaId], manual = true)

    // --- capture, reset, correction ---------------------------------------------------------------------------------
    override suspend fun capture(scope: MappingScope): MappingActionToken = withContext(Dispatchers.IO) {
        val members: List<Pair<String, String>>
        val kind: String
        var payload = ""
        when (scope) {
            is MappingScope.Entries -> {
                require(scope.revisions.size <= MAX_SCOPE_ENTRIES) { "scope is too large" }
                require(scope.revisions.keys.all { MappingEntryIds.decode(it) != null }) { "unknown entry id" }
                members = scope.revisions.entries.sortedBy { it.key }.map { it.key to it.value }
                kind = "ENTRIES"
            }
            is MappingScope.Source -> {
                members = membersOf(scope.key)
                kind = "SOURCE"
                payload = MappingEntryIds.sourceKey(scope.key)
            }
            MappingScope.All -> { members = membersOf(null); kind = "ALL" }
        }
        val token = UUID.randomUUID().toString()
        val now = clock.instant()
        database.withTransaction {
            val before = now.minus(TOKEN_TTL).toString()
            dao.pruneActionEntries(before)
            dao.pruneActions(before)
            dao.insertAction(MappingActionEntity(token, now.toString(), kind, payload, members.size, null))
            members.chunked(CHUNK).forEachIndexed { chunk, rows ->
                dao.insertActionEntries(rows.mapIndexed { index, (id, revision) ->
                    MappingActionEntryEntity(token, chunk * CHUNK + index, id, revision)
                })
            }
        }
        MappingActionToken(token, members.size)
    }

    /** Identity and revision of everything accepted in one source, or in the whole store, right now. */
    private suspend fun membersOf(key: ExtensionSelectionKey?): List<Pair<String, String>> {
        val rows = dao.managedRefs(if (key == null) 0 else 1, key?.sourceId.orEmpty(), key?.extensionId.orEmpty(),
            key?.publisherId.orEmpty(), key?.providerId.orEmpty())
        val room = rows.mapNotNull { row -> row.toRef()?.let { MappingEntryIds.encode(it) to row.revisionKey } }
        val segments = navigation.state.value.segments.filter { key == null || it.key == key }
            .map { MappingEntryIds.encode(MappingEntryIds.navigation(it)) to MappingEntryIds.navigationRevision(it) }
        return (room + segments).also { require(it.size <= MAX_SCOPE_ENTRIES) { "scope is too large" } }
    }

    private fun MappingRefRow.toRef(): MappingEntryRef? = when (kind) {
        MappingEntryRef.FENCE_V3_SOURCE -> runCatching {
            MappingEntryRef.V3Source(ExtensionSelectionKey(sourceId, extensionId, publisherId, providerId), entryKey)
        }.getOrNull()
        MappingEntryRef.FENCE_V3_LEGACY -> MappingEntryRef.V3Legacy(entryKey)
        MappingEntryRef.FENCE_R2 -> MappingEntryRef.R2(entryKey)
        else -> null
    }

    private class Abort(val result: MappingMutationResult) : RuntimeException(null, null, false, false)

    override suspend fun reset(token: MappingActionToken): MappingMutationResult = withContext(Dispatchers.IO) {
        AppLog.i("matching") { "user: reset mappings of a captured scope" }
        mutation.withLock {
            val action = dao.action(token.value) ?: return@withLock MappingMutationResult.UNAVAILABLE
            if (action.consumedAt != null) return@withLock MappingMutationResult.UNAVAILABLE
            val entries = entriesOf(token.value)
            if (entries.size != action.entryCount) return@withLock MappingMutationResult.UNAVAILABLE
            val decoded = entries.map { (MappingEntryIds.decode(it.entryId) ?: return@withLock MappingMutationResult.UNAVAILABLE) to it.revision }

            // The manual episode segments live in a file, a second commit boundary. Keep the captured Room action
            // unconsumed until both stores commit: if the process dies after Room commits, the durable action entries
            // let a retry skip already-removed Room rows and finish removing the still-listed file segments.
            val storedSegments = navigation.state.value.segments.associateBy { MappingEntryIds.navigation(it) }
            val removals = HashSet<ProviderEpisodeSegment>()
            for ((ref, revision) in decoded) {
                if (ref !is MappingEntryRef.Navigation) continue
                val segment = storedSegments[ref] ?: continue
                if (MappingEntryIds.navigationRevision(segment) != revision) return@withLock MappingMutationResult.STALE
                removals += segment
            }
            val now = clock.instant()
            try {
                database.withTransaction {
                    for ((ref, revision) in decoded) {
                        if (ref is MappingEntryRef.Navigation) continue
                        val current = current(ref) ?: continue
                        if (current.revision != revision) throw Abort(MappingMutationResult.STALE)
                        remove(ref)
                        ref.fence?.let { (kind, key) -> fence.bump(kind, key, now) }
                    }
                }
            } catch (abort: Abort) {
                return@withLock abort.result
            }
            navigation.removeSegments(removals)
            val consumed = database.withTransaction {
                dao.consumeAction(token.value, clock.instant().toString())
            }
            if (consumed != 1) return@withLock MappingMutationResult.UNAVAILABLE
            MappingMutationResult.APPLIED
        }
    }

    private suspend fun entriesOf(token: String): List<MappingActionEntryEntity> {
        val all = ArrayList<MappingActionEntryEntity>()
        var from = 0
        while (true) {
            val page = dao.actionEntries(token, from, CHUNK)
            if (page.isEmpty()) return all
            all += page
            from = page.last().ordinal + 1
        }
    }

    private class Current(val revision: String, val mediaId: Int?)

    private suspend fun current(ref: MappingEntryRef): Current? = when (ref) {
        is MappingEntryRef.V3Source -> {
            val (subject, provider) = splitEntryKey(ref.entryKey)
            dao.sourceMapping(ref.key.sourceId, ref.key.extensionId, ref.key.publisherId, ref.key.providerId, subject, provider)
                ?.takeIf { it.mappingStatus in MANAGED_STATUSES }
                ?.let { Current(it.revision.toString(), it.externalId?.toIntOrNull()) }
        }
        is MappingEntryRef.V3Legacy -> {
            val (subject, provider) = splitEntryKey(ref.entryKey)
            releaseDao.getExternalMapping(subject, provider)?.takeIf { it.mappingStatus in MANAGED_STATUSES }
                ?.let { Current(MappingEntryIds.legacyV3Revision(it), it.externalId?.toIntOrNull()) }
        }
        is MappingEntryRef.R2 -> releaseDao.getMapping(ref.streamKey)?.takeIf { it.mediaId != null }
            ?.let { Current(MappingEntryIds.r2Revision(it), it.mediaId) }
        is MappingEntryRef.Navigation -> navigation.state.value.segments
            .firstOrNull { MappingEntryIds.navigation(it) == ref }
            ?.let { Current(MappingEntryIds.navigationRevision(it), it.mediaId) }
    }

    private suspend fun remove(ref: MappingEntryRef) {
        when (ref) {
            is MappingEntryRef.V3Source -> {
                val (subject, provider) = splitEntryKey(ref.entryKey)
                dao.deleteSourceMapping(ref.key.sourceId, ref.key.extensionId, ref.key.publisherId, ref.key.providerId,
                    subject, provider)
            }
            is MappingEntryRef.V3Legacy -> {
                val (subject, provider) = splitEntryKey(ref.entryKey)
                releaseDao.deleteExternalMapping(subject, provider)
            }
            is MappingEntryRef.R2 -> releaseDao.deleteMapping(ref.streamKey)
            is MappingEntryRef.Navigation -> Unit
        }
    }

    override suspend fun correct(id: String, revision: String, mediaId: Int): MappingMutationResult =
        withContext(Dispatchers.IO) {
            AppLog.i("matching") { "user: correct mapping to media=$mediaId" }
            if (mediaId <= 0) return@withContext MappingMutationResult.UNAVAILABLE
            val ref = MappingEntryIds.decode(id) ?: return@withContext MappingMutationResult.UNAVAILABLE
            mutation.withLock { correctLocked(ref, revision, mediaId) }
        }

    private suspend fun correctLocked(ref: MappingEntryRef, revision: String, mediaId: Int): MappingMutationResult {
        if (ref is MappingEntryRef.Navigation) {
            val old = navigation.state.value.segments.firstOrNull { MappingEntryIds.navigation(it) == ref }
                ?: return MappingMutationResult.STALE
            if (MappingEntryIds.navigationRevision(old) != revision) return MappingMutationResult.STALE
            navigation.replaceSegment(old, old.copy(mediaId = mediaId))
            return MappingMutationResult.APPLIED
        }
        val now = clock.instant()
        return try {
            database.withTransaction {
                val current = current(ref) ?: throw Abort(MappingMutationResult.STALE)
                if (current.revision != revision) throw Abort(MappingMutationResult.STALE)
                when (ref) {
                    is MappingEntryRef.V3Source -> {
                        val (subject, provider) = splitEntryKey(ref.entryKey)
                        val row = dao.sourceMapping(ref.key.sourceId, ref.key.extensionId, ref.key.publisherId,
                            ref.key.providerId, subject, provider) ?: throw Abort(MappingMutationResult.STALE)
                        dao.upsertSourceMapping(row.copy(externalId = mediaId.toString(),
                            mappingSource = MappingSource.MANUAL.name, mappingStatus = MappingStatus.ACTIVE.name,
                            confidence = MappingConfidence.EXACT.name, validatedAt = now.toString(), staleAt = null,
                            provenance = MANUAL_PROVENANCE, revision = row.revision + 1, updatedAt = now.toString()))
                    }
                    is MappingEntryRef.V3Legacy -> {
                        val (subject, provider) = splitEntryKey(ref.entryKey)
                        val row = releaseDao.getExternalMapping(subject, provider) ?: throw Abort(MappingMutationResult.STALE)
                        releaseDao.upsertExternalMapping(row.copy(externalId = mediaId.toString(),
                            mappingSource = MappingSource.MANUAL.name, mappingStatus = MappingStatus.ACTIVE.name,
                            confidence = MappingConfidence.EXACT.name, validatedAt = now.toString(), staleAt = null,
                            provenance = MANUAL_PROVENANCE))
                    }
                    is MappingEntryRef.R2 -> {
                        val row = releaseDao.getMapping(ref.streamKey) ?: throw Abort(MappingMutationResult.STALE)
                        releaseDao.upsertMappings(listOf(row.copy(mediaId = mediaId,
                            confidence = MappingConfidence.EXACT.name, score = 1.0, runnerUpMargin = null,
                            evidence = MANUAL_PROVENANCE, matcherVersion = MANUAL_MATCHER_VERSION,
                            origin = MappingOrigin.MANUAL.name, updatedAt = now.toString())))
                    }
                    is MappingEntryRef.Navigation -> Unit
                }
                ref.fence?.let { (kind, key) -> fence.bump(kind, key, now) }
                MappingMutationResult.APPLIED
            }
        } catch (abort: Abort) {
            abort.result
        }
    }

    // --- rematch ----------------------------------------------------------------------------------------------------
    override fun rematch(token: MappingActionToken): Flow<MappingRematchProgress> = flow {
        val action = dao.action(token.value)
        if (action == null || action.consumedAt != null) {
            emit(MappingRematchProgress(0, 0))
            return@flow
        }
        // The captured scope is capped at MAX_SCOPE_ENTRIES and each entry performs bounded, cancellable work.
        // Process the full confirmed scope so the UI cannot report a truncated batch as complete.
        val total = action.entryCount
        emit(MappingRematchProgress(0, total))
        val searches = SearchBudget(searchesPerRun)
        var from = 0
        var done = 0
        while (done < total) {
            val page = dao.actionEntries(token.value, from, minOf(CHUNK, total - done))
            if (page.isEmpty()) break
            for (entry in page) {
                currentCoroutineContext().ensureActive()
                val outcome = rematchOne(entry, searches)
                done++
                emit(MappingRematchProgress(done, total, entry.entryId, outcome))
            }
            from = page.last().ordinal + 1
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun rematchOne(entry: MappingActionEntryEntity, searches: SearchBudget): MappingRematchOutcome {
        val ref = MappingEntryIds.decode(entry.entryId) ?: return MappingRematchOutcome.UNAVAILABLE
        return try {
            // The epoch first, the revision second: a change in between fails one of the two checks.
            val epoch = ref.fence?.let { (kind, key) -> fence.epoch(kind, key) } ?: 0L
            val current = current(ref) ?: return MappingRematchOutcome.STALE
            if (current.revision != entry.revision) return MappingRematchOutcome.STALE
            val media = current.mediaId ?: return MappingRematchOutcome.UNAVAILABLE
            service.rematch(ref, media, epoch, searches)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MappingRematchOutcome.UNAVAILABLE
        }
    }

    // --- unbound series of the active source ---------------------------------------------------------------------------
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeUnmatched(): Flow<List<UnmatchedSeries>> = service.activeSource.flatMapLatest { active ->
        if (active == null) flowOf(emptyList()) else combine(
            dao.observeSourceMappingTrigger(), dao.observeLabelTrigger(), dao.observeLegacyV3Trigger(),
            database.reconciliationDao().observeSourceProjections(active.sourceId, active.extensionId,
                active.publisherId, active.providerId).map { it.size },
        ) { _, _, _, _ -> 0 }.mapLatest { service.unmatched(active) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun matchUnmatchedNow(): Int = withContext(Dispatchers.IO) {
        AppLog.i("matching") { "user: match unbound series now" }
        service.matchPendingNow().matched
    }

    override suspend fun searchUnmatchedNow(): Int = withContext(Dispatchers.IO) {
        AppLog.i("matching") { "user: match unbound series now with a search per title" }
        service.searchPendingNow().matched
    }

    override suspend fun assignUnmatched(series: UnmatchedSeries, mediaId: Int): MappingMutationResult =
        withContext(Dispatchers.IO) { mutation.withLock { service.assign(series, mediaId) } }

    // --- detail entry -----------------------------------------------------------------------------------------------
    override suspend fun ensureDetailMapping(mediaId: Int) =
        service.ensureForMedia(DetailMappingRequest(mediaId, emptySet()))

    override suspend fun ensureDetailMapping(request: DetailMappingRequest) = service.ensureForMedia(request)

    // --- matcher options: none of the matcher's settings is a user option, so there is nothing to configure ---------
    override fun observeMatcherOptions(): Flow<List<MatcherOption>> = flowOf(emptyList())

    override suspend fun setMatcherOption(key: String, value: String) {
        throw IllegalArgumentException("no matcher option is supported")
    }

    override suspend fun resetMatcherOptions() = Unit

    private companion object {
        const val CHUNK = 500
        const val MAX_SCOPE_ENTRIES = 20_000
        const val MAX_SEARCHES_PER_RUN = 100
        val TOKEN_TTL: Duration = Duration.ofHours(24)
        /** An accepted historical binding stays visible and manageable even after it went stale. */
        val MANAGED_STATUSES = setOf(MappingStatus.ACTIVE.name, MappingStatus.STALE.name)
        const val MANUAL_PROVENANCE = "settings-correction"
        const val MANUAL_MATCHER_VERSION = "manual-v1"
    }
}

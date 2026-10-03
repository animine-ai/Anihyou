package com.axiel7.anihyou.release.data.repository

import androidx.room.withTransaction
import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import com.axiel7.anihyou.release.core.api.IdentityCandidate
import com.axiel7.anihyou.release.core.api.IdentityCandidateSource
import com.axiel7.anihyou.release.core.api.MappingRematchOutcome
import com.axiel7.anihyou.release.core.api.TargetedIdentityQuery
import com.axiel7.anihyou.release.core.matching.MatchDecision
import com.axiel7.anihyou.release.core.matching.MatchTier
import com.axiel7.anihyou.release.core.matching.ReleaseMatchRequest
import com.axiel7.anihyou.release.core.matching.ReleaseMatcher
import com.axiel7.anihyou.release.core.matching.TitleNormalizer
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
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
    private val matcher: ReleaseMatcher = ReleaseMatcher(),
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
        val startedAt = clock.instant()
        val sourceKey = MappingEntryIds.sourceKey(active)
        if (dao.effectiveOverviewMappings(active.sourceId, active.extensionId, active.publisherId, active.providerId,
                sourceKey, request.mediaId.toString()).isNotEmpty()) return
        if (navigation.state.value.segments.any { it.key == active && it.mediaId == request.mediaId }) return
        if (request.format == "MOVIE") return

        val titles = request.titles.ifEmpty { cachedTitles(request.mediaId) }
        if (titles.isEmpty()) return
        val wanted = titles.map(TitleNormalizer::normalize)
        // A cour or part cannot be told apart by a season subject, so it is left to the explicit settings.
        if (wanted.any { it.part != null }) return
        val season = wanted.firstNotNullOfOrNull { it.season } ?: 1
        val wantedBases = wanted.map { it.base }.filter { it.isNotBlank() }.toSet()
        if (wantedBases.isEmpty()) return

        val hits = mutableListOf<SourceSeriesLabelEntity>()
        var offset = 0
        while (offset < MAX_LABELS) {
            val page = dao.labelsOf(active.sourceId, active.extensionId, active.publisherId, active.providerId, PAGE, offset)
            page.forEach { label -> if (names(label).any { TitleNormalizer.normalize(it).base in wantedBases }) hits += label }
            if (page.size < PAGE) break
            offset += PAGE
        }
        // Exactly one source series may fit; two are ambiguous and stay unmatched.
        val label = hits.singleOrNull() ?: return
        val subject = runCatching {
            AniWorldMappingSubject.Season(AniWorldSiteIdentifier(label.providerSeriesKey), season)
        }.getOrNull() ?: return
        if (alreadyAccepted(active, subject)) return

        val decision = matcher.match(
            ReleaseMatchRequest(sourceIdentity(active.providerId, label.providerSeriesKey, season), label.title,
                aliases = names(label).toSet() - label.title, season = season.takeIf { it in 1..99 }),
            listOf(IdentityCandidate(request.mediaId, titles, request.format,
                request.startYear?.let { runCatching { LocalDate.of(it, 1, 1) }.getOrNull() })),
        )
        val matched = decision as? MatchDecision.Matched ?: return
        if (matched.mediaId != request.mediaId || matched.tier !in AUTO_TIERS) return
        writeAuto(active, subject, label.providerSeriesKey, matched, startedAt)
    }

    /**
     * The explicit rematch of one captured entry. The accepted binding stays until a valid replacement exists; a
     * replacement is written only if no reset or correction happened since [startedAt].
     */
    internal suspend fun rematch(ref: MappingEntryRef, currentMediaId: Int, startedAt: Instant): MappingRematchOutcome {
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
        var decision = matcher.match(request, local)
        val localHit = decision as? MatchDecision.Matched
        if (localHit == null || localHit.tier !in AUTO_TIERS) {
            val searched = attempt {
                candidates.targetedSearch(TargetedIdentityQuery(identity, title, setOf("TV"),
                    signature = "settings-rematch|${identity.stableKey}|$title")).candidates
            }.orEmpty()
            delay(SEARCH_PACE_MS)
            decision = matcher.match(request, (local + searched).distinctBy { it.mediaId })
        }
        val matched = decision as? MatchDecision.Matched ?: return MappingRematchOutcome.RETAINED
        if (matched.tier !in AUTO_TIERS) return MappingRematchOutcome.RETAINED
        if (matched.mediaId == currentMediaId) return MappingRematchOutcome.RETAINED
        return replace(ref, matched, startedAt)
    }

    private suspend fun replace(ref: MappingEntryRef, matched: MatchDecision.Matched, startedAt: Instant): MappingRematchOutcome =
        database.withTransaction {
            val (kind, fenceKey) = ref.fence ?: return@withTransaction MappingRematchOutcome.UNAVAILABLE
            if (!fence.allows(kind, fenceKey, startedAt)) return@withTransaction MappingRematchOutcome.STALE
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

    private suspend fun writeAuto(active: ExtensionSelectionKey, subject: AniWorldMappingSubject.Season, slug: String,
                                  matched: MatchDecision.Matched, startedAt: Instant) {
        database.withTransaction {
            val entryKey = "${subject.stableKey}|${ExternalProvider.ANILIST.value}"
            if (!fence.allows(MappingEntryRef.FENCE_V3_SOURCE, "${MappingEntryIds.sourceKey(active)}|$entryKey", startedAt)) return@withTransaction
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
        /** A fuzzy hit is never accepted without the user. */
        val AUTO_TIERS = setOf(MatchTier.EXACT_NORMALIZED, MatchTier.EXACT_BASE_SEASON, MatchTier.ALIAS)
    }
}

/** An entry key is "<subject key>|<external provider>"; the subject key itself never contains the last bar. */
internal fun splitEntryKey(entryKey: String): Pair<String, String> =
    entryKey.substringBeforeLast('|') to entryKey.substringAfterLast('|')

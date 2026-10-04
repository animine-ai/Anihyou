package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.api.ReleaseUiFreshness
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.source.ExtensionPreferences
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.data.extension.FileProviderNavigationStateStore
import com.axiel7.anihyou.release.data.extension.ProviderNavigationStoredState
import com.axiel7.anihyou.release.core.source.ExtensionProductPolicyRepository
import com.axiel7.anihyou.release.core.source.ExtensionSourceRepository
import com.axiel7.anihyou.release.core.source.usableExtension
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.data.db.ExternalMappingEntity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.ReleaseReconciliationMapper
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomReleasePresentationRepository(
    private val projections: RoomReleaseProjectionRepository,
    private val database: ReleaseDatabase? = null,
    private val productPolicy: ExtensionProductPolicyRepository? = null,
    private val extensionSources: ExtensionSourceRepository? = null,
    private val navigationStore: FileProviderNavigationStateStore? = null,
    /**
     * Whether the old provider-wide lane may present anything. Null keeps it always on (tests, tools). The product passes
     * the user's old AniWorld setting: a lane nobody switched on must not decide Behind, countdowns or the calendar, so
     * without an active source AniList is the only data, as in the original app.
     */
    private val legacyLaneEnabled: Flow<Boolean>? = null,
) : ReleasePresentationRepository {
    override fun observeForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Flow<Map<Int, List<ReleaseUiPresentation>>> {
        val legacy = projections.observeForMedia(accountId, mediaIds).map { rows ->
            rows.mapValues { (_, candidates) ->
                candidates.map { it.toUiPresentation() }
            }
        }
        val selection = selection() ?: return legacy.offMain()
        // Extension First, the same selection as the calendar: an active usable source owns the release fields of
        // every entry point; without an active source the old provider-wide projection stays (when its lane is on);
        // an unusable one shows nothing.
        return combine(legacy, selection, legacyLaneEnabled ?: kotlinx.coroutines.flow.flowOf(true)) { legacyRows, chosen, legacyOn ->
            val result = when (chosen) {
                is ReleaseSelection.Legacy -> if (legacyOn) legacyRows else emptyMap()
                is ReleaseSelection.None -> emptyMap()
                is ReleaseSelection.Extension ->
                    chosen.rows.toExtensionMediaPresentations(chosen.mappings, mediaIds, chosen.preferences, chosen.source, chosen.segments)
            }
            AppLog.i("presentation") {
                "media request=${mediaIds.size} legacyMedia=${legacyRows.size} legacyLaneOn=$legacyOn mode=${chosen.javaClass.simpleName} " +
                    "presented=${result.size} authoritative=${result.values.count { list -> list.any { it.isAuthoritative } }}"
            }
            result
        }.offMain()
    }

    override fun observeCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): Flow<List<ReleaseUiCalendarItem>> {
        val legacy = projections.observeCalendar(accountId, range).map { rows ->
            rows.map { it.toUiCalendarItem() }
        }
        val selection = selection() ?: return legacy.offMain()
        return combine(legacy, selection, legacyLaneEnabled ?: kotlinx.coroutines.flow.flowOf(true)) { legacyRows, chosen, legacyOn ->
            val result = when (chosen) {
                is ReleaseSelection.Legacy -> if (legacyOn) legacyRows else emptyList()
                is ReleaseSelection.None -> emptyList()
                is ReleaseSelection.Extension ->
                    chosen.rows.toExtensionCalendarItems(chosen.mappings, range, chosen.preferences, chosen.source, chosen.segments)
            }
            AppLog.i("presentation") {
                "calendar range=$range legacy=${legacyRows.size} legacyLaneOn=$legacyOn mode=${chosen.javaClass.simpleName} items=${result.size} " +
                    "authoritative=${result.count { it.isAuthoritative }} days=${result.mapNotNull { it.sourceDate }.distinct().size}"
            }
            result
        }.offMain()
    }

    /**
     * Decoding and folding the accepted rows is CPU work that grows with the rows of the source (about 16 ms per 1,000
     * rows on a warm server JVM, see ExtensionMediaPresentationsTest). The consumers collect in their view model scope,
     * which runs on the main thread, so the work runs on the default dispatcher and only the result is handed over.
     */
    private fun <T> Flow<T>.offMain(): Flow<T> = flowOn(Dispatchers.Default)

    /** Which release data every consumer presents right now. Null when the repository has no source-bound inputs. */
    private fun selection(): Flow<ReleaseSelection>? {
        val db = database ?: return null
        val policy = productPolicy ?: return null
        // R04: extension rows are folded per exact source, so only the active source's own rows are
        // ever read; another source's accepted rows can never be presented as this source's.
        val active = policy.policy.map { it.activeReleaseSource }.distinctUntilChanged()
        val activeRows = active.flatMapLatest { selected ->
            if (selected == null) kotlinx.coroutines.flow.flowOf(emptyList<com.axiel7.anihyou.release.data.db.SourceReleaseProjectionEntity>()) else
                db.reconciliationDao().observeSourceProjections(
                    selected.sourceId, selected.extensionId, selected.publisherId, selected.providerId)
        }
        // The bindings in force for exactly this source: its own accepted rows plus the old provider-wide rows
        // of unknown origin that it has not reset or corrected itself (R04 / matching management).
        val mappings = active.flatMapLatest { selected ->
            if (selected == null) kotlinx.coroutines.flow.flowOf(emptyList()) else
                db.matchingDao().observeEffectiveAniListMappings(selected.sourceId, selected.extensionId,
                    selected.publisherId, selected.providerId, MappingEntryIds.sourceKey(selected))
        }
        return combine(
            activeRows,
            mappings,
            policy.policy,
            extensionSources?.sources ?: kotlinx.coroutines.flow.flowOf(emptyList()),
            navigationStore?.state ?: kotlinx.coroutines.flow.flowOf(ProviderNavigationStoredState()),
        ) { sourceRows, bindings, product, catalog, navigation ->
            when (product.activeReleaseSource?.providerId) {
                "aniworld" -> {
                    val selected = requireNotNull(product.activeReleaseSource)
                    if (catalog.usableExtension(selected) != null) {
                        AppLog.d("selection") {
                            "mode=EXTENSION ext=${selected.extensionId} source=${selected.sourceId.take(8)} rows=${sourceRows.size} mappings=${bindings.size} " +
                                "tracks=${product.preferencesFor(selected).enabledTracks.sorted()}"
                        }
                        ReleaseSelection.Extension(
                            rows = sourceRows.filter {
                                it.sourceId == selected.sourceId && it.extensionId == selected.extensionId &&
                                    it.publisherId == selected.publisherId && it.providerId == selected.providerId
                            }.map { it.asCanonical() },
                            mappings = bindings,
                            preferences = product.preferencesFor(selected),
                            source = selected, segments = navigation.segments,
                        )
                    } else {
                        AppLog.w("selection") { "mode=NONE: active source ${selected.extensionId} is not usable (not installed, revoked or disabled)" }
                        ReleaseSelection.None
                    }
                }
                null -> {
                    AppLog.d("selection") { "mode=LEGACY (no active extension source) -> AniList / old provider lane" }
                    ReleaseSelection.Legacy
                }
                else -> {
                    AppLog.w("selection") { "mode=NONE: unsupported provider ${product.activeReleaseSource?.providerId}" }
                    ReleaseSelection.None
                }
            }
        }
    }
}

private sealed interface ReleaseSelection {
    data object Legacy : ReleaseSelection
    data object None : ReleaseSelection
    class Extension(
        val rows: List<com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity>,
        val mappings: List<ExternalMappingEntity>,
        val preferences: ExtensionPreferences,
        val source: ExtensionSelectionKey,
        val segments: List<ProviderEpisodeSegment>,
    ) : ReleaseSelection
}

internal fun List<com.axiel7.anihyou.release.data.db.CanonicalReleaseProjectionEntity>.toExtensionCalendarItems(
    mappings: List<ExternalMappingEntity>,
    range: ClosedRange<LocalDate>,
    preferences: ExtensionPreferences = ExtensionPreferences(),
    source: ExtensionSelectionKey? = null,
    segments: List<ProviderEpisodeSegment> = emptyList(),
): List<ReleaseUiCalendarItem> {
    val lookup = MappingLookup(mappings)
    // The same track switches as the per-media fold: a language track the user turned off is not presented by any entry
    // point (the calendar also feeds the widget and the explore airing rows).
    val enabledTracks = preferences.enabledTracks - "UNKNOWN"
    return mapNotNull { row ->
        val state = runCatching { ReleaseReconciliationMapper.state(row) }.getOrNull()
            ?: return@mapNotNull null
        val identity = CanonicalReleaseIdentity.decode(row.projectionKey)
            ?: return@mapNotNull null
        if (identity.track.name !in enabledTracks) return@mapNotNull null
        if (state.underlyingPhase !in setOf(
                ReleasePhase.PREDICTED,
                ReleasePhase.EXPECTED,
                ReleasePhase.CONFIRMED,
                ReleasePhase.RELEASED,
            )
        ) return@mapNotNull null

        val confirmed = state.underlyingPhase == ReleasePhase.RELEASED ||
            state.underlyingPhase == ReleasePhase.CONFIRMED
        val presentationAt = if (confirmed) {
            state.releaseAt ?: state.forecastAt
        } else {
            state.forecastAt
        } ?: return@mapNotNull null
        val sourceDate = presentationAt.atZone(ReleaseSourceTimePolicy.ANI_WORLD_ZONE).toLocalDate()
        if (sourceDate < range.start || sourceDate > range.endInclusive) return@mapNotNull null

        val releaseKind = when (identity.installment) {
            is Installment.Episode -> ReleaseKind.EPISODE
            is Installment.Film -> ReleaseKind.MOVIE
            is Installment.Special -> return@mapNotNull null
        }
        val mediaId = lookup.aniListId(identity, state.navigationSeasons)
        val installment = canonicalPresentationInstallment(source, mediaId, identity.seriesPath,
            identity.sourceSeason, identity.installment, segments) ?: return@mapNotNull null
        ReleaseUiCalendarItem(
            mediaId = mediaId,
            stream = ReleaseStreamKey(
                providerId = ProviderId("aniworld"),
                stableSeriesKey = SourceSeriesKey(identity.seriesPath),
                releaseKind = releaseKind,
                sourceSeason = identity.sourceSeason,
                languageTrack = identity.track,
            ),
            installment = installment,
            forecastAt = presentationAt,
            confirmed = confirmed,
            // The effective phase already encodes the monotonic release contract (ReleaseConflictPolicy.effectivePhase): an
            // open conflict makes a planned row CONFLICT, a released episode stays RELEASED. The per-media fold reads the same
            // state, so one released episode has one authority in every entry point.
            authority = if (state.phase == ReleasePhase.CONFLICT) {
                ReleaseUiAuthority.AMBIGUOUS
            } else {
                ReleaseUiAuthority.VALID
            },
            sourceDate = sourceDate,
            // No consumer reads a source URL, and the extension path does not build provider URLs in the host.
            sourceRoot = null,
            revision = state.revision,
        )
    }
        .distinctBy { it.eventKey }
        .sortedWith(
            compareBy<ReleaseUiCalendarItem> { it.sourceDate }
                .thenBy { it.forecastAt ?: Instant.MAX }
                .thenBy { it.stream.stableKey }
                .thenBy { it.installment.stableKey },
        )
}

/**
 * The bindings of one emission, indexed by site slug. A row looks at the few bindings of its own series instead of at
 * every binding the source has, which keeps a library with thousands of bindings from costing rows times bindings
 * comparisons on every database change (measured in ExtensionMediaPresentationsTest.bindingLookupAgreesWithTheLinearScanAndItsCostIsMeasuredAtScale).
 */
internal class MappingLookup(mappings: List<ExternalMappingEntity>) {
    private val bySlug: Map<String, List<ExternalMappingEntity>> = mappings.groupBy { it.siteSlug }

    /** The AniList media this row is bound to, or null when no binding or more than one media matches. */
    fun aniListId(identity: CanonicalReleaseIdentity, navigationSeasons: Set<Int>): Int? {
        val candidates = bySlug[identity.seriesPath.removePrefix("/anime/stream/")].orEmpty().filter { row ->
            when (val installment = identity.installment) {
                is Installment.Episode ->
                    row.subjectType == "SEASON" &&
                        row.navigationSeason != null &&
                        row.navigationSeason in navigationSeasons
                is Installment.Film ->
                    row.subjectType == "FILM" && row.filmNumber == installment.number
                is Installment.Special -> false
            }
        }
        return candidates.mapNotNull { it.externalId?.toIntOrNull()?.takeIf { id -> id > 0 } }
            .distinct()
            .singleOrNull()
    }
}

private fun MediaReleaseProjection.toUiPresentation(): ReleaseUiPresentation =
    ReleaseUiPresentation(
        mediaId = mediaId,
        stream = stream,
        authority = authority.toUiAuthority(),
        confirmedThroughEpisode = confirmedThroughEpisode,
        confirmedInstallments = confirmedInstallments,
        confirmedPending = pendingCount,
        nextExpectedInstallment = nextForecast?.identity?.installment,
        nextForecast = nextForecast,
        freshness = freshness.status.toUiFreshness(),
        sourceRoot = sourceRoot,
        revision = revision,
    )

private fun CalendarReleaseProjection.toUiCalendarItem(): ReleaseUiCalendarItem =
    ReleaseUiCalendarItem(
        mediaId = mediaId,
        stream = stream,
        installment = installment,
        forecastAt = forecastAt,
        confirmed = confirmed,
        authority = authority.toUiAuthority(),
        sourceDate = sourceDate,
        sourceRoot = null,
        revision = revision,
    )

private fun AuthorityStatus.toUiAuthority(): ReleaseUiAuthority = when (this) {
    AuthorityStatus.VALID -> ReleaseUiAuthority.VALID
    AuthorityStatus.AMBIGUOUS -> ReleaseUiAuthority.AMBIGUOUS
    AuthorityStatus.UNMAPPED -> ReleaseUiAuthority.UNMAPPED
    AuthorityStatus.STALE -> ReleaseUiAuthority.STALE
    AuthorityStatus.ERROR -> ReleaseUiAuthority.ERROR
    AuthorityStatus.DISABLED -> ReleaseUiAuthority.DISABLED
}

private fun FreshnessStatus.toUiFreshness(): ReleaseUiFreshness = when (this) {
    FreshnessStatus.UNKNOWN -> ReleaseUiFreshness.UNKNOWN
    FreshnessStatus.FRESH -> ReleaseUiFreshness.FRESH
    FreshnessStatus.STALE -> ReleaseUiFreshness.STALE
    FreshnessStatus.ERROR -> ReleaseUiFreshness.ERROR
    FreshnessStatus.DISABLED -> ReleaseUiFreshness.DISABLED
}

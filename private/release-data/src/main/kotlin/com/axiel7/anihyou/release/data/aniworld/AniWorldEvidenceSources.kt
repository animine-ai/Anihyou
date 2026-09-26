package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.ReleaseEvidenceSource
import com.axiel7.anihyou.release.core.api.SourceFailureKind
import com.axiel7.anihyou.release.core.api.SourceResult
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException

/** Calendar source. It can produce forecasts only. */
class AniWorldCalendarEvidenceAdapter(
    private val client: AniWorldClient,
    private val parser: AniWorldEvidenceParser = AniWorldEvidenceParser(),
    private val sourceRoot: String = DEFAULT_ANIWORLD_SOURCE_ROOT,
    private val clock: Clock = Clock.systemUTC(),
) : AbstractAniWorldEvidenceSource(client, parser, clock) {
    override val sourceType: ReleaseSourceType = ReleaseSourceType.ANIWORLD_CALENDAR

    override suspend fun collect(): SourceResult<List<ReleaseEvidence>> = collectPage(
        role = AniWorldPageRole.FUTURE_CALENDAR,
        url = resolve(sourceRoot, "animekalender"),
        evidenceType = ReleaseEvidenceType.FORECAST,
    )
}

/** Neue Episoden is the primary AniWorld confirmation source. */
class AniWorldRecentEpisodeEvidenceAdapter(
    private val client: AniWorldClient,
    private val parser: AniWorldEvidenceParser = AniWorldEvidenceParser(),
    private val sourceRoot: String = DEFAULT_ANIWORLD_SOURCE_ROOT,
    private val clock: Clock = Clock.systemUTC(),
) : AbstractAniWorldEvidenceSource(client, parser, clock) {
    override val sourceType: ReleaseSourceType = ReleaseSourceType.ANIWORLD_RECENT

    override suspend fun collect(): SourceResult<List<ReleaseEvidence>> = collectPage(
        role = AniWorldPageRole.RECENT_CURRENT,
        url = resolve(sourceRoot, "neue-episoden"),
        evidenceType = ReleaseEvidenceType.CONFIRMATION,
    )
}

/**
 * Verschobene Episoden is correction evidence. The parser emits one record
 * per positively identified language track and never fills a missing track.
 */
class AniWorldPostponementEvidenceAdapter(
    private val client: AniWorldClient,
    private val parser: AniWorldEvidenceParser = AniWorldEvidenceParser(),
    private val sourceRoot: String = DEFAULT_ANIWORLD_SOURCE_ROOT,
    private val postponementPath: String = DEFAULT_POSTPONEMENT_PATH,
    private val clock: Clock = Clock.systemUTC(),
) : AbstractAniWorldEvidenceSource(client, parser, clock) {
    override val sourceType: ReleaseSourceType = ReleaseSourceType.ANIWORLD_POSTPONEMENT

    override suspend fun collect(): SourceResult<List<ReleaseEvidence>> = collectPage(
        role = AniWorldPageRole.POSTPONEMENT,
        url = resolve(sourceRoot, postponementPath.trimStart('/')),
        evidenceType = ReleaseEvidenceType.CORRECTION,
    )

    companion object {
        const val DEFAULT_POSTPONEMENT_PATH = "/verschobene-episoden"
    }
}

/** Direct episode/stream page verification source. */
class AniWorldDirectVerificationEvidenceAdapter(
    private val client: AniWorldClient,
    private val episodeUrl: String,
    private val parser: AniWorldEvidenceParser = AniWorldEvidenceParser(),
    private val clock: Clock = Clock.systemUTC(),
) : AbstractAniWorldEvidenceSource(client, parser, clock) {
    override val sourceType: ReleaseSourceType = ReleaseSourceType.ANIWORLD_DIRECT_PAGE

    override suspend fun collect(): SourceResult<List<ReleaseEvidence>> = collectPage(
        role = AniWorldPageRole.DIRECT_EPISODE,
        url = episodeUrl,
        evidenceType = ReleaseEvidenceType.VERIFICATION,
    )
}

/**
 * Source collection is deliberately independent. A failed adapter contributes
 * a typed result but never removes evidence collected by another adapter.
 */
class AniWorldEvidenceIngestionCoordinator(
    private val sources: List<ReleaseEvidenceSource>,
) {
    suspend fun collect(): AniWorldEvidenceCollection {
        val results = sources.map { source ->
            try {
                source.collect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                SourceResult.Failure(
                    kind = SourceFailureKind.UNKNOWN,
                    diagnostic = "source " + source.sourceType.name + " failed closed: " +
                        (exception::class.simpleName ?: "source-error"),
                )
            }
        }
        return AniWorldEvidenceCollection(
            results = results,
            evidence = results.flatMap { result ->
                when (result) {
                    is SourceResult.Success -> result.value
                    is SourceResult.PartialSuccess -> result.value
                    is SourceResult.Failure -> emptyList()
                }
            },
        )
    }
}

data class AniWorldEvidenceCollection(
    val results: List<SourceResult<List<ReleaseEvidence>>>,
    val evidence: List<ReleaseEvidence>,
)

abstract class AbstractAniWorldEvidenceSource(
    private val client: AniWorldClient,
    private val parser: AniWorldEvidenceParser,
    private val clock: Clock,
    private val identityResolver: AniWorldIdentityResolver = AniWorldIdentityResolver(clock),
) : ReleaseEvidenceSource {
    abstract override val sourceType: ReleaseSourceType

    protected suspend fun collectPage(
        role: AniWorldPageRole,
        url: String,
        evidenceType: ReleaseEvidenceType,
    ): SourceResult<List<ReleaseEvidence>> {
        val observedAt = clock.instant()
        return try {
            when (val fetched = client.fetch(AniWorldPageRequest(role = role, url = url))) {
                is AniWorldClientResult.Failure -> fetched.toSourceFailure(observedAt)
                is AniWorldClientResult.Success -> {
                    val response = fetched.response
                    when (
                        val parsed = parser.parse(
                            html = response.body,
                            sourceUrl = response.finalUrl,
                            role = role,
                            observedAt = observedAt,
                        )
                    ) {
                        is AniWorldEvidenceParseResult.Failure -> parsed.toSourceFailure(
                            sourceType = sourceType,
                            observedAt = observedAt,
                        )
                        is AniWorldEvidenceParseResult.Success -> {
                            val evidence = parsed.observations.mapNotNull { observation ->
                                observation.toEvidence(
                                    sourceType = sourceType,
                                    sourceUrl = response.finalUrl,
                                    sourceHash = parsed.sourceHash,
                                    parserVersion = parser.parserVersion,
                                    evidenceType = evidenceType,
                                    observedAt = observedAt,
                                )
                            }
                            val warnings = parsed.warnings + if (
                                evidence.size < parsed.observations.size
                            ) {
                                listOf("one or more observations failed site identity validation")
                            } else {
                                emptyList()
                            }
                            val health = SourceHealth(
                                sourceType = sourceType,
                                status = if (warnings.isEmpty()) {
                                    SourceHealthStatus.HEALTHY
                                } else {
                                    SourceHealthStatus.DEGRADED
                                },
                                lastAttemptAt = observedAt,
                                lastSuccessAt = observedAt,
                                parserVersion = parser.parserVersion,
                                sourceHash = parsed.sourceHash,
                                diagnostic = warnings.joinToString("; ").take(256).ifBlank { null },
                            )
                            if (warnings.isEmpty()) {
                                SourceResult.Success(evidence, health)
                            } else {
                                SourceResult.PartialSuccess(
                                    value = evidence,
                                    diagnostic = warnings.distinct().joinToString("; ").take(256),
                                    sourceHealth = health,
                                )
                            }
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            SourceResult.Failure(
                kind = SourceFailureKind.UNKNOWN,
                diagnostic = "source collection failed closed: " +
                    (exception::class.simpleName ?: "source-error"),
                sourceHealth = SourceHealth(
                    sourceType = sourceType,
                    status = SourceHealthStatus.UNAVAILABLE,
                    lastAttemptAt = observedAt,
                    lastSuccessAt = null,
                    consecutiveFailures = 1,
                    parserVersion = parser.parserVersion,
                ),
            )
        }
    }

    private fun AniWorldNormalizedObservation.toEvidence(
        sourceType: ReleaseSourceType,
        sourceUrl: String,
        sourceHash: String,
        parserVersion: String,
        evidenceType: ReleaseEvidenceType,
        observedAt: Instant,
    ): ReleaseEvidence? {
        val siteIdentifier = when (
            val resolved = identityResolver.resolve(
                sourceUrl = sourceRoot,
                sourceType = sourceType.toIdentitySourceType(),
                observedAt = observedAt,
                sourceHash = sourceHash,
                parserVersion = parserVersion,
            )
        ) {
            is AniWorldIdentityResolution.Success -> resolved.series
            is AniWorldIdentityResolution.Failure -> return null
        }
        val sourceReportedAt = when (sourceType) {
            ReleaseSourceType.ANIWORLD_CALENDAR -> forecastAt
            else -> this.sourceReportedAt
        }
        val identityKey = listOf(
            siteIdentifier.stableKey,
            stream.sourceSeason?.toString() ?: "source-season:unknown",
            navigationSeason?.toString() ?: "navigation-season:unknown",
            installment.stableKey,
            track.name,
        ).joinToString("/")

        val evidenceId = AniWorldEvidenceId.create(
            sourceType = sourceType,
            sourceHash = sourceHash,
            identityKey = identityKey,
            evidenceType = evidenceType,
            sourceReportedAt = sourceReportedAt,
            approximateTime = approximate,
            scheduleCondition = scheduleCondition,
        )
        val timingConfidence = when {
            sourceType == ReleaseSourceType.ANIWORLD_POSTPONEMENT -> 0.8
            sourceReportedAt != null || forecastAt != null -> 1.0
            else -> 0.75
        }
        return ReleaseEvidence(
            id = evidenceId,
            sourceType = sourceType,
            sourceUrl = sourceUrl,
            sourceHash = sourceHash,
            parserVersion = parserVersion,
            observedAt = observedAt,
            sourceReportedAt = sourceReportedAt,
            approximateTime = approximate,
            siteIdentifier = siteIdentifier,
            sourceSeason = stream.sourceSeason,
            navigationSeason = navigationSeason,
            installment = installment,
            languageTrack = track,
            evidenceType = evidenceType,
            scheduleCondition = scheduleCondition,
            confidence = ConfidenceVector(
                source = 1.0,
                identity = 1.0,
                installment = 1.0,
                languageTrack = 1.0,
                timing = timingConfidence,
            ),
        )
    }

    private fun ReleaseSourceType.toIdentitySourceType(): AniWorldIdentitySourceType = when (this) {
        ReleaseSourceType.ANIWORLD_CALENDAR -> AniWorldIdentitySourceType.CALENDAR
        ReleaseSourceType.ANIWORLD_RECENT -> AniWorldIdentitySourceType.RECENT
        ReleaseSourceType.ANIWORLD_POSTPONEMENT -> AniWorldIdentitySourceType.POSTPONEMENT
        ReleaseSourceType.ANIWORLD_DIRECT_PAGE -> AniWorldIdentitySourceType.DIRECT_PAGE
        else -> AniWorldIdentitySourceType.PAGE
    }

    private fun AniWorldEvidenceParseResult.Failure.toSourceFailure(
        sourceType: ReleaseSourceType,
        observedAt: Instant,
    ): SourceResult.Failure = SourceResult.Failure(
        kind = kind.toSourceFailureKind(),
        diagnostic = diagnostic.take(256),
        sourceHealth = SourceHealth(
            sourceType = sourceType,
            status = kind.toHealthStatus(),
            lastAttemptAt = observedAt,
            lastSuccessAt = null,
            consecutiveFailures = 1,
            parserVersion = parser.parserVersion,
            sourceHash = sourceHash,
            diagnostic = diagnostic.take(256),
        ),
    )

    private fun AniWorldClientResult.Failure.toSourceFailure(
        observedAt: Instant,
    ): SourceResult.Failure = SourceResult.Failure(
        kind = kind.toSourceFailureKind(),
        diagnostic = diagnostic.take(256),
        sourceHealth = SourceHealth(
            sourceType = sourceType,
            status = kind.toHealthStatus(),
            lastAttemptAt = observedAt,
            lastSuccessAt = null,
            consecutiveFailures = 1,
            parserVersion = parser.parserVersion,
            diagnostic = diagnostic.take(256),
        ),
    )

    private fun AniWorldFailureKind.toSourceFailureKind(): SourceFailureKind = when (this) {
        AniWorldFailureKind.BLOCKED_PAGE -> SourceFailureKind.BLOCKED
        AniWorldFailureKind.HTTP_STATUS,
        AniWorldFailureKind.TRANSPORT_FAILURE,
        AniWorldFailureKind.NON_HTML_CONTENT,
        AniWorldFailureKind.OVERSIZED_BODY,
        AniWorldFailureKind.REDIRECT_HOST,
        -> SourceFailureKind.NETWORK
        AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
        AniWorldFailureKind.MISSING_PAGE_ANCHOR,
        AniWorldFailureKind.CHANGED_SEMANTIC_ANCHOR,
        AniWorldFailureKind.INVALID_DATE_TIME,
        AniWorldFailureKind.AMBIGUOUS_LOCAL_TIME,
        AniWorldFailureKind.DUPLICATE_IDENTITY_CONFLICT,
        AniWorldFailureKind.INVALID_CARD,
        AniWorldFailureKind.EXCESSIVE_INVALID_CARDS,
        AniWorldFailureKind.TOO_MANY_CARDS,
        AniWorldFailureKind.TOO_MANY_DAYS,
        AniWorldFailureKind.EMPTY_STRUCTURE,
        AniWorldFailureKind.UNSUPPORTED_ROLE,
        -> SourceFailureKind.PARSE
    }

    private fun AniWorldFailureKind.toHealthStatus(): SourceHealthStatus = when (this) {
        AniWorldFailureKind.BLOCKED_PAGE -> SourceHealthStatus.BLOCKED
        AniWorldFailureKind.HTTP_STATUS,
        AniWorldFailureKind.TRANSPORT_FAILURE,
        AniWorldFailureKind.NON_HTML_CONTENT,
        AniWorldFailureKind.OVERSIZED_BODY,
        AniWorldFailureKind.REDIRECT_HOST,
        AniWorldFailureKind.EMPTY_STRUCTURE,
        -> SourceHealthStatus.UNAVAILABLE
        else -> SourceHealthStatus.DEGRADED
    }

    protected fun resolve(root: String, path: String): String = runCatching {
        URI(root).resolve("/" + path.trimStart('/')).toString()
    }.getOrDefault(root.trimEnd('/') + "/" + path.trimStart('/'))
}

/**
 * Versioned, deterministic identifier for append-only AniWorld evidence.
 *
 * The fingerprint deliberately includes the complete release identity and the
 * complete source snapshot hash. Poll time is excluded so an unchanged source
 * snapshot deduplicates while SourceHealth records polling attempts.
 */
internal object AniWorldEvidenceId {
    fun create(
        sourceType: ReleaseSourceType,
        sourceHash: String,
        identityKey: String,
        evidenceType: ReleaseEvidenceType,
        sourceReportedAt: Instant?,
        approximateTime: Boolean,
        scheduleCondition: com.axiel7.anihyou.release.core.model.ScheduleCondition,
    ): String = ReleaseEvidenceFingerprintV2.evidenceId(
        sourceType = sourceType,
        sourceHash = sourceHash,
        identityKey = identityKey,
        evidenceType = evidenceType,
        sourceReportedAt = sourceReportedAt,
        approximateTime = approximateTime,
        scheduleCondition = scheduleCondition,
    )
}

private const val DEFAULT_ANIWORLD_SOURCE_ROOT = "https://aniworld.to"

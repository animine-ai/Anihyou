package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.FailureKind
import com.axiel7.anihyou.release.core.api.ProviderFetchRequest
import com.axiel7.anihyou.release.core.api.ProviderFetchResult
import com.axiel7.anihyou.release.core.api.ReleaseProvider
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class AniWorldProvider(
    private val client: AniWorldClient,
    private val parser: AniWorldParser = AniWorldParser(),
    private val sourceRoot: String = DEFAULT_SOURCE_ROOT,
    private val clock: Clock = Clock.systemUTC(),
    private val sourceZone: java.time.ZoneId = java.time.ZoneId.of("Europe/Berlin"),
) : ReleaseProvider {
    override val id: ProviderId = ProviderId(AniWorldParser.PROVIDER_ID)

    override suspend fun fetch(request: ProviderFetchRequest): ProviderFetchResult {
        require(request.range.start <= request.range.endInclusive) { "provider range must be ordered" }
        val observedAt = clock.instant()

        val recentResponse = when (val result = client.fetchRecent(sourceRoot)) {
            is AniWorldClientResult.Success -> result.response
            is AniWorldClientResult.Failure -> return result.toProviderFailure()
        }
        val recentPage = when (
            val result = parser.parse(
                response = recentResponse,
                role = AniWorldPageRole.RECENT_CURRENT,
                observedAt = observedAt,
            )
        ) {
            is AniWorldParseResult.Success -> result.page
            is AniWorldParseResult.Failure -> return result.toProviderFailure()
        }

        val calendarResponse = when (val result = client.fetchCalendar(sourceRoot)) {
            is AniWorldClientResult.Success -> result.response
            is AniWorldClientResult.Failure -> return result.toProviderFailure()
        }
        val calendarPage = when (
            val result = parser.parse(
                response = calendarResponse,
                role = AniWorldPageRole.FUTURE_CALENDAR,
                observedAt = observedAt,
            )
        ) {
            is AniWorldParseResult.Success -> result.page
            is AniWorldParseResult.Failure -> return result.toProviderFailure()
        }

        val requestedTracks = request.tracks
        val snapshots = (recentPage.snapshots + calendarPage.snapshots)
            .flatMap { snapshot ->
                if (requestedTracks.isEmpty() || snapshot.stream.languageTrack in requestedTracks) {
                    listOf(snapshot)
                } else {
                    emptyList()
                }
            }
            .map { snapshot ->
                snapshot.copy(
                    forecasts = snapshot.forecasts.filter { forecast ->
                        ReleaseSourceTimePolicy.effectiveDate(
                            sourceDate = forecast.sourceDate,
                            forecastAt = forecast.forecastAt,
                            forecastZone = forecast.sourceZone,
                            fallbackZone = sourceZone,
                        ) in request.range
                    },
                )
            }
            .groupBy { it.stream }
            .map { (stream, rows) -> merge(stream, rows, observedAt) }
            .filter { snapshot ->
                snapshot.confirmations.isNotEmpty() || snapshot.forecasts.isNotEmpty()
            }
            .sortedBy { it.stream.stableKey }

        return ProviderFetchResult.Success(snapshots)
    }

    private fun merge(
        stream: ReleaseStreamKey,
        rows: List<ReleaseSnapshot>,
        observedAt: Instant,
    ): ReleaseSnapshot {
        val confirmations = rows.flatMap { it.confirmations }
            .distinctBy { Triple(it.identity.stableKey, it.evidenceKind, it.confirmedObservedAt) }
            .sortedBy { it.confirmedObservedAt }
        val forecasts = rows.flatMap { it.forecasts }
            .distinctBy { Triple(it.identity.stableKey, it.forecastAt, it.observedAt) }
            .sortedBy { it.forecastAt }
        val freshness = Freshness(
            status = FreshnessStatus.FRESH,
            lastAttemptAt = observedAt,
            lastSuccessAt = observedAt,
            observedAt = observedAt,
            parserVersion = PARSER_VERSION,
            sourceHash = digest(rows.joinToString("|") { it.freshness.sourceHash.orEmpty() }),
            diagnostic = "recent/current plus future-calendar normalized",
        )
        return ReleaseSnapshot(
            stream = stream,
            confirmations = confirmations,
            forecasts = forecasts,
            freshness = freshness,
            mapping = null,
            sourcePresent = true,
            sourceRoot = rows.asSequence().mapNotNull { it.sourceRoot }.firstOrNull(),
            observedAt = observedAt,
        )
    }

    private fun AniWorldClientResult.Failure.toProviderFailure(): ProviderFetchResult.Failure =
        ProviderFetchResult.Failure(
            kind = when (kind) {
                AniWorldFailureKind.BLOCKED_PAGE -> FailureKind.BLOCKED
                AniWorldFailureKind.HTTP_STATUS,
                AniWorldFailureKind.NON_HTML_CONTENT,
                AniWorldFailureKind.OVERSIZED_BODY,
                AniWorldFailureKind.REDIRECT_HOST -> FailureKind.NETWORK
                AniWorldFailureKind.MISSING_PAGE_ANCHOR,
                AniWorldFailureKind.CHANGED_SEMANTIC_ANCHOR,
                AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
                AniWorldFailureKind.EMPTY_STRUCTURE -> FailureKind.STRUCTURE
                AniWorldFailureKind.INVALID_DATE_TIME,
                AniWorldFailureKind.AMBIGUOUS_LOCAL_TIME,
                AniWorldFailureKind.DUPLICATE_IDENTITY_CONFLICT,
                AniWorldFailureKind.INVALID_CARD,
                AniWorldFailureKind.EXCESSIVE_INVALID_CARDS,
                AniWorldFailureKind.TOO_MANY_CARDS,
                AniWorldFailureKind.TOO_MANY_DAYS,
                AniWorldFailureKind.UNSUPPORTED_ROLE -> FailureKind.PARSE
            },
            diagnostic = diagnostic,
        )

    private fun AniWorldParseResult.Failure.toProviderFailure(): ProviderFetchResult.Failure =
        ProviderFetchResult.Failure(
            kind = when (kind) {
                AniWorldFailureKind.BLOCKED_PAGE -> FailureKind.BLOCKED
                AniWorldFailureKind.MISSING_PAGE_ANCHOR,
                AniWorldFailureKind.CHANGED_SEMANTIC_ANCHOR,
                AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
                AniWorldFailureKind.EMPTY_STRUCTURE,
                AniWorldFailureKind.TOO_MANY_CARDS,
                AniWorldFailureKind.TOO_MANY_DAYS -> FailureKind.STRUCTURE
                AniWorldFailureKind.INVALID_DATE_TIME,
                AniWorldFailureKind.AMBIGUOUS_LOCAL_TIME,
                AniWorldFailureKind.DUPLICATE_IDENTITY_CONFLICT,
                AniWorldFailureKind.INVALID_CARD,
                AniWorldFailureKind.EXCESSIVE_INVALID_CARDS,
                AniWorldFailureKind.UNSUPPORTED_ROLE -> FailureKind.PARSE
                AniWorldFailureKind.HTTP_STATUS,
                AniWorldFailureKind.NON_HTML_CONTENT,
                AniWorldFailureKind.OVERSIZED_BODY,
                AniWorldFailureKind.REDIRECT_HOST -> FailureKind.NETWORK
            },
            diagnostic = diagnostic,
        )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val DEFAULT_SOURCE_ROOT = "https://aniworld.to"
        const val PARSER_VERSION = AniWorldParser.DEFAULT_PARSER_VERSION
    }
}

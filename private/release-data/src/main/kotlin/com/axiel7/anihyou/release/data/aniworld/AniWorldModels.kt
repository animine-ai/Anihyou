package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AniWorldPageRole {
    RECENT_CURRENT,
    FUTURE_CALENDAR,
    POSTPONEMENT,
    DIRECT_EPISODE,
    SUPPORT_EXPLANATION,
}

enum class AniWorldFailureKind {
    BLOCKED_PAGE,
    MISSING_PAGE_ANCHOR,
    CHANGED_SEMANTIC_ANCHOR,
    UNKNOWN_LANGUAGE_MARKER,
    HTTP_STATUS,
    TRANSPORT_FAILURE,
    NON_HTML_CONTENT,
    OVERSIZED_BODY,
    REDIRECT_HOST,
    INVALID_DATE_TIME,
    AMBIGUOUS_LOCAL_TIME,
    DUPLICATE_IDENTITY_CONFLICT,
    INVALID_CARD,
    EXCESSIVE_INVALID_CARDS,
    TOO_MANY_CARDS,
    TOO_MANY_DAYS,
    EMPTY_STRUCTURE,
    UNSUPPORTED_ROLE,
}

data class AniWorldLimits(
    val maxBodyBytes: Int = 2_000_000,
    val maxCards: Int = 512,
    val maxCalendarDays: Int = 31,
    val maxUnknownTokens: Int = 32,
    val maxUnknownTokenValueLength: Int = 128,
    val maxInvalidCardRatio: Double = 0.50,
) {
    init {
        require(maxBodyBytes > 0)
        require(maxCards > 0)
        require(maxCalendarDays > 0)
        require(maxUnknownTokens >= 0)
        require(maxUnknownTokenValueLength > 0)
        require(maxInvalidCardRatio in 0.0..1.0)
    }
}

data class AniWorldTransportRequest(
    val url: String,
    val maxBytes: Int,
    val timeoutMillis: Long,
)

data class AniWorldHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val finalUrl: String,
    val body: String,
)

fun interface AniWorldHttpTransport {
    suspend fun fetch(request: AniWorldTransportRequest): AniWorldHttpResponse
}

data class AniWorldPageRequest(
    val role: AniWorldPageRole,
    val url: String,
)

data class AniWorldRawToken(
    val key: String,
    val value: String,
)

data class AniWorldPageDiagnostics(
    val role: AniWorldPageRole,
    val parserVersion: String,
    val sourceHash: String,
    val bodyBytes: Int,
    val cardCount: Int,
    val validCardCount: Int,
    val invalidCardCount: Int,
    val recognizedTrackCount: Int,
    val unknownFlagCount: Int,
    val boundedUnknownTokenCount: Int,
    val sourceRoot: String,
)

data class AniWorldNormalizedObservation(
    val stream: ReleaseStreamKey,
    val installment: Installment,
    val track: LanguageTrack,
    val confirmed: Boolean,
    val forecastAt: Instant?,
    val sourceDate: LocalDate?,
    val sourceTime: String?,
    val sourceZone: ZoneId?,
    val approximate: Boolean,
    val sourceRoot: String,
    val rawTokens: List<AniWorldRawToken>,
    val navigationSeason: Int? = null,
    val sourceReportedAt: Instant? = null,
    val scheduleCondition: ScheduleCondition = ScheduleCondition.UNKNOWN,
)

data class AniWorldParsedPage(
    val role: AniWorldPageRole,
    val observations: List<AniWorldNormalizedObservation>,
    val snapshots: List<ReleaseSnapshot>,
    val diagnostics: AniWorldPageDiagnostics,
)

sealed interface AniWorldParseResult {
    data class Success(
        val page: AniWorldParsedPage,
    ) : AniWorldParseResult

    data class Failure(
        val kind: AniWorldFailureKind,
        val diagnostic: String,
        val diagnostics: AniWorldPageDiagnostics? = null,
    ) : AniWorldParseResult
}

data class AniWorldPageInspection(
    val role: AniWorldPageRole,
    val pageAnchor: String,
    val flagTracksInDocumentOrder: List<LanguageTrack>,
    val unknownFlagCount: Int,
    val sourceLinks: List<SourceSeriesKey>,
)

sealed interface AniWorldInspectionResult {
    data class Success(
        val inspection: AniWorldPageInspection,
    ) : AniWorldInspectionResult

    data class Failure(
        val kind: AniWorldFailureKind,
        val diagnostic: String,
    ) : AniWorldInspectionResult
}

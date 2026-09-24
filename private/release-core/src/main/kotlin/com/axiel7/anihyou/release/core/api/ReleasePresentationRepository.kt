package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

enum class ReleaseUiAuthority {
    VALID,
    AMBIGUOUS,
    UNMAPPED,
    STALE,
    ERROR,
    DISABLED,
}

enum class ReleaseUiFreshness {
    UNKNOWN,
    FRESH,
    STALE,
    ERROR,
    DISABLED,
}

/**
 * A typed provider-owned presentation. The UI never has to parse a stable key or
 * infer whether a row is an episode, film, special, OVA, or ONA.
 */
data class ReleaseUiPresentation(
    val mediaId: Int?,
    val stream: ReleaseStreamKey,
    val authority: ReleaseUiAuthority,
    val confirmedThroughEpisode: Int?,
    val confirmedInstallments: List<Installment>,
    val confirmedPending: Int,
    val nextExpectedInstallment: Installment?,
    val nextForecast: Forecast?,
    val freshness: ReleaseUiFreshness,
    val sourceRoot: String?,
    val revision: Long,
) {
    init {
        require(mediaId == null || mediaId > 0) { "media id must be positive when present" }
        require(confirmedPending >= 0) { "confirmed pending count must be non-negative" }
        require(revision >= 0L) { "revision must be non-negative" }
    }

    val track: LanguageTrack
        get() = stream.languageTrack

    val providerId: ProviderId
        get() = stream.providerId

    val isAuthoritative: Boolean
        get() = authority == ReleaseUiAuthority.VALID

    val nextForecastAt: Instant?
        get() = nextForecast?.forecastAt

    @Deprecated("Use confirmedPending to make the eligibility meaning explicit")
    val pendingCount: Int
        get() = confirmedPending
}

data class ReleaseUiCalendarItem(
    val mediaId: Int?,
    val stream: ReleaseStreamKey,
    val installment: Installment,
    val forecastAt: Instant?,
    val confirmed: Boolean,
    val authority: ReleaseUiAuthority,
    val sourceDate: LocalDate?,
    val sourceRoot: String?,
    val revision: Long,
) {
    init {
        require(mediaId == null || mediaId > 0) { "media id must be positive when present" }
        require(revision >= 0L) { "revision must be non-negative" }
    }

    val track: LanguageTrack
        get() = stream.languageTrack

    val providerId: ProviderId
        get() = stream.providerId

    val installmentKey: String
        get() = installment.stableKey

    val isAuthoritative: Boolean
        get() = authority == ReleaseUiAuthority.VALID

    /**
     * Stable provider event identity. Media ids are enrichment only and are not
     * part of the identity, so same-media tracks and installments remain rows.
     */
    val eventKey: String
        get() = listOf(
            "provider-event",
            stream.stableKey,
            track.name,
            installment.stableKey,
            if (confirmed) "confirmed" else "forecast",
        ).joinToString("|")
}

object EmptyReleasePresentationRepository : ReleasePresentationRepository {
    override fun observeForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Flow<Map<Int, List<ReleaseUiPresentation>>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    override fun observeCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): Flow<List<ReleaseUiCalendarItem>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

interface ReleasePresentationRepository {
    fun observeForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Flow<Map<Int, List<ReleaseUiPresentation>>>

    fun observeCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): Flow<List<ReleaseUiCalendarItem>>

    suspend fun currentForMedia(
        accountId: Long?,
        mediaIds: Set<Int>,
    ): Map<Int, List<ReleaseUiPresentation>> =
        observeForMedia(accountId, mediaIds).first()

    suspend fun currentCalendar(
        accountId: Long?,
        range: ClosedRange<LocalDate>,
    ): List<ReleaseUiCalendarItem> = observeCalendar(accountId, range).first()
}

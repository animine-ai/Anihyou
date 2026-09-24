package com.axiel7.anihyou.release.core.projection

import com.axiel7.anihyou.release.core.model.AniListFallbackPresentation
import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey

sealed interface ReleasePresentation {
    data class Provider(
        val value: ProviderReleasePresentation,
    ) : ReleasePresentation

    data class AniListFallback(
        val value: AniListFallbackPresentation,
    ) : ReleasePresentation

    data object Unavailable : ReleasePresentation
}

data class ProviderReleasePresentation(
    val mediaId: Int,
    val stream: ReleaseStreamKey,
    val confirmedInstallments: List<Installment>,
    val confirmedPending: Int,
    val nextExpectedInstallment: Installment?,
    val nextForecast: Forecast?,
    val authority: AuthorityStatus,
    val freshness: Freshness,
    val sourceRoot: String?,
    val revision: Long,
) {
    val track: LanguageTrack
        get() = stream.languageTrack

    val providerId: ProviderId
        get() = stream.providerId
}

object ReleasePresentationSelector {
    fun select(
        provider: MediaReleaseProjection?,
        aniListFallback: AniListFallbackPresentation?,
    ): ReleasePresentation {
        if (provider?.authority == AuthorityStatus.VALID && provider.mediaId != null) {
            return ReleasePresentation.Provider(
                value = ProviderReleasePresentation(
                    mediaId = provider.mediaId,
                    stream = provider.stream,
                    confirmedInstallments = provider.confirmedInstallments,
                    confirmedPending = provider.pendingCount,
                    nextExpectedInstallment = provider.nextForecast?.identity?.installment,
                    nextForecast = provider.nextForecast,
                    authority = provider.authority,
                    freshness = provider.freshness,
                    sourceRoot = provider.sourceRoot,
                    revision = provider.revision,
                ),
            )
        }
        return aniListFallback?.let { ReleasePresentation.AniListFallback(it) }
            ?: ReleasePresentation.Unavailable
    }
}

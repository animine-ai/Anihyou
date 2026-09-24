package com.axiel7.anihyou.release.core.model

/**
 * The smallest AniWorld unit that may be mapped to an external catalogue.
 *
 * A series is not itself a mapping subject because AniWorld navigation makes
 * seasons and films distinct catalogue units. Episodes inside one season share
 * the season subject; a film always gets its own subject.
 */
enum class AniWorldMappingSubjectType {
    SEASON,
    FILM,
}

sealed interface AniWorldMappingSubject {
    val siteIdentifier: AniWorldSiteIdentifier
    val stableKey: String
    val type: AniWorldMappingSubjectType

    data class Season(
        override val siteIdentifier: AniWorldSiteIdentifier,
        val navigationSeason: Int,
    ) : AniWorldMappingSubject {
        init {
            require(navigationSeason >= 0) { "navigation season must be non-negative" }
        }

        override val type: AniWorldMappingSubjectType = AniWorldMappingSubjectType.SEASON
        override val stableKey: String =
            "${siteIdentifier.stableKey}/season:$navigationSeason"
    }

    data class Film(
        override val siteIdentifier: AniWorldSiteIdentifier,
        val filmNumber: Int,
    ) : AniWorldMappingSubject {
        init {
            require(filmNumber > 0) { "film number must be positive" }
        }

        override val type: AniWorldMappingSubjectType = AniWorldMappingSubjectType.FILM
        override val stableKey: String =
            "${siteIdentifier.stableKey}/film:$filmNumber"
    }
}

sealed interface AniWorldMappingSubjectResolution {
    data class Resolved(val subject: AniWorldMappingSubject) : AniWorldMappingSubjectResolution

    data class Unmapped(
        val siteIdentifier: AniWorldSiteIdentifier,
        val diagnostic: String,
    ) : AniWorldMappingSubjectResolution {
        init { require(diagnostic.isNotBlank()) { "subject diagnostic must not be blank" } }
    }
}

/** Creates subjects without inventing missing season or film information. */
object AniWorldMappingSubjectFactory {
    fun from(identity: AniWorldInstallmentIdentity): AniWorldMappingSubjectResolution =
        when (val installment = identity.installment) {
            is Installment.Episode -> identity.navigationSeason?.let {
                AniWorldMappingSubjectResolution.Resolved(
                    AniWorldMappingSubject.Season(identity.series, it),
                )
            } ?: AniWorldMappingSubjectResolution.Unmapped(
                identity.series,
                "navigation season is required for a season mapping subject",
            )

            is Installment.Film -> installment.number?.takeIf { it > 0 }?.let {
                AniWorldMappingSubjectResolution.Resolved(
                    AniWorldMappingSubject.Film(identity.series, it),
                )
            } ?: AniWorldMappingSubjectResolution.Unmapped(
                identity.series,
                "positive film number is required for a film mapping subject",
            )

            is Installment.Special -> AniWorldMappingSubjectResolution.Unmapped(
                identity.series,
                "special installments are not mapping subjects",
            )
        }

    fun season(siteIdentifier: AniWorldSiteIdentifier, navigationSeason: Int): AniWorldMappingSubject =
        AniWorldMappingSubject.Season(siteIdentifier, navigationSeason)

    fun film(siteIdentifier: AniWorldSiteIdentifier, filmNumber: Int): AniWorldMappingSubject =
        AniWorldMappingSubject.Film(siteIdentifier, filmNumber)
}

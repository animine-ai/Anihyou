package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.AniWorldShadowPollStore
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import com.axiel7.anihyou.release.core.extension.InstallmentV1
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.data.extension.ExtensionAcquisitionTarget
import com.axiel7.anihyou.release.data.extension.ExtensionTargetSource
import java.security.MessageDigest
import java.time.Clock

/**
 * Converts only eligible, already-proven V3 direct targets into host protocol values.
 * URL grammar validation stays in the AniWorld data adapter; the extension host remains generic.
 */
class AniWorldExtensionTargetSource(
    private val pollStore: AniWorldShadowPollStore,
    private val clock: Clock = Clock.systemUTC(),
) : ExtensionTargetSource {
    override suspend fun targets(): List<ExtensionAcquisitionTarget> {
        val now = clock.instant()
        val candidates = pollStore.eligibleDirectTargets(now)
        return DirectTargetSelectionPolicy.select(candidates, now)
            .flatMap { candidate -> candidate.exactTargetKeys.mapNotNull { target(candidate, it) } }
            .distinctBy { it.target.targetToken }
    }

    private fun target(candidate: com.axiel7.anihyou.release.core.sync.DirectTargetCandidate,
                       key: String): ExtensionAcquisitionTarget? {
        val identity = CanonicalReleaseIdentity.decode(key) ?: return null
        val route = (AniWorldCanonicalRouteParser.parse(candidate.canonicalUrl)
            as? AniWorldCanonicalRouteResult.Success)?.route ?: return null
        if (route.canonicalSeriesPath != identity.seriesPath || route.installment != identity.installment) return null
        if (identity.installment is Installment.Episode && route.season == null) return null
        if (route.season?.let { it !in 0..9999 } == true) return null

        val installment = when (val value = identity.installment) {
            is Installment.Episode -> InstallmentV1(ObservationInstallmentKind.EPISODE,
                buildString {
                    append(value.number)
                    value.fraction?.let { append('.').append(it) }
                })
            is Installment.Film -> InstallmentV1(ObservationInstallmentKind.FILM,
                value.number?.takeIf { it > 0 }?.toString() ?: return null)
            is Installment.Special -> return null
        }
        val track = when (identity.track) {
            LanguageTrack.DE_SUB -> ObservationTrack.DE_SUB
            LanguageTrack.DE_DUB -> ObservationTrack.DE_DUB
            else -> return null
        }
        val token = "aw-target-v1-${sha256(key)}"
        return ExtensionAcquisitionTarget(
            target = ExtensionTargetV1(
                targetToken = token,
                providerSeriesKey = route.slug,
                providerUrl = candidate.canonicalUrl,
                sourceSeason = identity.sourceSeason,
                navigationSeason = route.season,
                installment = installment,
                track = track,
            ),
            canonicalKey = identity.key,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

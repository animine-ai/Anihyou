package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.api.AniWorldShadowPollStore
import com.axiel7.anihyou.release.core.extension.ExtensionTargetV1
import com.axiel7.anihyou.release.core.extension.InstallmentV1
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.model.CanonicalReleaseIdentity
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.sync.DirectTargetCandidate
import com.axiel7.anihyou.release.core.sync.DirectTargetSelectionPolicy
import com.axiel7.anihyou.release.data.extension.ExtensionAcquisitionTarget
import com.axiel7.anihyou.release.data.extension.ExtensionTargetSource
import java.security.MessageDigest
import java.time.Clock

/**
 * Converts eligible host-owned mapping coordinates into provider-neutral extension protocol values.
 * The host does not pass a provider URL: de.aniworld owns exact route construction and validation.
 */
class AniWorldExtensionTargetSource(
    private val pollStore: AniWorldShadowPollStore,
    private val clock: Clock = Clock.systemUTC(),
) : ExtensionTargetSource {
    override suspend fun targets(): List<ExtensionAcquisitionTarget> {
        val now = clock.instant()
        val candidates = pollStore.eligibleMappedDirectTargets(now)
        return DirectTargetSelectionPolicy.select(candidates, now)
            .flatMap { candidate -> candidate.exactTargetKeys.mapNotNull { target(candidate, it) } }
            .distinctBy { it.target.targetToken }
    }

    private fun target(candidate: DirectTargetCandidate, key: String): ExtensionAcquisitionTarget? {
        val identity = CanonicalReleaseIdentity.decode(key) ?: return null
        val providerSeriesKey = candidate.providerSeriesKey ?: return null
        val navigationSeason = candidate.navigationSeason ?: return null
        if (providerSeriesKey.length !in 1..128 || navigationSeason !in 1..9999) return null
        if (identity.track.name !in candidate.tracks) return null

        val installment = when (val value = identity.installment) {
            is Installment.Episode -> InstallmentV1(ObservationInstallmentKind.EPISODE,
                buildString {
                    append(value.number)
                    value.fraction?.let { append('.').append(it) }
                })
            is Installment.Film, is Installment.Special -> return null
        }
        val track = when (identity.track) {
            LanguageTrack.DE_SUB -> ObservationTrack.DE_SUB
            LanguageTrack.DE_DUB -> ObservationTrack.DE_DUB
        }
        val token = "aw-target-v1-${sha256(key)}"
        return ExtensionAcquisitionTarget(
            target = ExtensionTargetV1(
                targetToken = token,
                providerSeriesKey = providerSeriesKey,
                providerUrl = null,
                sourceSeason = identity.sourceSeason,
                navigationSeason = navigationSeason,
                installment = installment,
                track = track,
            ),
            canonicalKey = identity.key,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

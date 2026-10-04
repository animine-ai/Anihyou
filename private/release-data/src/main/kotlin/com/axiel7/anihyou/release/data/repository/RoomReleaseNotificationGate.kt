package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.log.AppLog
import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseNotificationGate
import com.axiel7.anihyou.release.core.api.ReleaseDeliveryDecision
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import com.axiel7.anihyou.release.data.preferences.ReleasePreferencesStore
import kotlinx.coroutines.flow.first

/**
 * Device-side AniList AIRING suppression is enabled only when the local
 * provider projection is currently authoritative for the same account.
 *
 * Release delivery is additionally revalidated against the current known
 * local account progress immediately before posting. Unknown progress fails
 * closed instead of being reconstructed from an older projection.
 */
class RoomReleaseNotificationGate(
    private val database: ReleaseDatabase,
    private val releasePreferencesStore: ReleasePreferencesStore,
    private val accountContextProvider: ReleaseAccountContextProvider,
) : ReleaseNotificationGate {
    override suspend fun suppressAniListAiring(
        accountId: Long,
        mediaId: Int,
        episode: Int,
    ): Boolean {
        if (accountId <= 0L || mediaId <= 0 || episode <= 0) return false
        if (!providerEnabled()) return false
        val suppress = database.releaseDao()
            .getValidMediaProjectionsForAccount(accountId, mediaId)
            .asSequence()
            .mapNotNull { it.toDomainOrNull() }
            .any { projection ->
                projection.mediaId == mediaId &&
                    projection.stream.providerId.value == PROVIDER_ID &&
                    projection.confirmedInstallments
                        .filterIsInstance<Installment.Episode>()
                        .any { it.fraction == null && it.number == episode }
            }
        AppLog.i("notification") { "AniList airing media=$mediaId episode=$episode suppressed by the release lane: $suppress" }
        return suppress
    }

    override suspend fun allowReleaseDelivery(
        accountId: Long, mediaId: Int, identityKey: String, installment: Installment?,
    ): Boolean = evaluateReleaseDelivery(accountId, mediaId, identityKey, installment) == ReleaseDeliveryDecision.ALLOW

    override suspend fun evaluateReleaseDelivery(
        accountId: Long, mediaId: Int, identityKey: String, installment: Installment?,
    ): ReleaseDeliveryDecision {
        val decision = decideReleaseDelivery(accountId, mediaId, identityKey, installment)
        AppLog.i("notification") {
            "release delivery media=$mediaId identity=${AppLog.short(identityKey)} installment=$installment -> $decision"
        }
        return decision
    }

    private suspend fun decideReleaseDelivery(
        accountId: Long, mediaId: Int, identityKey: String, installment: Installment?,
    ): ReleaseDeliveryDecision {
        if (accountId <= 0L || mediaId <= 0 || identityKey.isBlank()) return ReleaseDeliveryDecision.INELIGIBLE
        if (installment == null || installment is Installment.Episode && installment.fraction != null)
            return ReleaseDeliveryDecision.INELIGIBLE
        if (!providerEnabled()) return ReleaseDeliveryDecision.INELIGIBLE

        // Do not turn a cold-cache miss into a permanently cancelled notification. No new AniList query policy.
        val accountContext = accountContextProvider.current(setOf(mediaId))
        if (accountContext.accountId == null) return ReleaseDeliveryDecision.UNKNOWN
        if (accountContext.accountId != accountId) return ReleaseDeliveryDecision.INELIGIBLE
        val currentProgress = accountContext.progressFor(mediaId) ?: return ReleaseDeliveryDecision.UNKNOWN
        val confirmed = database.releaseDao().getValidMediaProjectionsForAccount(accountId, mediaId)
            .asSequence().mapNotNull { it.toDomainOrNull() }.any { projection ->
                projection.confirmedInstallments.any { candidate ->
                    candidate == installment && SourceIdentity(projection.stream, candidate).stableKey == identityKey
                }
            }
        return if (confirmed && isTypedEligible(installment, currentProgress)) ReleaseDeliveryDecision.ALLOW
            else ReleaseDeliveryDecision.INELIGIBLE
    }

    private fun isTypedEligible(
        installment: Installment,
        currentProgress: Int,
    ): Boolean = when (installment) {
        is Installment.Episode -> installment.number > currentProgress
        is Installment.Film,
        is Installment.Special,
        -> true
    }

    private suspend fun providerEnabled(): Boolean =
        releasePreferencesStore.preferences.first().selectedProvider?.value == PROVIDER_ID

    private companion object {
        const val PROVIDER_ID = "aniworld"
    }
}

package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseNotificationGate
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
        return database.releaseDao()
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
    }

    override suspend fun allowReleaseDelivery(
        accountId: Long,
        mediaId: Int,
        identityKey: String,
        installment: Installment?,
    ): Boolean {
        if (accountId <= 0L || mediaId <= 0 || identityKey.isBlank()) return false
        if (installment == null || installment is Installment.Episode && installment.fraction != null) {
            return false
        }
        if (!providerEnabled()) return false

        val accountContext = runCatching {
            accountContextProvider.current(setOf(mediaId))
        }.getOrNull() ?: return false
        if (accountContext.accountId != accountId) return false
        val currentProgress = accountContext.progressFor(mediaId) ?: return false

        return database.releaseDao()
            .getValidMediaProjectionsForAccount(accountId, mediaId)
            .asSequence()
            .mapNotNull { it.toDomainOrNull() }
            .any { projection ->
                val confirmed = projection.confirmedInstallments.any { candidate ->
                    candidate == installment &&
                        SourceIdentity(projection.stream, candidate).stableKey == identityKey
                }
                confirmed && isTypedEligible(installment, currentProgress)
            }
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

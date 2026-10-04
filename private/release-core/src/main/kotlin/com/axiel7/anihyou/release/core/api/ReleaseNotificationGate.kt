package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.Installment

enum class ReleaseDeliveryDecision { ALLOW, INELIGIBLE, UNKNOWN }

interface ReleaseNotificationGate {
    suspend fun suppressAniListAiring(
        accountId: Long,
        mediaId: Int,
        episode: Int,
    ): Boolean

    suspend fun evaluateReleaseDelivery(
        accountId: Long, mediaId: Int, identityKey: String, installment: Installment? = null,
    ): ReleaseDeliveryDecision = if (allowReleaseDelivery(accountId, mediaId, identityKey, installment))
        ReleaseDeliveryDecision.ALLOW else ReleaseDeliveryDecision.INELIGIBLE

    suspend fun allowReleaseDelivery(
        accountId: Long,
        mediaId: Int,
        identityKey: String,
        installment: Installment? = null,
    ): Boolean
}

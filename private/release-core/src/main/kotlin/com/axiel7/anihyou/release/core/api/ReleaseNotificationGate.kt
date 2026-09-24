package com.axiel7.anihyou.release.core.api

import com.axiel7.anihyou.release.core.model.Installment

interface ReleaseNotificationGate {
    suspend fun suppressAniListAiring(
        accountId: Long,
        mediaId: Int,
        episode: Int,
    ): Boolean

    suspend fun allowReleaseDelivery(
        accountId: Long,
        mediaId: Int,
        identityKey: String,
        installment: Installment? = null,
    ): Boolean
}

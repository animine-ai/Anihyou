package com.axiel7.anihyou.feature.worker

import com.axiel7.anihyou.core.model.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotificationTapTargetTest {
    @Test fun aTapOpensTheMediaDetailsOfTheNotifiedMediaWithoutTouchingAniListNotifications() {
        val target = releaseTapTarget(mediaId = 154587)
        assertEquals(DeepLink.Type.ANIME.intentAction, target.action)
        assertEquals("media_details", target.action)
        assertEquals("154587", target.contentId)
        assertTrue("a release notification is not an AniList notification and must not mark them as read",
            target.opensFromOwnSurface)
    }
}

package com.axiel7.anihyou.release.core.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotificationIdTest {
    @Test
    fun sameEventKeyAlwaysGetsTheSamePositiveId() {
        val eventKey = "release-confirmation-v1::7::42::aniworld/series/EPISODE/2026/DE_SUB/episode:5::RECENT_LIST_EXPLICIT_MARKER"

        assertEquals(
            ReleaseNotificationId.fromEventKey(eventKey),
            ReleaseNotificationId.fromEventKey(eventKey),
        )
        assertTrue(ReleaseNotificationId.fromEventKey(eventKey) > 0)
    }

    @Test
    fun accountStreamAndInstallmentChangesDoNotShareTheId() {
        val first = ReleaseNotificationId.fromEventKey("event::account-7::de-sub::episode-5")
        val otherAccount = ReleaseNotificationId.fromEventKey("event::account-8::de-sub::episode-5")
        val otherTrack = ReleaseNotificationId.fromEventKey("event::account-7::de-dub::episode-5")
        val otherEpisode = ReleaseNotificationId.fromEventKey("event::account-7::de-sub::episode-6")

        assertNotEquals(first, otherAccount)
        assertNotEquals(first, otherTrack)
        assertNotEquals(first, otherEpisode)
    }
}

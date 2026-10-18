package com.axiel7.anihyou.release.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotificationScopeTest {
    private fun entry(status: NotificationListStatus, hidden: Boolean = false) = ListEntryInfo(3, status, hidden)

    @Test fun theDefaultAnnouncesOnlyWatchingAndRewatchingAndNeverHiddenOrNotOnTheList() {
        val scope = ReleaseNotificationScope()
        assertTrue(scope.allows(1, entry(NotificationListStatus.CURRENT)))
        assertTrue(scope.allows(1, entry(NotificationListStatus.REPEATING)))
        assertFalse(scope.allows(1, entry(NotificationListStatus.PAUSED)))
        assertFalse(scope.allows(1, entry(NotificationListStatus.PLANNING)))
        assertFalse(scope.allows(1, entry(NotificationListStatus.CURRENT, hidden = true)))
        assertFalse(scope.allows(1, null))
        assertFalse(scope.alsoAniListAiring)
        assertFalse(scope.announceByCalendar)
    }

    @Test fun hiddenEntriesCanBeSwitchedOnAndAllIgnoresStatusAndHiddenFlags() {
        assertTrue(ReleaseNotificationScope(includeHidden = true).allows(1, entry(NotificationListStatus.CURRENT, hidden = true)))
        val all = ReleaseNotificationScope(mode = NotificationScopeMode.ALL)
        assertTrue(all.allows(1, entry(NotificationListStatus.DROPPED, hidden = true)))
        assertTrue(all.allows(1, null))
    }

    @Test fun customFollowsTheChosenStatusesAndNotOnList() {
        val custom = ReleaseNotificationScope(mode = NotificationScopeMode.CUSTOM, statuses = setOf(NotificationListStatus.PAUSED))
        assertTrue(custom.allows(1, entry(NotificationListStatus.PAUSED)))
        assertFalse(custom.allows(1, entry(NotificationListStatus.CURRENT)))
        assertFalse(custom.allows(1, null))
        assertTrue(custom.copy(includeNotOnList = true).allows(1, null))
    }

    @Test fun aDecisionAboutOneAnimeBeatsTheGeneralChoice() {
        val scope = ReleaseNotificationScope(overrides = mapOf(7 to true, 8 to false))
        assertTrue(scope.allows(7, null))
        assertTrue(scope.allows(7, entry(NotificationListStatus.DROPPED)))
        assertFalse(scope.allows(8, entry(NotificationListStatus.CURRENT)))
        assertEquals(false, scope.allows(9, null))
    }
}

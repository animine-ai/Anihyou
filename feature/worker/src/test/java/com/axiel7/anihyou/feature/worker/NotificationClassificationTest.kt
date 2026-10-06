package com.axiel7.anihyou.feature.worker

import com.axiel7.anihyou.core.model.notification.GenericNotification
import com.axiel7.anihyou.core.network.type.NotificationType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassificationTest {
    @Test
    fun failedAiringClassificationStopsAtTimestampWithoutReplayingOlderSafeWork() = runBlocking {
        val result = classifyAniListDeviceNotifications(
            notifications = listOf(
                notification(id = 1, createdAt = 100, type = null, text = "safe-old"),
                notification(
                    id = 2,
                    createdAt = 200,
                    type = NotificationType.AIRING,
                    text = "Episode 10 of Test aired",
                ),
                notification(id = 3, createdAt = 300, type = null, text = "safe-new"),
            ),
            viewerId = 7,
            suppressAiring = { _, _, _ -> error("gate unavailable") },
        )

        assertTrue(result.retryNeeded)
        assertEquals(100, result.cursorCreatedAt)
        assertEquals(listOf(1), result.deviceNotifications.map { it.id })
    }

    @Test
    fun safeNotificationAtSameTimestampAsFailedAiringIsDeferredWithTheFailedGroup() = runBlocking {
        val result = classifyAniListDeviceNotifications(
            notifications = listOf(
                notification(id = 1, createdAt = 200, type = null, text = "same-time-safe"),
                notification(
                    id = 2,
                    createdAt = 200,
                    type = NotificationType.AIRING,
                    text = "Episode 10 of Test aired",
                ),
            ),
            viewerId = 7,
            suppressAiring = { _, _, _ -> error("gate unavailable") },
        )

        assertTrue(result.retryNeeded)
        assertEquals(null, result.cursorCreatedAt)
        assertTrue(result.deviceNotifications.isEmpty())
    }

    @Test
    fun intentionallySuppressedAiringStillAdvancesCursorAndDoesNotPoisonSafeWork() = runBlocking {
        val result = classifyAniListDeviceNotifications(
            notifications = listOf(
                notification(
                    id = 1,
                    createdAt = 100,
                    type = NotificationType.AIRING,
                    text = "Episode 9 of Test aired",
                ),
                notification(id = 2, createdAt = 200, type = null, text = "safe"),
            ),
            viewerId = 7,
            suppressAiring = { _, _, _ -> true },
        )

        assertFalse(result.retryNeeded)
        assertEquals(200, result.cursorCreatedAt)
        assertEquals(listOf(2), result.deviceNotifications.map { it.id })
    }

    private fun notification(
        id: Int,
        createdAt: Int,
        type: NotificationType?,
        text: String,
    ) = GenericNotification(
        id = id,
        text = text,
        imageUrl = null,
        contentId = 42,
        type = type,
        createdAt = createdAt,
    )
}

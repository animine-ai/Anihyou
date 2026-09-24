package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.core.notification.ReleaseNotificationCandidate
import com.axiel7.anihyou.release.data.db.NotificationOutboxStatus
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_1_2
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_2_3
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_3_4
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_4_5
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_5_6
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_6_7
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import com.axiel7.anihyou.release.data.db.toDomainOrNull
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationOutboxStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "notification-outbox-test.db"
    private val createdAt = Instant.parse("2026-09-11T12:00:00Z")

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun enqueueIsIdempotentAndDueRowsAreOrdered() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val first = candidate(1, LanguageTrack.DE_SUB)
            val second = candidate(2, LanguageTrack.DE_DUB)
            assertTrue(store.enqueue(first, createdAt))
            assertFalse(store.enqueue(first, createdAt.plusSeconds(1)))
            assertTrue(store.enqueue(second, createdAt.plusSeconds(2)))
            assertEquals(2, database.releaseDao().notificationOutboxCount())
            assertEquals(
                listOf(first.eventKey, second.eventKey),
                store.observeDue(createdAt).first().map { it.eventKey },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun claimLeaseAndDeliveryAreIdempotent() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val item = candidate(3, LanguageTrack.DE_SUB)
            store.enqueue(item, createdAt)

            val claimed = store.claim(
                item.eventKey,
                now = createdAt,
                leaseUntil = createdAt.plusSeconds(60),
            )
            assertEquals(NotificationOutboxStatus.POSTING, claimed?.status)
            assertEquals(1, claimed?.attemptCount)
            assertTrue(store.observeDue(createdAt.plusSeconds(30)).first().isEmpty())
            assertTrue(store.markDelivered(item.eventKey, createdAt.plusSeconds(10)))
            assertTrue(store.markDelivered(item.eventKey, createdAt.plusSeconds(20)))
            assertEquals(
                NotificationOutboxStatus.DELIVERED,
                database.releaseDao().getNotificationOutbox(item.eventKey)!!.toDomainOrNull()!!.status,
            )
            assertTrue(store.observeDue(createdAt.plusSeconds(120)).first().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun expiredPostingLeaseIsReclaimedWithTheSameEventKey() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val item = candidate(4, LanguageTrack.DE_SUB)
            store.enqueue(item, createdAt)
            assertEquals(
                NotificationOutboxStatus.POSTING,
                store.claim(item.eventKey, createdAt, createdAt.plusSeconds(30))?.status,
            )
            assertEquals(
                listOf(item.eventKey),
                store.observeDue(createdAt.plusSeconds(30)).first().map { it.eventKey },
            )
            val reclaimed = store.claim(item.eventKey, createdAt.plusSeconds(30), createdAt.plusSeconds(90))
            assertEquals(NotificationOutboxStatus.POSTING, reclaimed?.status)
            assertEquals(2, reclaimed?.attemptCount)
        } finally {
            database.close()
        }
    }

    @Test
    fun unexpiredLeaseCannotBeClaimedByASecondDispatcher() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val item = candidate(9, LanguageTrack.DE_SUB)
            store.enqueue(item, createdAt)

            assertEquals(
                NotificationOutboxStatus.POSTING,
                store.claim(item.eventKey, createdAt, createdAt.plusSeconds(60))?.status,
            )
            assertNull(store.claim(item.eventKey, createdAt.plusSeconds(30), createdAt.plusSeconds(90)))
            assertEquals(
                NotificationOutboxStatus.POSTING,
                store.claim(item.eventKey, createdAt.plusSeconds(60), createdAt.plusSeconds(120))?.status,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun temporaryFailuresRemainPendingThenBecomeCancelledAtTheBound() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val item = candidate(5, LanguageTrack.DE_SUB)
            store.enqueue(item, createdAt)

            store.claim(item.eventKey, createdAt, createdAt.plusSeconds(30))
            assertEquals(
                NotificationOutboxStatus.PENDING,
                store.reschedulePending(item.eventKey, createdAt.plusSeconds(60), "temporary"),
            )
            assertEquals(1, database.releaseDao().getNotificationOutbox(item.eventKey)!!.attemptCount)

            store.claim(item.eventKey, createdAt.plusSeconds(60), createdAt.plusSeconds(90))
            assertEquals(
                NotificationOutboxStatus.PENDING,
                store.reschedulePending(item.eventKey, createdAt.plusSeconds(120), "temporary-2"),
            )
            store.claim(item.eventKey, createdAt.plusSeconds(120), createdAt.plusSeconds(150))
            assertEquals(
                NotificationOutboxStatus.CANCELLED,
                store.reschedulePending(item.eventKey, createdAt.plusSeconds(180), "temporary-3"),
            )
            assertEquals(
                NotificationOutboxStatus.CANCELLED,
                database.releaseDao().getNotificationOutbox(item.eventKey)!!.toDomainOrNull()!!.status,
            )
            assertFalse(store.markDelivered(item.eventKey, createdAt.plusSeconds(200)))
        } finally {
            database.close()
        }
    }

    @Test
    fun cancellationIsIdempotentAndMalformedRowsAreIsolated() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val valid = candidate(6, LanguageTrack.DE_SUB)
            store.enqueue(valid, createdAt)
            val row = database.releaseDao().getNotificationOutbox(valid.eventKey)!!
            database.releaseDao().upsertNotificationOutbox(
                row.copy(
                    eventKey = "malformed",
                    identityKey = "bad",
                    payload = "not-the-identity",
                    status = "UNKNOWN",
                ),
            )
            assertEquals(listOf(valid.eventKey), store.observeDue(createdAt).first().map { it.eventKey })
            assertTrue(store.cancel(valid.eventKey, "logout", createdAt.plusSeconds(1)))
            assertTrue(store.cancel(valid.eventKey, "logout-again", createdAt.plusSeconds(2)))
            assertEquals(
                NotificationOutboxStatus.CANCELLED,
                database.releaseDao().getNotificationOutbox(valid.eventKey)!!.toDomainOrNull()!!.status,
            )
            assertNull(database.releaseDao().getNotificationOutbox("missing"))
        } finally {
            database.close()
        }
    }

    @Test
    fun exhaustedPostingLeaseIsCancelledWithEffectiveClaimTime() = runBlocking {
        val database = openDatabase()
        try {
            val store = RoomNotificationOutboxStore(database)
            val item = candidate(8, LanguageTrack.DE_SUB)
            store.enqueue(item, createdAt)
            val row = database.releaseDao().getNotificationOutbox(item.eventKey)!!
            database.releaseDao().upsertNotificationOutbox(
                row.copy(
                    status = NotificationOutboxStatus.POSTING.name,
                    attemptCount = MAX_NOTIFICATION_ATTEMPTS,
                    nextAttemptAt = null,
                    postingLeaseUntil = createdAt.minusSeconds(1).toString(),
                    deliveredAt = null,
                    cancelledAt = null,
                ),
            )

            val cancelled = store.claim(item.eventKey, now = createdAt.plusSeconds(10))

            assertEquals(NotificationOutboxStatus.CANCELLED, cancelled?.status)
            assertEquals(
                createdAt.plusSeconds(10).toString(),
                database.releaseDao().getNotificationOutbox(item.eventKey)?.cancelledAt,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun outboxQueryUsesAccountStatusAttemptIndex() = runBlocking {
        val database = openDatabase()
        try {
            val cursor = database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN " +
                    "SELECT eventKey FROM notification_outbox " +
                    "INDEXED BY idx_notification_outbox_account_status_attempt " +
                    "WHERE accountId = 7 AND status = 'PENDING' AND nextAttemptAt IS NULL",
            )
            val details = buildString {
                cursor.use {
                    val detailColumn = it.getColumnIndexOrThrow("detail")
                    while (it.moveToNext()) append(it.getString(detailColumn))
                }
            }
            assertTrue(details.contains("idx_notification_outbox_account_status_attempt"))
        } finally {
            database.close()
        }
    }

    private fun openDatabase(): ReleaseDatabase = Room.databaseBuilder(
        context,
        ReleaseDatabase::class.java,
        databaseName,
    ).addMigrations(
        RELEASE_MIGRATION_1_2,
        RELEASE_MIGRATION_2_3,
        RELEASE_MIGRATION_3_4,
        RELEASE_MIGRATION_4_5,
        RELEASE_MIGRATION_5_6,
        RELEASE_MIGRATION_6_7,
    ).allowMainThreadQueries().build()

    private fun candidate(episode: Int, track: LanguageTrack): ReleaseNotificationCandidate {
        val stream = ReleaseStreamKey(
            providerId = ProviderId("provider"),
            stableSeriesKey = SourceSeriesKey("series"),
            releaseKind = ReleaseKind.EPISODE,
            sourceSeason = 2026,
            languageTrack = track,
        )
        return ReleaseNotificationCandidate(
            accountId = 7L,
            mediaId = 42,
            identity = SourceIdentity(stream, Installment.Episode(episode)),
            evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
            observedAt = createdAt,
        )
    }
}

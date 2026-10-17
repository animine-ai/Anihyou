package com.axiel7.anihyou.release.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.release.core.api.ExtensionReleaseRefreshScheduler
import com.axiel7.anihyou.release.core.api.ExtensionRefreshTrigger
import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import com.axiel7.anihyou.release.core.api.ListEntryInfo
import com.axiel7.anihyou.release.core.api.NotificationListStatus
import com.axiel7.anihyou.release.core.api.NotificationScopeMode
import com.axiel7.anihyou.release.core.api.ReleaseNotificationScope
import com.axiel7.anihyou.release.core.api.ReleaseAccountContext
import com.axiel7.anihyou.release.core.api.ReleaseAccountContextProvider
import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.api.ReleaseOutboxScheduler
import com.axiel7.anihyou.release.core.api.ReleasePreferencesRepository
import com.axiel7.anihyou.release.core.api.ReleaseProviderPreferences
import com.axiel7.anihyou.release.core.api.ReleasePresentationRepository
import com.axiel7.anihyou.release.core.api.ReleaseUiAuthority
import com.axiel7.anihyou.release.core.api.ReleaseUiCalendarItem
import com.axiel7.anihyou.release.core.api.ReleaseUiPresentation
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_1_2
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_2_3
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_3_4
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_4_5
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_5_6
import com.axiel7.anihyou.release.data.db.RELEASE_MIGRATION_6_7
import com.axiel7.anihyou.release.data.db.ReleaseDatabase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What follows a refresh: confirmed episodes become notifications, planned ones become targeted checks of the list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionReleaseFollowUpTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "follow-up-test.db"
    private val now = Instant.parse("2026-10-17T16:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @After fun cleanup() { context.deleteDatabase(databaseName) }

    private fun stream(slug: String, track: LanguageTrack = LanguageTrack.DE_SUB) = ReleaseStreamKey(
        ProviderId("aniworld"), SourceSeriesKey("/anime/stream/$slug"), ReleaseKind.EPISODE, 1, track)

    private fun item(
        mediaId: Int?, episode: Int, at: Instant, confirmed: Boolean, slug: String = "some-series",
        track: LanguageTrack = LanguageTrack.DE_SUB, authority: ReleaseUiAuthority = ReleaseUiAuthority.VALID,
    ) = ReleaseUiCalendarItem(mediaId, stream(slug, track), Installment.Episode(episode), at, confirmed, authority,
        LocalDate.of(2026, 10, 17), null, 1)

    private class Presentation(val rows: List<ReleaseUiCalendarItem>) : ReleasePresentationRepository {
        override fun observeForMedia(accountId: Long?, mediaIds: Set<Int>): Flow<Map<Int, List<ReleaseUiPresentation>>> =
            flowOf(emptyMap())
        override fun observeCalendar(accountId: Long?, range: ClosedRange<LocalDate>): Flow<List<ReleaseUiCalendarItem>> =
            flowOf(rows)
    }

    private class Prefs(val enabled: Boolean, val track: ReleaseGermanTrack = ReleaseGermanTrack.DE_SUB) : ReleasePreferencesRepository {
        override val releasePreferences = flowOf(ReleaseProviderPreferences("aniworld", track, enabled))
        override suspend fun setProviderEnabled(enabled: Boolean) = Unit
        override suspend fun setPreferredTrack(track: ReleaseGermanTrack) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
    }

    private class Recording : ReleaseOutboxScheduler, ExtensionReleaseRefreshScheduler {
        var outboxScheduled = 0
        var dueChecks: List<Instant> = emptyList()
        override fun schedule() { outboxScheduled++ }
        override fun scheduleDue() = Unit
        override fun ensureSlotScheduled() = Unit
        override fun scheduleForeground() = Unit
        override fun scheduleNow() = Unit
        override fun scheduleDeferred(trigger: ExtensionRefreshTrigger, notBefore: Instant, chain: Int) = Unit
        override fun scheduleDueReleases(times: List<Instant>) { dueChecks = times }
        override fun cancel() = Unit
    }

    private fun followUp(
        database: ReleaseDatabase, rows: List<ReleaseUiCalendarItem>, enabled: Boolean, progress: Map<Int, Int>,
        recording: Recording, track: ReleaseGermanTrack = ReleaseGermanTrack.DE_SUB, account: Long? = 7L,
        sourceActive: Boolean = true, scope: ReleaseNotificationScope = ReleaseNotificationScope(),
        entries: Map<Int, ListEntryInfo> = progress.mapValues { ListEntryInfo(it.value, NotificationListStatus.CURRENT) },
        fetch: suspend (Set<Int>) -> Map<Int, ListEntryInfo> = { ids -> entries.filterKeys { it in ids } },
        learn: suspend (List<DetailMappingRequest>) -> Unit = {},
    ) = ExtensionReleaseFollowUp(
        presentation = Presentation(rows),
        preferences = Prefs(enabled, track),
        accountContext = ReleaseAccountContextProvider { _ ->
            if (account == null) ReleaseAccountContext(null) else ReleaseAccountContext(account)
        },
        outbox = RoomNotificationOutboxStore(database, clock),
        outboxScheduler = recording,
        refreshScheduler = recording,
        sourceActive = { sourceActive },
        titleOf = { id -> if (id == 42) "Frieren" else null },
        fetchEntries = fetch,
        scope = { scope },
        learnNumbering = learn,
        clock = clock,
    )

    private fun openDatabase(): ReleaseDatabase = Room.databaseBuilder(context, ReleaseDatabase::class.java, databaseName)
        .addMigrations(RELEASE_MIGRATION_1_2, RELEASE_MIGRATION_2_3, RELEASE_MIGRATION_3_4, RELEASE_MIGRATION_4_5,
            RELEASE_MIGRATION_5_6, RELEASE_MIGRATION_6_7).allowMainThreadQueries().build()

    private suspend fun due(database: ReleaseDatabase) = RoomNotificationOutboxStore(database, clock).observeDue(now).first()

    @Test fun aConfirmedUnseenEpisodeBecomesOneNotificationWithTheAniListTitleAndRunsTheDispatcher() = runBlocking {
        val database = openDatabase()
        try {
            val recording = Recording()
            val rows = listOf(item(42, 5, now.minus(Duration.ofMinutes(20)), confirmed = true))
            val follow = followUp(database, rows, enabled = true, progress = mapOf(42 to 4), recording = recording)
            follow.run()
            val pending = due(database)
            assertEquals(1, pending.size)
            assertEquals("Frieren", pending.single().displayTitle)
            assertEquals(1, recording.outboxScheduled)
            // The same refresh again adds nothing and does not wake the dispatcher for nothing.
            follow.run()
            assertEquals(1, due(database).size)
            assertEquals(1, recording.outboxScheduled)
        } finally { database.close() }
    }

    @Test fun anEpisodeThatIsOnlyPlannedIsNeverAnnouncedAsAvailable() = runBlocking {
        val database = openDatabase()
        try {
            val recording = Recording()
            val rows = listOf(item(42, 5, now.minus(Duration.ofMinutes(20)), confirmed = false))
            followUp(database, rows, enabled = true, progress = mapOf(42 to 4), recording = recording).run()
            assertTrue(due(database).isEmpty())
            assertEquals(0, recording.outboxScheduled)
        } finally { database.close() }
    }

    @Test fun notificationsOffWatchedUnknownProgressWrongTrackOldAndFutureEpisodesAreNotAnnounced() = runBlocking {
        val database = openDatabase()
        try {
            val recent = now.minus(Duration.ofMinutes(20))
            val rows = listOf(
                item(1, 5, recent, true, "watched"),
                item(2, 5, recent, true, "unknown-progress"),
                item(3, 5, recent, true, "dub", LanguageTrack.DE_DUB),
                item(4, 5, now.minus(Duration.ofHours(13)), true, "old"),
                item(5, 5, now.plus(Duration.ofHours(2)), true, "future"),
                item(6, 5, recent, true, "unbound").copy(mediaId = null),
                item(7, 5, recent, true, "ambiguous", authority = ReleaseUiAuthority.AMBIGUOUS),
            )
            val progress = mapOf(1 to 5, 3 to 4, 4 to 4, 5 to 4, 7 to 4)
            val off = Recording()
            followUp(database, rows, enabled = false, progress = progress, recording = off).run()
            assertTrue(due(database).isEmpty())
            val on = Recording()
            followUp(database, rows, enabled = true, progress = progress, recording = on).run()
            assertTrue("nothing here may be announced, got ${due(database).map { it.mediaId }}", due(database).isEmpty())
            assertEquals(0, on.outboxScheduled)
        } finally { database.close() }
    }

    @Test fun filmsAndFractionalInstallmentsAreLeftAloneWithoutFailing() = runBlocking {
        val database = openDatabase()
        try {
            val recent = now.minus(Duration.ofMinutes(10))
            val rows = listOf(
                item(42, 1, recent, true, "film").copy(installment = Installment.Film(1)),
                item(43, 1, recent, true, "half").copy(installment = Installment.Episode(5, 5)),
                item(44, 5, recent, true, "whole"),
            )
            val recording = Recording()
            followUp(database, rows, true, mapOf(42 to 0, 43 to 4, 44 to 4), recording).run()
            assertEquals(listOf(44), due(database).map { it.mediaId })
        } finally { database.close() }
    }

    private val recent get() = now.minus(Duration.ofMinutes(5))
    private fun rowsFor(vararg ids: Int) = ids.map { item(it, 5, recent, true, "series-$it") }

    @Test fun byDefaultOnlyWatchingAndRewatchingAreAnnouncedAndHiddenEntriesAreNot() = runBlocking {
        val database = openDatabase()
        try {
            val entries = mapOf(
                1 to ListEntryInfo(4, NotificationListStatus.CURRENT),
                2 to ListEntryInfo(4, NotificationListStatus.REPEATING),
                3 to ListEntryInfo(4, NotificationListStatus.PAUSED),
                4 to ListEntryInfo(4, NotificationListStatus.PLANNING),
                5 to ListEntryInfo(4, NotificationListStatus.CURRENT, hiddenFromStatusLists = true),
            )
            followUp(database, rowsFor(1, 2, 3, 4, 5, 6), true, emptyMap(), Recording(), entries = entries).run()
            assertEquals(listOf(1, 2), due(database).map { it.mediaId }.sorted())
        } finally { database.close() }
    }

    @Test fun hiddenEntriesCanBeSwitchedOn() = runBlocking {
        val database = openDatabase()
        try {
            val entries = mapOf(5 to ListEntryInfo(4, NotificationListStatus.CURRENT, hiddenFromStatusLists = true))
            followUp(database, rowsFor(5), true, emptyMap(), Recording(), entries = entries,
                scope = ReleaseNotificationScope(includeHidden = true)).run()
            assertEquals(listOf(5), due(database).map { it.mediaId })
        } finally { database.close() }
    }

    @Test fun allAnnouncesEverythingEvenAnimeThatAreNotOnTheListButStillSkipsWatchedEpisodes() = runBlocking {
        val database = openDatabase()
        try {
            val entries = mapOf(
                1 to ListEntryInfo(4, NotificationListStatus.COMPLETED),
                2 to ListEntryInfo(5, NotificationListStatus.CURRENT),
            )
            followUp(database, rowsFor(1, 2, 3), true, emptyMap(), Recording(), entries = entries,
                scope = ReleaseNotificationScope(mode = NotificationScopeMode.ALL)).run()
            assertEquals(listOf(1, 3), due(database).map { it.mediaId }.sorted())
        } finally { database.close() }
    }

    @Test fun customAnnouncesExactlyTheChosenStatusesAndNotOnListOnlyIfAsked() = runBlocking {
        val database = openDatabase()
        try {
            val entries = mapOf(
                1 to ListEntryInfo(4, NotificationListStatus.PAUSED),
                2 to ListEntryInfo(4, NotificationListStatus.CURRENT),
                3 to ListEntryInfo(4, NotificationListStatus.DROPPED),
            )
            val chosen = ReleaseNotificationScope(mode = NotificationScopeMode.CUSTOM,
                statuses = setOf(NotificationListStatus.PAUSED, NotificationListStatus.DROPPED))
            followUp(database, rowsFor(1, 2, 3, 4), true, emptyMap(), Recording(), entries = entries, scope = chosen).run()
            assertEquals(listOf(1, 3), due(database).map { it.mediaId }.sorted())
            followUp(database, rowsFor(4), true, emptyMap(), Recording(), entries = entries,
                scope = chosen.copy(includeNotOnList = true)).run()
            assertEquals(listOf(1, 3, 4), due(database).map { it.mediaId }.sorted())
        } finally { database.close() }
    }

    @Test fun aDecisionAboutOneAnimeBeatsTheScopeButNeverAnnouncesAWatchedEpisode() = runBlocking {
        val database = openDatabase()
        try {
            val entries = mapOf(
                1 to ListEntryInfo(4, NotificationListStatus.CURRENT),
                2 to ListEntryInfo(4, NotificationListStatus.DROPPED),
                3 to ListEntryInfo(5, NotificationListStatus.DROPPED),
            )
            val chosen = ReleaseNotificationScope(overrides = mapOf(1 to false, 2 to true, 3 to true, 4 to true))
            followUp(database, rowsFor(1, 2, 3, 4), true, emptyMap(), Recording(), entries = entries, scope = chosen).run()
            // 1 switched off, 2 switched on, 3 switched on but already watched, 4 switched on and not on the list.
            assertEquals(listOf(2, 4), due(database).map { it.mediaId }.sorted())
        } finally { database.close() }
    }

    @Test fun theNumberingOfFollowedAnimeIsTaughtOnceFromTheEntriesAniListReturned() = runBlocking {
        val database = openDatabase()
        try {
            val taught = mutableListOf<List<DetailMappingRequest>>()
            val request = DetailMappingRequest(50, setOf("Series 50", "Series Fifty"), episodeExtent = 12)
            val entries = mapOf(50 to ListEntryInfo(4, NotificationListStatus.CURRENT, numbering = request))
            val follow = followUp(database, rowsFor(50, 51), true, emptyMap(), Recording(), entries = entries,
                learn = { taught += it })
            follow.run(); follow.run()
            assertEquals(listOf(listOf(request)), taught)
        } finally { database.close() }
    }

    @Test fun theListIsAskedOnceWithinTheTtlAndNothingIsAnnouncedIfAniListCannotBeAsked() = runBlocking {
        val database = openDatabase()
        try {
            val asked = mutableListOf<Set<Int>>()
            val follow = followUp(database, rowsFor(50, 51), true, mapOf(50 to 4), Recording(),
                fetch = { ids -> asked += ids; mapOf(50 to ListEntryInfo(4, NotificationListStatus.CURRENT)) })
            follow.run(); follow.run()
            // The list once, then the one candidate again just before it is announced; the second refresh asks for nothing.
            assertEquals(listOf(setOf(50, 51), setOf(50)), asked)
            assertEquals(listOf(50), due(database).map { it.mediaId })
            val failing = followUp(database, rowsFor(60), true, emptyMap(), Recording(),
                fetch = { error("offline") })
            failing.run()
            assertEquals(listOf(50), due(database).map { it.mediaId })
        } finally { database.close() }
    }

    @Test fun theDubIsAnnouncedWhenItIsTheChosenTrack() = runBlocking {
        val database = openDatabase()
        try {
            val rows = listOf(item(42, 5, now.minus(Duration.ofMinutes(5)), true, track = LanguageTrack.DE_DUB),
                item(43, 3, now.minus(Duration.ofMinutes(5)), true, "sub-only"))
            followUp(database, rows, true, mapOf(42 to 4, 43 to 2), Recording(), track = ReleaseGermanTrack.DE_DUB).run()
            assertEquals(listOf(42), due(database).map { it.mediaId })
        } finally { database.close() }
    }

    @Test fun plannedEpisodesOfFollowedSeriesGetTargetedChecksAndWatchedOnesDoNot() = runBlocking {
        val database = openDatabase()
        try {
            val recording = Recording()
            val at = Instant.parse("2026-10-17T17:00:00Z")
            val rows = listOf(item(42, 5, at, false), item(43, 9, at.plus(Duration.ofHours(2)), false, "watched"))
            followUp(database, rows, enabled = false, progress = mapOf(42 to 4, 43 to 9), recording = recording).run()
            // Notifications are off, but the list is still asked at the planned time: the calendar shows what it finds.
            assertEquals(listOf(1L, 6L, 16L, 31L).map { at.plus(Duration.ofMinutes(it)) }, recording.dueChecks)
        } finally { database.close() }
    }

    @Test fun withoutAnActiveSourceOrAccountNothingHappens() = runBlocking {
        val database = openDatabase()
        try {
            val rows = listOf(item(42, 5, now.minus(Duration.ofMinutes(20)), true))
            val noSource = Recording()
            followUp(database, rows, true, mapOf(42 to 4), noSource, sourceActive = false).run()
            val noAccount = Recording()
            followUp(database, rows, true, mapOf(42 to 4), noAccount, account = null).run()
            assertTrue(due(database).isEmpty())
            assertEquals(0, noSource.outboxScheduled + noAccount.outboxScheduled)
        } finally { database.close() }
    }
}

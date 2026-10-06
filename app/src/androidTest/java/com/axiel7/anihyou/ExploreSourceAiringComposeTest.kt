package com.axiel7.anihyou

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.axiel7.anihyou.core.model.media.exampleBasicMediaDetails
import com.axiel7.anihyou.core.model.media.exampleBasicMediaListEntry
import com.axiel7.anihyou.core.network.fragment.ExploreMedia
import com.axiel7.anihyou.core.network.type.MediaStatus
import com.axiel7.anihyou.core.resources.R
import com.axiel7.anihyou.feature.explore.explore.content.AiringContent
import com.axiel7.anihyou.release.core.api.*
import com.axiel7.anihyou.release.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ExploreSourceAiringComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun tenSourceCardsOutsideAniListPageLoadRealCoversTitlesAndOpenTheCorrectDetails() {
        val coverColor = 0xffc6178e.toInt()
        val cover = File(rule.activity.filesDir, "explore-cover-fixture.png")
        Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(coverColor) }.let { bitmap ->
            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        var metadata by mutableStateOf<Map<Int, ExploreMedia>>(emptyMap())
        var loading by mutableStateOf(true)
        var opened: Int? = null
        var calendarOpened = false
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column {
                    AiringContent(false, remember { mutableStateListOf() }, remember { mutableStateListOf() },
                        providerAiringRows = (1..10).map(::row), providerAiringMedia = metadata,
                        isLoadingProviderAiring = loading, isLoading = false,
                        onLongClickItem = { _, _ -> }, navigateToCalendar = { calendarOpened = true },
                        navigateToMediaDetails = { opened = it })
                }
            }
        }
        val generic = rule.activity.getString(R.string.release_provider_only)
        rule.onNodeWithText(generic).assertDoesNotExist()
        rule.runOnIdle { metadata = (1..10).associateWith { media(it, cover.toURI().toString()) }; loading = false }
        rule.onNodeWithText("Mapped Anime 1").assertIsDisplayed()
        rule.waitUntil(10_000) {
            val bitmap = rule.captureRootBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { it == coverColor } > 1000
        }
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "explore-source-metadata-loaded") {
            rule.onNodeWithText("Mapped Anime 1").assertIsDisplayed()
            rule.onNodeWithText(generic).assertDoesNotExist()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Mapped Anime 10"))
        rule.onNodeWithText("Mapped Anime 10").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(10, opened) }
        rule.onNodeWithText(rule.activity.getString(R.string.airing_soon)).performClick()
        rule.runOnIdle { assertTrue(calendarOpened) }
    }

    @Test fun listFilterUsesEnrichedMembershipAndUnmappedRowsNeverProduceFakeCards() {
        var onList by mutableStateOf(false)
        var opened: Int? = null
        val metadata = mapOf(7 to media(7, listed = true), 8 to media(8))
        rule.setContent {
            MaterialTheme {
                Column {
                    AiringContent(onList, remember { mutableStateListOf() }, remember { mutableStateListOf() },
                        providerAiringRows = listOf(row(7), row(8), row(9), row(null)), providerAiringMedia = metadata,
                        isLoading = false, onLongClickItem = { _, _ -> }, navigateToCalendar = {},
                        navigateToMediaDetails = { opened = it })
                }
            }
        }
        rule.onNodeWithText("Mapped Anime 8").assertIsDisplayed()
        rule.onNodeWithText("Mapped Anime 7").assertDoesNotExist()
        rule.runOnIdle { onList = true }
        rule.onNodeWithText("Mapped Anime 7").assertIsDisplayed()
        rule.onNodeWithText("Mapped Anime 8").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.release_provider_only)).assertDoesNotExist()
        rule.storeVerifiedScreenshot(rule.activity, File(rule.activity.getExternalFilesDir(null), "ep07-ui"),
            "explore-source-list-filter") { rule.onNodeWithText("Mapped Anime 7").assertIsDisplayed() }
        rule.onNodeWithText("Mapped Anime 7").performClick()
        rule.runOnIdle { assertEquals(7, opened) }
    }

    private fun media(id: Int, cover: String? = null, listed: Boolean = false) = ExploreMedia(
        __typename = "Media", id = id, basicMediaDetails = exampleBasicMediaDetails.copy(id = id,
            isAdult = false, title = exampleBasicMediaDetails.title!!.copy(userPreferred = "Mapped Anime $id")),
        status = MediaStatus.RELEASING, coverImage = cover?.let {
            ExploreMedia.CoverImage(__typename = "MediaCoverImage", large = it, color = null)
        }, popularity = 1, bannerImage = null, averageScore = null, genres = emptyList(),
        mediaListEntry = if (listed) ExploreMedia.MediaListEntry(__typename = "MediaList", id = id,
            mediaId = id, basicMediaListEntry = exampleBasicMediaListEntry.copy(mediaId = id)) else null,
        nextAiringEpisode = null,
    )
    private fun row(id: Int?) = ReleaseUiCalendarItem(
        mediaId = id, stream = ReleaseStreamKey(ProviderId("fixture-source"), SourceSeriesKey("series-$id"),
            ReleaseKind.EPISODE, 1, LanguageTrack.DE_DUB), installment = Installment.Episode(23),
        forecastAt = Instant.parse("2026-10-06T15:10:00Z"), confirmed = false,
        authority = ReleaseUiAuthority.VALID, sourceDate = LocalDate.of(2026, 10, 6),
        sourceRoot = "https://example.test", revision = 1,
    )
}

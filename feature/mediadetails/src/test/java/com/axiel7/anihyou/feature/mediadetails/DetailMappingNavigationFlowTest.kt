package com.axiel7.anihyou.feature.mediadetails

import com.axiel7.anihyou.release.core.api.DetailMappingRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DetailMappingNavigationFlowTest {
    @Test
    fun mapsOnceBeforeNavigationAndProgressChangesOnlyReobserveNavigation() = runBlocking {
        val progress = MutableStateFlow(3)
        val events = mutableListOf<String>()
        val request = DetailMappingRequest(mediaId = 42, titles = setOf("Example anime"), format = "TV")

        val values = progress.observeNavigationAfterDetailMapping(
            mediaId = 42,
            request = request,
            ensureDetailMapping = { events += "match:${it.mediaId}" },
            observe = { id, watched ->
                events += "observe:$id:$watched"
                flowOf(watched)
            },
        ).onEach { if (it == 3) progress.value = 4 }
            .take(2)
            .toList()

        assertEquals(listOf(3, 4), values)
        assertEquals(listOf("match:42", "observe:42:3", "observe:42:4"), events)
    }

    @Test
    fun airingMetadataChangeReobservesWithTheSameProgressWithoutRematching() = runBlocking {
        val basis = com.axiel7.anihyou.release.core.navigation.AniListReleaseBasis("RELEASING", 26, 15, 1234L)
        val input = MutableStateFlow(12 to basis)
        var matches = 0
        val values = input.observeNavigationAfterDetailMapping(
            mediaId = 42,
            request = DetailMappingRequest(mediaId = 42, titles = setOf("Example anime")),
            ensureDetailMapping = { matches++ },
            observe = { _, snapshot -> flowOf(snapshot.second.nextEpisode) },
        ).onEach { if (it == 15) input.value = 12 to basis.copy(nextEpisode = 16) }
            .take(2).toList()
        assertEquals(listOf(15, 16), values)
        assertEquals(1, matches)
    }

    @Test
    fun skipsMatchingForNonAnimeAndKeepsNavigationVisibleAfterLookupFailure() = runBlocking {
        var invoked = false
        val mangaNavigation = flowOf(7).observeNavigationAfterDetailMapping(
            mediaId = 43,
            request = null,
            ensureDetailMapping = { invoked = true },
            observe = { id, watched -> flowOf("$id:$watched") },
        ).toList()
        assertFalse(invoked)
        assertEquals(listOf("43:7"), mangaNavigation)

        val afterFailure = flowOf(8).observeNavigationAfterDetailMapping(
            mediaId = 42,
            request = DetailMappingRequest(mediaId = 42, titles = setOf("Example anime")),
            ensureDetailMapping = { error("temporary matcher failure") },
            observe = { id, watched -> flowOf("$id:$watched") },
        ).toList()
        assertEquals(listOf("42:8"), afterFailure)
    }
}

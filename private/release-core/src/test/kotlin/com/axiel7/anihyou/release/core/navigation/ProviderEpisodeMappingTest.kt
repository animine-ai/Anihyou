package com.axiel7.anihyou.release.core.navigation

import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEpisodeMappingTest {
    @Test
    fun `canonical episode three maps explicitly to provider season two episode fifteen`() {
        val segment = segment()

        val coordinate = ProviderEpisodeMapper.coordinate(
            listOf(segment), KEY, MEDIA_ID, BigDecimal("3"), setOf("DE_SUB"),
        )

        assertEquals(BigDecimal("15"), segment.providerEpisode(BigDecimal("3")))
        assertEquals(KEY, coordinate?.key)
        assertEquals(MEDIA_ID, coordinate?.mediaId)
        assertEquals("fixture-series", coordinate?.seriesKey)
        assertEquals(2, coordinate?.sourceSeason)
        assertEquals(BigDecimal("3"), coordinate?.canonicalEpisode)
        assertEquals("15", coordinate?.providerEpisode)
        assertEquals(setOf("DE_SUB"), coordinate?.availableTracks)
    }

    @Test
    fun `segment offset maps in both directions and preserves fractional episodes`() {
        val segment = segment(providerFirst = 15, canonicalFirst = 3, count = 24)

        assertEquals(BigDecimal("20.25"), segment.providerEpisode(BigDecimal("8.25")))
        assertEquals(BigDecimal("4.5"), segment.canonicalEpisode(BigDecimal("16.5")))
        assertEquals("15.5", ProviderEpisodeMapper.coordinate(
            listOf(segment), KEY, MEDIA_ID, BigDecimal("3.5"), setOf("DE_DUB"),
        )?.providerEpisode)
    }

    @Test
    fun `segment intervals exclude values outside their half open bounds`() {
        val segment = segment(providerFirst = 15, canonicalFirst = 3, count = 2)

        assertNull(segment.providerEpisode(BigDecimal("2.99")))
        assertNull(segment.providerEpisode(BigDecimal("5")))
        assertNull(segment.canonicalEpisode(BigDecimal("14.99")))
        assertNull(segment.canonicalEpisode(BigDecimal("17")))
        assertNull(ProviderEpisodeMapper.coordinate(listOf(segment), KEY, MEDIA_ID,
            BigDecimal("5"), setOf("DE_SUB")))
    }

    @Test
    fun `overlapping segments are ambiguous and do not yield a coordinate`() {
        val first = segment(providerFirst = 15, canonicalFirst = 3, count = 12)
        val second = segment(providerFirst = 1, canonicalFirst = 3, count = 1, sourceSeason = 1)

        assertNull(ProviderEpisodeMapper.coordinate(
            listOf(first, second), KEY, MEDIA_ID, BigDecimal("3"), setOf("DE_SUB"),
        ))
    }

    @Test
    fun `mapping reset cannot reuse a removed segment`() {
        val oldSegment = segment(providerFirst = 15, canonicalFirst = 3, count = 8)
        val changedSegment = segment(providerFirst = 1, canonicalFirst = 3, count = 8, sourceSeason = 1)

        assertEquals("15", ProviderEpisodeMapper.coordinate(
            listOf(oldSegment), KEY, MEDIA_ID, BigDecimal("3"), setOf("DE_SUB"),
        )?.providerEpisode)
        assertEquals("1", ProviderEpisodeMapper.coordinate(
            listOf(changedSegment), KEY, MEDIA_ID, BigDecimal("3"), setOf("DE_SUB"),
        )?.providerEpisode)
        assertNull(ProviderEpisodeMapper.coordinate(
            emptyList(), KEY, MEDIA_ID, BigDecimal("3"), setOf("DE_SUB"),
        ))
    }

    @Test
    fun `segments are scoped to exact source and media identity`() {
        val segment = segment()

        assertNull(ProviderEpisodeMapper.coordinate(listOf(segment), OTHER_KEY, MEDIA_ID,
            BigDecimal("3"), setOf("DE_SUB")))
        assertNull(ProviderEpisodeMapper.coordinate(listOf(segment), KEY, MEDIA_ID + 1,
            BigDecimal("3"), setOf("DE_SUB")))
    }

    @Test
    fun `invalid segment bounds are rejected at construction`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProviderEpisodeSegment(KEY, MEDIA_ID, "fixture-series", 2, 9999, 3, 2)
        }

        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    private fun segment(
        providerFirst: Int = 15,
        canonicalFirst: Int = 3,
        count: Int = 8,
        sourceSeason: Int = 2,
    ) = ProviderEpisodeSegment(
        key = KEY,
        mediaId = MEDIA_ID,
        seriesKey = "fixture-series",
        sourceSeason = sourceSeason,
        providerFirst = providerFirst,
        canonicalFirst = canonicalFirst,
        count = count,
    )

    companion object {
        private const val MEDIA_ID = 42
        private val KEY = ExtensionSelectionKey("aniworld-source", "de.aniworld", "fixture-publisher", "aniworld")
        private val OTHER_KEY = ExtensionSelectionKey("other-source", "de.other", "other-publisher", "other-provider")
    }
}

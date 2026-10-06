package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.navigation.ProviderEpisodeSegment
import com.axiel7.anihyou.release.core.source.ExtensionSelectionKey
import org.junit.Assert.*
import org.junit.Test

class CanonicalPresentationInstallmentTest {
    private val source = ExtensionSelectionKey("repo", "extension", "publisher", "provider")
    private val segment = ProviderEpisodeSegment(source, 42, "series", 1, 13, 1, 12)
    private fun map(number: Int, segments: List<ProviderEpisodeSegment> = listOf(segment)) =
        canonicalPresentationInstallment(source, 42, "/anime/stream/series", 1, Installment.Episode(number), segments)
    @Test fun absoluteProviderNumbersBecomeCanonicalProgressPositions() {
        assertEquals(Installment.Episode(1), map(13))
        assertEquals(Installment.Episode(12), map(24))
        assertNull(map(12)); assertNull(map(25))
    }
    @Test fun overlappingSegmentsAreRejectedEvenWhenTheyAgree() {
        assertNull(map(13, listOf(segment, segment)))
    }
    @Test fun anotherSourceOrSeasonCannotApplyAnOffset() {
        assertNull(map(13, listOf(segment.copy(key = source.copy(sourceId = "other")))))
        assertNull(map(13, listOf(segment.copy(sourceSeason = 2))))
        assertNull(map(13, emptyList()))
    }
}

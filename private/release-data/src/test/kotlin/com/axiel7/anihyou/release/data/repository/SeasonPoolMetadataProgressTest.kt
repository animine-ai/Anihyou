package com.axiel7.anihyou.release.data.repository

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SeasonPoolMetadataProgressTest {
    @Test
    fun failedRetryDoesNotExtendPartialCacheTtlOrEraseSuccessfulPageCount() {
        val fetchedAt = Instant.parse("2026-09-10T12:00:00Z")
        val expiresAt = Instant.parse("2026-09-17T12:00:00Z")
        val existing = SeasonPoolCacheMetadata(
            complete = false,
            nextCursor = "3",
            fetchedAt = fetchedAt,
            expiresAt = expiresAt,
            pagesFetched = 2,
        )

        val unchanged = nextSeasonPoolMetadata(
            existing = existing,
            now = Instant.parse("2026-09-12T12:00:00Z"),
            successfulPages = 0,
            complete = false,
            nextCursor = "3",
        )

        assertSame(existing, unchanged)
        assertEquals(expiresAt, unchanged?.expiresAt)
        assertEquals(2, unchanged?.pagesFetched)
    }

    @Test
    fun successfulPartialRetryAdvancesCursorAndAccumulatesSuccessfulPages() {
        val existing = SeasonPoolCacheMetadata(
            complete = false,
            nextCursor = "3",
            fetchedAt = Instant.parse("2026-09-10T12:00:00Z"),
            expiresAt = Instant.parse("2026-09-17T12:00:00Z"),
            pagesFetched = 2,
        )
        val now = Instant.parse("2026-09-12T12:00:00Z")

        val updated = nextSeasonPoolMetadata(
            existing = existing,
            now = now,
            successfulPages = 1,
            complete = false,
            nextCursor = "4",
        ) ?: error("metadata missing")

        assertEquals("4", updated.nextCursor)
        assertEquals(3, updated.pagesFetched)
        assertEquals(now, updated.fetchedAt)
        assertEquals(Instant.parse("2026-09-19T12:00:00Z"), updated.expiresAt)
    }

    @Test
    fun successfulCompletionClearsCursorWithoutLosingCoverageCount() {
        val updated = nextSeasonPoolMetadata(
            existing = null,
            now = Instant.parse("2026-09-12T12:00:00Z"),
            successfulPages = 2,
            complete = true,
            nextCursor = "3",
        ) ?: error("metadata missing")

        assertEquals(true, updated.complete)
        assertNull(updated.nextCursor)
        assertEquals(2, updated.pagesFetched)
    }
}

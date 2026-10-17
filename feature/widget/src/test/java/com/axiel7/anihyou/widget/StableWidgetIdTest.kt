package com.axiel7.anihyou.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableWidgetIdTest {
    /** Glance throws for an id below Long.MIN_VALUE / 2 and the header uses 0: no event key may land on either. */
    @Test
    fun everyKeyGetsAnAllowedNonHeaderId() {
        val reserved = Long.MIN_VALUE / 2
        val keys = (0 until 20_000).map { "aniworld/series-$it/EPISODE/${it % 40}/DE_SUB/episode:$it" } +
            listOf("", "a", "aniworld//an")
        keys.forEach { key ->
            val id = stableWidgetId(key)
            assertTrue("id $id of '$key' is reserved or the header's", id >= reserved && id != 0L)
        }
    }

    @Test
    fun theIdOfAKeyIsStable() {
        assertEquals(stableWidgetId("aniworld/x/EPISODE/1/DE_SUB/episode:1"), stableWidgetId("aniworld/x/EPISODE/1/DE_SUB/episode:1"))
    }
}

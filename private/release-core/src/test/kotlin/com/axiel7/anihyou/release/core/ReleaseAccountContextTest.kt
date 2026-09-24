package com.axiel7.anihyou.release.core

import com.axiel7.anihyou.release.core.api.ReleaseAccountContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseAccountContextTest {
    @Test
    fun knownZeroIsDistinctFromUnknown() {
        val context = ReleaseAccountContext(
            accountId = 42L,
            progressByMediaId = mapOf(7 to 0, 8 to 7),
        )

        assertEquals(0, context.progressFor(7))
        assertEquals(7, context.progressFor(8))
        assertNull(context.progressFor(9))
    }

    @Test
    fun aLargeCompleteLocalReadPreservesEveryKnownValue() {
        val context = ReleaseAccountContext(
            accountId = 42L,
            progressByMediaId = (1..51).associateWith { id -> if (id == 1) 0 else id },
        )

        assertEquals(51, context.progressByMediaId.size)
        assertEquals(0, context.progressFor(1))
        assertEquals(51, context.progressFor(51))
        assertNull(context.progressFor(52))
    }
}

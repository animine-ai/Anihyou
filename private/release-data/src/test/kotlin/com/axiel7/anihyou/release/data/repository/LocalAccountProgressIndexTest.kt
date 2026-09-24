package com.axiel7.anihyou.release.data.repository

import com.axiel7.anihyou.core.domain.repository.LocalAccountProgressIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAccountProgressIndexTest {
    @Test
    fun knownZeroAndPartialRowsRemainDistinct() {
        val index = LocalAccountProgressIndex()
        index.record(userId = 7, progressByMediaId = mapOf(11 to 0, 12 to 7))

        assertEquals(mapOf(11 to 0, 12 to 7), index.read(7, setOf(11, 12)))
        assertNull(index.read(7, setOf(11, 13)))
    }

    @Test
    fun fiftyPlusRowsAreIndexedPerAccountWithoutCrossAccountLeakage() {
        val index = LocalAccountProgressIndex()
        index.record(
            userId = 7,
            progressByMediaId = (1..51).associateWith { id -> if (id == 1) 0 else id },
        )

        assertEquals(51, index.read(7, (1..51).toSet())?.size)
        assertEquals(0, index.read(7, setOf(1))?.get(1))
        assertNull(index.read(8, setOf(1)))
    }

    @Test
    fun mutationInvalidationMakesProgressUnknownUntilFreshLocalDataArrives() {
        val index = LocalAccountProgressIndex()
        index.record(userId = 7, progressByMediaId = mapOf(11 to 7, 12 to 3))
        index.record(userId = 8, progressByMediaId = mapOf(11 to 2))

        index.invalidate(11)

        assertNull(index.read(7, setOf(11)))
        assertNull(index.read(8, setOf(11)))
        assertEquals(mapOf(12 to 3), index.read(7, setOf(12)))

        index.record(userId = 7, progressByMediaId = mapOf(11 to 8))
        assertEquals(mapOf(11 to 8), index.read(7, setOf(11)))
        assertNull(index.read(8, setOf(11)))
    }

    @Test
    fun deleteStyleClearMakesEveryAccountUnknownUntilRepopulated() {
        val index = LocalAccountProgressIndex()
        index.record(userId = 7, progressByMediaId = mapOf(11 to 7, 12 to 3))
        index.record(userId = 8, progressByMediaId = mapOf(11 to 2))

        index.clear()

        assertNull(index.read(7, setOf(11)))
        assertNull(index.read(7, setOf(12)))
        assertNull(index.read(8, setOf(11)))
    }
}

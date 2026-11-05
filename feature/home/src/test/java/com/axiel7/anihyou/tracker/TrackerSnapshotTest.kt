package com.axiel7.anihyou

import com.axiel7.anihyou.core.domain.tracker.TrackerEntry
import com.axiel7.anihyou.core.domain.tracker.TrackerRange
import com.axiel7.anihyou.core.domain.tracker.TrackerSnapshotStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackerSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = TrackerSnapshotStore(temp.root)
    private fun entry(id: Int = 1) = TrackerEntry(id,"Example show","Example show",null,2,12,3,4,1_800_000_000,"CURRENT")

    @Test fun missingCacheIsNotAnEmptyConfirmedList() { assertNull(store().read()) }
    @Test fun incompletePaginationIsNotPublishedAsComplete() {
        val s = store(); s.switchAccount(101); s.recordPage(101,1,true,listOf(entry()))
        assertFalse(s.read()!!.complete)
        s.recordPage(101,2,false,listOf(entry(2)))
        assertTrue(s.read()!!.complete); assertEquals(2,s.read()!!.entries.size)
    }
    @Test fun completedEmptyListIsDistinguishedFromMissingData() {
        val s=store();s.switchAccount(101);s.recordPage(101,1,false,emptyList())
        assertTrue(s.read()!!.complete);assertTrue(s.read()!!.entries.isEmpty())
    }
    @Test fun lateResultsForAPreviousAccountAreRejected() {
        val s=store();s.switchAccount(101);s.recordPage(101,1,false,listOf(entry()));s.switchAccount(202)
        assertFalse(s.recordPage(101,1,false,listOf(entry())))
        assertEquals(202,s.read()!!.accountId);assertFalse(s.read()!!.complete);assertTrue(s.read()!!.entries.isEmpty())
    }
    @Test fun signOutCannotBeUndoneByAnOldBootstrapOrResponse() {
        val s=store();s.switchAccount(101);s.switchAccount(null);s.initializeIfMissing(101)
        assertNull(s.read()!!.accountId);assertFalse(s.recordPage(101,1,false,listOf(entry())))
    }
    @Test fun anUpdatedFirstPageInvalidatesTheOldRemainingPages() {
        val s=store();s.switchAccount(101);s.recordPage(101,1,true,listOf(entry()));s.recordPage(101,2,false,listOf(entry(2)))
        s.recordPage(101,1,true,listOf(entry(3)));assertFalse(s.read()!!.complete)
        assertEquals(listOf(3),s.read()!!.entries.map { it.mediaId })
    }
    @Test fun anotherInstanceReadsTheSamePublishedSnapshot() {
        val s=store();s.switchAccount(101);s.recordPage(101,1,false,listOf(entry()),1234)
        assertEquals(1234L,store().read()!!.cachedAtMillis);assertEquals(entry(),store().read()!!.entries.single())
    }
    @Test fun reloadingTheSameAccountDoesNotClearItsCache() {
        val s=store();s.switchAccount(101);s.recordPage(101,1,false,listOf(entry()));s.switchAccount(101)
        assertTrue(s.read()!!.complete)
    }
    @Test fun aRangeIsHalfOpenAndCannotRequestUnboundedData() {
        val r=TrackerRange.parse("100","200")!!
        assertTrue(r.contains(100));assertFalse(r.contains(200))
        assertNull(TrackerRange.parse("-1","200"));assertNull(TrackerRange.parse("0","999999999999"))
        assertNull(TrackerRange.parse("bad","200"));assertNull(TrackerRange.parse("200","100"))
    }
}

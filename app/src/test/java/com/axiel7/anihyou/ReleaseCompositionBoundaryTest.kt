package com.axiel7.anihyou

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseCompositionBoundaryTest {
    @Test
    fun appDoesNotOwnReleaseImplementationComposition() {
        val source = File("src/main/java/com/axiel7/anihyou/App.kt").readText()
        assertFalse(source.contains("com.axiel7.anihyou.release.data"))
        assertFalse(source.contains("ReleaseDatabase"))
        assertFalse(source.contains("Room.databaseBuilder"))
        assertFalse(source.contains("ReleaseSyncCoordinator"))
        assertFalse(source.contains("releaseDataModule"))
    }
}

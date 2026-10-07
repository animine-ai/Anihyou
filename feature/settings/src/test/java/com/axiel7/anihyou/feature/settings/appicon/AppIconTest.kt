package com.axiel7.anihyou.feature.settings.appicon

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconTest {
    private val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    private val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    private val unset = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

    @Test fun aFreshInstallShowsTheDefaultIconAsTheManifestSaysIt() {
        // Nothing was ever switched: every state is "default", and only the default entry is on in the manifest.
        assertEquals(AppIcon.DEFAULT, AppIcon.current { unset })
    }

    @Test fun anEntryTheUserEnabledIsTheCurrentIcon() {
        val states = mapOf(AppIcon.KIYORI to disabled, AppIcon.ARCTIC to enabled)
        assertEquals(AppIcon.ARCTIC, AppIcon.current { states[it] ?: unset })
    }

    @Test fun anEntryThatWasNeverSwitchedIsOffEvenWhenTheDefaultOneIsDisabled() {
        val states = mapOf(AppIcon.KIYORI to disabled, AppIcon.CLASSIC to enabled)
        assertEquals(AppIcon.CLASSIC, AppIcon.current { states[it] ?: unset })
    }

    @Test fun noEntryOnAtAllFallsBackToTheDefault() {
        assertEquals(AppIcon.DEFAULT, AppIcon.current { disabled })
    }

    @Test fun switchingEnablesTheNewEntryBeforeItDisablesTheOthers() {
        AppIcon.entries.forEach { target ->
            val plan = AppIcon.switchPlan(target)
            assertEquals(target to true, plan.first())
            assertEquals(AppIcon.entries.size, plan.size)
            assertEquals(AppIcon.entries.toSet(), plan.map { it.first }.toSet())
            assertTrue(plan.drop(1).none { it.second })
        }
    }

    @Test fun everyIconHasItsOwnEntryName() {
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.className }.toSet().size)
        assertTrue(AppIcon.entries.all { it.className.startsWith("com.axiel7.anihyou.launcher.") })
    }
}

package com.axiel7.anihyou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniWorldShadowDebugActivationTest {
    @Test
    fun normalDebugBuildWithoutPropertyDoesNotActivateShadow() {
        assertFalse(BuildConfig.ANIWORLD_SHADOW_CANARY)
        assertFalse(AniWorldShadowDebugActivation.enabledForInternalTest)
    }

    @Test
    fun explicitPropertyActivatesOnlyDebugBuilds() {
        assertTrue(AniWorldShadowDebugActivation.isEnabled(debugBuild = true, canaryProperty = true))
        assertFalse(AniWorldShadowDebugActivation.isEnabled(debugBuild = true, canaryProperty = false))
        assertFalse(AniWorldShadowDebugActivation.isEnabled(debugBuild = false, canaryProperty = true))
        assertFalse(AniWorldShadowDebugActivation.isEnabled(debugBuild = false, canaryProperty = false))
    }
}


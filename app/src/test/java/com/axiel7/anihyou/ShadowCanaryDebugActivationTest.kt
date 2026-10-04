package com.axiel7.anihyou

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowCanaryDebugActivationTest {
    @Test
    fun normalDebugBuildWithoutPropertyDoesNotActivateShadow() {
        assertFalse(BuildConfig.ANIWORLD_SHADOW_CANARY)
        assertFalse(ShadowCanaryDebugActivation.enabledForInternalTest)
    }

    @Test
    fun explicitPropertyActivatesOnlyDebugBuilds() {
        assertTrue(ShadowCanaryDebugActivation.isEnabled(debugBuild = true, canaryProperty = true))
        assertFalse(ShadowCanaryDebugActivation.isEnabled(debugBuild = true, canaryProperty = false))
        assertFalse(ShadowCanaryDebugActivation.isEnabled(debugBuild = false, canaryProperty = true))
        assertFalse(ShadowCanaryDebugActivation.isEnabled(debugBuild = false, canaryProperty = false))
    }
}


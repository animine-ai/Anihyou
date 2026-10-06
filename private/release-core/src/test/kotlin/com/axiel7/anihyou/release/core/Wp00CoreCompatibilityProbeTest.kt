package com.axiel7.anihyou.release.core

import org.junit.Assert.assertEquals
import org.junit.Test

class Wp00CoreCompatibilityProbeTest {
    @Test
    fun pureJvmProbeRoundTripsValue() {
        assertEquals("phase0", Wp00CoreCompatibilityProbe.echo("phase0"))
    }
}

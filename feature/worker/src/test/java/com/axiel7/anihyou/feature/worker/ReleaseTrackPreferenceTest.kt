package com.axiel7.anihyou.feature.worker

import com.axiel7.anihyou.release.core.api.ReleaseGermanTrack
import com.axiel7.anihyou.release.core.model.LanguageTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseTrackPreferenceTest {
    @Test
    fun germanPreferenceMapsToTheSameTypedDeliveryTrack() {
        assertEquals(LanguageTrack.DE_SUB, ReleaseGermanTrack.DE_SUB.toLanguageTrack())
        assertEquals(LanguageTrack.DE_DUB, ReleaseGermanTrack.DE_DUB.toLanguageTrack())
    }
}

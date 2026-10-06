package com.axiel7.anihyou.release.core.source

import org.junit.Assert.*
import org.junit.Test

class ExtensionPreferencesTest {
    private val key = ExtensionSelectionKey("source", "example.multi", "publisher", "provider")

    @Test fun freshDefaultsFollowExtensionLanguagesAndNeverGuessUnknown() {
        val supported = setOf("EN_SUB", "JA_DUB", "EN_DUB", "UNKNOWN")
        val preferences = ExtensionProductPolicy().preferencesFor(key, supported)
        assertEquals(setOf("EN_SUB", "JA_DUB", "EN_DUB"), preferences.enabledTracks)
        assertEquals(listOf("en", "ja"), preferences.languageOrder)
        assertEquals(listOf("EN_SUB", "EN_DUB", "JA_DUB"), preferences.orderedTracks(supported))
        assertFalse(preferences.enabledTracks.any { it.startsWith("DE_") })
    }

    @Test fun savedExclusionsSurviveNewSupportedTracksAndOtherExtensions() {
        val saved = ExtensionPreferences(setOf("EN_SUB"), listOf("EN_SUB"), listOf("en"))
        val policy = ExtensionProductPolicy(preferences = mapOf(key to saved))
        assertEquals(saved, policy.preferencesFor(key, setOf("EN_SUB", "EN_DUB", "JA_DUB")))
        val other = key.copy(extensionId = "example.other")
        assertEquals(setOf("JA_DUB"), policy.preferencesFor(other, setOf("JA_DUB")).enabledTracks)
        val excluded = policy.copy(preferences = mapOf(key to saved.copy(enabledTracks = emptySet())))
        assertTrue(excluded.preferencesFor(key, setOf("EN_SUB", "EN_DUB")).orderedTracks(setOf("EN_SUB", "EN_DUB")).isEmpty())
    }
}

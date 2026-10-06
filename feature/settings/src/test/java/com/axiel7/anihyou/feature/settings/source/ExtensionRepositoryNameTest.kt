package com.axiel7.anihyou.feature.settings.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionRepositoryNameTest {
    @Test
    fun bothCurrentAndLegacyCatalogsHaveTheSameReadableName() {
        val base = "https://raw.githubusercontent.com/animine-ai/release-extentions/"
        assertEquals("AniHyou Extensions", repositoryDisplayName(base + "catalog"))
        assertEquals("AniHyou Extensions", repositoryDisplayName(base + "catalog/"))
        assertEquals("AniHyou Extensions", repositoryDisplayName(base + "private-preview-catalog/catalog"))
    }

    @Test
    fun unrelatedPathsAndLookalikesKeepTheirActualHost() {
        val cases = listOf(
            "https://raw.githubusercontent.com/someone/release-extentions/catalog" to "raw.githubusercontent.com",
            "https://raw.githubusercontent.com/animine-ai/release-extentions/catalog/other" to "raw.githubusercontent.com",
            "https://raw.githubusercontent.com.evil.test/animine-ai/release-extentions/catalog" to "raw.githubusercontent.com.evil.test",
            "https://raw.githubusercontent.com@evil.test/animine-ai/release-extentions/catalog" to "evil.test",
            "https://example.org/extensions" to "example.org",
        )
        cases.forEach { (url, expected) -> assertEquals(expected, repositoryDisplayName(url)) }
        assertNull(repositoryDisplayName("invalid url"))
    }
}

package com.axiel7.anihyou.release.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionSourceRegistryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `source address canonicalization accepts only unambiguous HTTPS DNS paths`() {
        val parsed = NormalizedExtensionSource.parse(" HTTPS://Example.Org/repository/ ")

        assertEquals("https://example.org/repository", parsed.url)
        assertEquals("https://example.org", parsed.origin)
        assertEquals(
            NormalizedExtensionSource.parse("https://example.org/repository"),
            NormalizedExtensionSource.parse("https://example.org:443/repository"),
        )

        listOf(
            "http://example.org/repository",
            "https://user@example.org/repository",
            "https://example.org/repository?mode=1",
            "https://example.org/repository#fragment",
            "https://example.org:444/repository",
            "https://127.0.0.1/repository",
            "https://[::1]/repository",
            "https://exämple.org/repository",
            "https://example.org/a/../repository",
            "https://example.org/a/%2e%2e/repository",
            "https://example.org//repository",
            "https://example.org/repository\\other",
            "https://example.org/repository with-space",
            "https://${"a".repeat(63)}.${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(62)}/repository",
        ).forEach { rejected ->
            assertInvalidAddress(rejected)
        }
    }

    @Test
    fun `canonical duplicate is stable across reopen and removal is a reusable tombstone`() {
        val directory = temporaryFolder.newFolder("registry")
        val firstRegistry = ExtensionSourceRegistry(directory)
        val first = firstRegistry.add(NormalizedExtensionSource.parse(URL))!!
        assertTrue(first.second)

        val reopened = ExtensionSourceRegistry(directory)
        val duplicate = reopened.add(NormalizedExtensionSource.parse("HTTPS://PACKAGES.EXAMPLE.ORG/catalog/"))!!
        assertFalse(duplicate.second)
        assertEquals(first.first.id, duplicate.first.id)

        reopened.update(first.first.id) { it.copy(enabled = false, epoch = it.epoch + 1) }
        val disabledAfterReopen = ExtensionSourceRegistry(directory).find(first.first.id)
        assertNotNull(disabledAfterReopen)
        assertFalse(disabledAfterReopen!!.enabled)
        assertEquals(1L, disabledAfterReopen.epoch)

        ExtensionSourceRegistry(directory).update(first.first.id) {
            it.copy(enabled = false, removed = true, epoch = it.epoch + 1)
        }
        val tombstoneRegistry = ExtensionSourceRegistry(directory)
        assertNotNull(tombstoneRegistry.find(first.first.id))
        assertTrue(tombstoneRegistry.find(first.first.id)!!.removed)
        assertNull(tombstoneRegistry.all().singleOrNull { !it.removed })

        val readded = tombstoneRegistry.add(NormalizedExtensionSource.parse(URL))!!
        assertTrue(readded.second)
        assertEquals(first.first.id, readded.first.id)
        assertTrue(readded.first.enabled)
        assertFalse(readded.first.removed)
        assertEquals(3L, readded.first.epoch)
    }

    @Test
    fun `registry rejects duplicate normalized URLs while retaining other records`() {
        val directory = temporaryFolder.newFolder("multiple")
        val registry = ExtensionSourceRegistry(directory)
        val first = registry.add(NormalizedExtensionSource.parse(URL))!!.first
        val second = registry.add(NormalizedExtensionSource.parse("https://mirror.example.org/catalog"))!!.first

        assertEquals(2, ExtensionSourceRegistry(directory).all().size)
        assertEquals(first.id, ExtensionSourceRegistry(directory).find(first.id)!!.id)
        assertEquals(second.id, ExtensionSourceRegistry(directory).find(second.id)!!.id)
        val duplicate = ExtensionSourceRegistry(directory).add(NormalizedExtensionSource.parse(URL))!!
        assertFalse(duplicate.second)
        assertEquals(first.id, duplicate.first.id)
    }

    private fun assertInvalidAddress(value: String) {
        try {
            NormalizedExtensionSource.parse(value)
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected invalid source address: $value")
    }

    private companion object {
        const val URL = "https://packages.example.org/catalog"
    }
}

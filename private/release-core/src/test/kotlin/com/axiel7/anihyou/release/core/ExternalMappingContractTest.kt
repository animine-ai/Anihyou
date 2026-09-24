package com.axiel7.anihyou.release.core

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingCandidate
import com.axiel7.anihyou.release.core.model.ExternalMappingResolution
import com.axiel7.anihyou.release.core.model.ExternalMappingPrecedence
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalMappingContractTest {
    private val now = Instant.parse("2026-09-21T12:00:00Z")
    private val site = AniWorldSiteIdentifier(
        slug = "naruto",
        normalizedTitle = "naruto",
        sourceType = AniWorldIdentitySourceType.URL,
        firstSeenAt = now,
        lastValidatedAt = now,
    )
    private val subject = AniWorldMappingSubject.Season(site, navigationSeason = 1)

    @Test
    fun activeMappingRequiresAnExplicitExternalId() {
        assertThrows(IllegalArgumentException::class.java) {
            ExternalMapping(
                subject = subject,
                externalProvider = ExternalProvider.MYANIMELIST,
                externalId = null,
                mappingSource = MappingSource.PERSISTED,
                confidence = MappingConfidence.EXACT,
                createdAt = now,
                validatedAt = now,
                status = MappingStatus.ACTIVE,
            )
        }
    }

    @Test
    fun ambiguousResolutionDoesNotCreateAnExternalBinding() {
        val result = ExternalMappingResolution.Ambiguous(
            subject = subject,
            candidates = listOf(
                ExternalMappingCandidate(
                    subject = subject,
                    externalProvider = ExternalProvider.MYANIMELIST,
                    externalId = "20",
                    mappingSource = MappingSource.FALLBACK,
                    confidence = MappingConfidence.AMBIGUOUS,
                ),
                ExternalMappingCandidate(
                    subject = subject,
                    externalProvider = ExternalProvider.MYANIMELIST,
                    externalId = "21",
                    mappingSource = MappingSource.FALLBACK,
                    confidence = MappingConfidence.AMBIGUOUS,
                ),
            ),
            diagnostic = "multiple exact-title candidates",
        )

        assertEquals(MappingStatus.AMBIGUOUS, MappingStatus.AMBIGUOUS)
        assertEquals(2, result.candidates.size)
        val unresolvedBinding = ExternalMapping(
            subject = subject,
            externalProvider = ExternalProvider.MYANIMELIST,
            externalId = null,
            mappingSource = MappingSource.FALLBACK,
            confidence = MappingConfidence.AMBIGUOUS,
            createdAt = now,
            validatedAt = null,
            status = MappingStatus.AMBIGUOUS,
        )
        assertNull(unresolvedBinding.externalId)
    }

    @Test
    fun siteIdentityDoesNotDependOnExternalIds() {
        assertEquals("aniworld:/anime/stream/naruto", site.stableKey)
        assertEquals("aniworld", site.provider)
        assertEquals("naruto", site.canonicalId)
    }

    @Test
    fun manualAndPersistedMappingsCannotBePoisonedByMALSync() {
        val manual = active(MappingSource.MANUAL, "manual")
        val persisted = active(MappingSource.PERSISTED, "persisted")
        val malsync = active(MappingSource.MALSYNC, "malsync")

        assertFalse(ExternalMappingPrecedence.canReplace(manual, malsync))
        assertFalse(ExternalMappingPrecedence.canReplace(persisted, malsync))
        assertTrue(ExternalMappingPrecedence.canReplace(null, malsync))
    }

    @Test
    fun unresolvedIncomingMappingCannotRemoveAnExistingBinding() {
        val existing = active(MappingSource.MALSYNC, "42")
        val unresolved = ExternalMapping(
            subject = subject,
            externalProvider = ExternalProvider.MAL,
            externalId = null,
            mappingSource = MappingSource.FALLBACK,
            confidence = MappingConfidence.NONE,
            createdAt = now,
            validatedAt = null,
            status = MappingStatus.UNRESOLVED,
        )

        assertFalse(ExternalMappingPrecedence.canReplace(existing, unresolved))
    }

    private fun active(source: MappingSource, id: String) = ExternalMapping(
        subject = subject,
        externalProvider = ExternalProvider.MAL,
        externalId = id,
        mappingSource = source,
        confidence = MappingConfidence.EXACT,
        createdAt = now,
        validatedAt = now,
        status = MappingStatus.ACTIVE,
    )
}

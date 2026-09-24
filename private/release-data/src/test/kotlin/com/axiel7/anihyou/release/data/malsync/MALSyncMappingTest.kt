package com.axiel7.anihyou.release.data.malsync

import com.axiel7.anihyou.release.core.api.ExternalMappingRepository
import com.axiel7.anihyou.release.core.api.MappingAttemptRepository
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingAttempt
import com.axiel7.anihyou.release.core.model.ExternalMappingResolution
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.MappingAttemptResultKind
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MALSyncMappingTest {
    private val now = Instant.parse("2026-09-22T12:00:00Z")
    private val site = AniWorldSiteIdentifier(
        slug = "alpha",
        normalizedTitle = "alpha",
        sourceType = AniWorldIdentitySourceType.PAGE,
        firstSeenAt = now,
        lastValidatedAt = now,
    )
    private val season = AniWorldMappingSubject.Season(site, 2)
    private val film = AniWorldMappingSubject.Film(site, 1)
    private val provider = ExternalProvider.MAL

    @Test
    fun seasonIdentifierUsesNavigationSeasonWithoutDefaultingToOne() {
        val result = MALSyncAniWorldIdentifierFactory.create(season)

        val identifier = (result as MALSyncIdentifierResult.Supported).identifier
        assertEquals("alpha?s=staffel-2", identifier.value)
    }

    @Test
    fun filmIdentifierRequiresDirectPageTitleEvidence() {
        val unsupported = MALSyncAniWorldIdentifierFactory.create(film)
        assertTrue(unsupported is MALSyncIdentifierResult.Unsupported)

        val supported = MALSyncAniWorldIdentifierFactory.create(
            subject = film,
            filmTitleEvidence = MALSyncFilmTitleEvidence(
                subject = film,
                title = "Mein Film [Deutsch]",
                sourceUrl = "https://aniworld.to/anime/stream/alpha/filme/film-1",
                sourceHash = "hash",
                parserVersion = "test-v1",
            ),
        )
        assertEquals(
            "alpha?m=mein-film",
            (supported as MALSyncIdentifierResult.Supported).identifier.value,
        )
    }

    @Test
    fun filmEvidenceMustMatchRequestedRouteAndFilmNumber() {
        val wrongFilm = film.copy(filmNumber = 2)

        val result = MALSyncAniWorldIdentifierFactory.create(
            subject = film,
            filmTitleEvidence = MALSyncFilmTitleEvidence(
                subject = wrongFilm,
                title = "Mein Film [Deutsch]",
                sourceUrl = "https://aniworld.to/anime/stream/alpha/filme/film-2",
                sourceHash = "hash",
                parserVersion = "test-v1",
            ),
        )

        assertTrue(result is MALSyncIdentifierResult.Unsupported)
    }

    @Test
    fun filmTitleUsesOnlyMALSyncBracketAndSpaceSemantics() {
        fun identifier(title: String): String {
            val result = MALSyncAniWorldIdentifierFactory.create(
                subject = film,
                filmTitleEvidence = MALSyncFilmTitleEvidence(
                    subject = film,
                    title = title,
                    sourceUrl = "https://aniworld.to/anime/stream/alpha/filme/film-1",
                    sourceHash = "hash",
                    parserVersion = "test-v1",
                ),
            )
            return (result as MALSyncIdentifierResult.Supported).identifier.value
        }

        assertEquals("alpha?m=mein-film!", identifier("  Mein Film!  "))
        assertEquals("alpha?m=mein--film", identifier("Mein  Film"))
        assertEquals("alpha?m=mein-film", identifier("Mein Film [Deutsch]"))
        assertEquals("alpha?m=mein-[deutsch-sub]", identifier("Mein [Deutsch Sub]"))
        assertEquals("alpha?m=über-film", identifier("Über Film"))
    }

    @Test
    fun unsupportedCapabilityDoesNotInvokeClientAndReturnsUnmapped() = runBlocking {
        val calls = AtomicInteger()
        val attempts = InMemoryAttempts()
        val client = MALSyncMappingClient {
            calls.incrementAndGet()
            MALSyncMappingLookup.Exact(provider, "99", "test")
        }
        val resolver = CachedExternalMappingResolver(
            mappings = InMemoryMappings(),
            attempts = attempts,
            client = client,
            clock = fixedClock(),
        )

        val result = resolver.resolve(season, provider)

        assertTrue(result is ExternalMappingResolution.Unmapped)
        assertEquals(0, calls.get())
        assertEquals(MappingAttemptResultKind.UNSUPPORTED, attempts.rows.single().resultKind)
    }

    @Test
    fun activePersistedCacheHitSkipsNetwork() = runBlocking {
        val mappings = InMemoryMappings()
        val existing = mapping(source = MappingSource.PERSISTED, status = MappingStatus.ACTIVE, id = "42")
        mappings.put(existing)
        val calls = AtomicInteger()
        val resolver = CachedExternalMappingResolver(
            mappings = mappings,
            client = MALSyncMappingClient {
                calls.incrementAndGet()
                MALSyncMappingLookup.Failure("must not be called")
            },
            clock = fixedClock(),
        )

        val result = resolver.resolve(season, provider)

        assertEquals(existing, (result as ExternalMappingResolution.Mapped).mapping)
        assertEquals(0, calls.get())
    }

    @Test
    fun activeMappingAtStaleAtIsRevalidatedAndUnavailableCapabilityRetainsIt() = runBlocking {
        val mappings = InMemoryMappings()
        val existing = mapping(
            source = MappingSource.MALSYNC,
            status = MappingStatus.ACTIVE,
            id = "42",
        ).copy(staleAt = now)
        mappings.put(existing)
        val attempts = InMemoryAttempts()

        val result = CachedExternalMappingResolver(
            mappings = mappings,
            attempts = attempts,
            client = MALSyncMappingClient { error("client must not be called when capability is unavailable") },
            clock = fixedClock(),
        ).resolve(season, provider)

        assertEquals(existing, (result as ExternalMappingResolution.Mapped).mapping)
        assertEquals(MappingAttemptResultKind.UNSUPPORTED, attempts.rows.single().resultKind)
        assertTrue(attempts.rows.single().diagnostic.contains("revalidation unavailable"))
    }

    @Test
    fun rejectedWriteRereadsConcurrentMappingAndNeverReturnsUnpersistedIncoming() = runBlocking {
        val mappings = RejectingWriteMappings(
            concurrent = mapping(MappingSource.MANUAL, MappingStatus.ACTIVE, "manual"),
        )
        val attempts = InMemoryAttempts()
        val result = CachedExternalMappingResolver(
            mappings = mappings,
            attempts = attempts,
            capability = availableCapability(),
            client = MALSyncMappingClient { MALSyncMappingLookup.Exact(provider, "incoming", "test") },
            clock = fixedClock(),
        ).resolve(season, provider)

        assertEquals("manual", (result as ExternalMappingResolution.Mapped).mapping.externalId)
        assertEquals(MappingAttemptResultKind.PERSISTENCE_REJECTED, attempts.rows.single().resultKind)
    }

    @Test
    fun rejectedWriteWithoutEffectiveMappingFailsClosed() = runBlocking {
        val mappings = RejectingWriteMappings()
        val attempts = InMemoryAttempts()
        val result = CachedExternalMappingResolver(
            mappings = mappings,
            attempts = attempts,
            capability = availableCapability(),
            client = MALSyncMappingClient { MALSyncMappingLookup.Exact(provider, "incoming", "test") },
            clock = fixedClock(),
        ).resolve(season, provider)

        assertTrue(result is ExternalMappingResolution.Unmapped)
        assertNull(mappings.find(season, provider))
        assertEquals(MappingAttemptResultKind.PERSISTENCE_REJECTED, attempts.rows.single().resultKind)
    }

    @Test
    fun malformedAmbiguityRecordsFinalMalformedAttempt() = runBlocking {
        val attempts = InMemoryAttempts()
        val result = CachedExternalMappingResolver(
            mappings = InMemoryMappings(),
            attempts = attempts,
            capability = availableCapability(),
            client = MALSyncMappingClient {
                MALSyncMappingLookup.Ambiguous(
                    candidates = listOf(candidate("1"), candidate("1")),
                    diagnostic = "duplicate candidates",
                )
            },
            clock = fixedClock(),
        ).resolve(season, provider)

        assertTrue(result is ExternalMappingResolution.Unmapped)
        assertEquals(MappingAttemptResultKind.MALFORMED_RESPONSE, attempts.rows.last().resultKind)
    }

    @Test
    fun sameInstantAttemptsRemainAppendOnly() = runBlocking {
        val attempts = InMemoryAttempts()
        val first = ExternalMappingAttempt(
            subject = season,
            externalProvider = provider,
            source = MappingSource.MALSYNC,
            attemptedAt = now,
            resultKind = MappingAttemptResultKind.AMBIGUOUS,
            diagnostic = "first",
        )
        val second = first.copy(
            resultKind = MappingAttemptResultKind.MALFORMED_RESPONSE,
            diagnostic = "second",
        )

        assertTrue(attempts.append(first))
        assertTrue(attempts.append(second))
        assertEquals(2, attempts.rows.size)
        assertEquals(MappingAttemptResultKind.MALFORMED_RESPONSE, attempts.latest(season, provider)?.resultKind)
    }

    @Test
    fun networkFailureKeepsExistingMappingObservable() = runBlocking {
        val mappings = InMemoryMappings()
        val existing = mapping(source = MappingSource.MALSYNC, status = MappingStatus.STALE, id = "42")
        mappings.put(existing)
        val available = availableCapability()
        val resolver = CachedExternalMappingResolver(
            mappings = mappings,
            capability = available,
            client = MALSyncMappingClient { MALSyncMappingLookup.Failure("timeout") },
            clock = fixedClock(),
        )

        val result = resolver.resolve(season, provider)

        assertEquals(existing, (result as ExternalMappingResolution.Mapped).mapping)
        assertEquals(existing, mappings.find(season, provider))
    }

    @Test
    fun ambiguousLookupNeverCreatesExternalBinding() = runBlocking {
        val attempts = InMemoryAttempts()
        val mappings = InMemoryMappings()
        val resolver = CachedExternalMappingResolver(
            mappings = mappings,
            attempts = attempts,
            capability = availableCapability(),
            client = MALSyncMappingClient {
                MALSyncMappingLookup.Ambiguous(
                    candidates = listOf(
                        candidate("1"),
                        candidate("2"),
                    ),
                    diagnostic = "two exact candidates",
                )
            },
            clock = fixedClock(),
        )

        val result = resolver.resolve(season, provider)

        assertTrue(result is ExternalMappingResolution.Ambiguous)
        assertEquals(2, (result as ExternalMappingResolution.Ambiguous).candidates.size)
        assertNull(mappings.find(season, provider))
        assertEquals(MappingAttemptResultKind.AMBIGUOUS, attempts.rows.single().resultKind)
    }

    @Test
    fun mappingAdapterTypesCannotBecomeReleaseAuthority() {
        assertTrue(!ReleaseEvidence::class.java.isAssignableFrom(MALSyncIdentifier::class.java))
        assertTrue(!ReleaseDecision::class.java.isAssignableFrom(MALSyncIdentifier::class.java))
        assertTrue(!ReleaseEvidence::class.java.isAssignableFrom(MALSyncMappingLookup::class.java))
        assertTrue(!ReleaseDecision::class.java.isAssignableFrom(MALSyncMappingLookup::class.java))
    }

    @Test
    fun candidateForAnotherSubjectIsRejectedWithoutBinding() = runBlocking {
        val mappings = InMemoryMappings()
        val resolver = CachedExternalMappingResolver(
            mappings = mappings,
            capability = availableCapability(),
            client = MALSyncMappingClient {
                MALSyncMappingLookup.Ambiguous(
                    candidates = listOf(
                        candidate("1", AniWorldMappingSubject.Season(site, 3)),
                        candidate("2", AniWorldMappingSubject.Season(site, 3)),
                    ),
                    diagnostic = "wrong subject",
                )
            },
            clock = fixedClock(),
        )

        val result = resolver.resolve(season, provider)

        assertTrue(result is ExternalMappingResolution.Unmapped)
        assertNull(mappings.find(season, provider))
    }

    private fun mapping(
        source: MappingSource,
        status: MappingStatus,
        id: String?,
    ): ExternalMapping = ExternalMapping(
        subject = season,
        externalProvider = provider,
        externalId = id,
        mappingSource = source,
        confidence = if (id == null) MappingConfidence.NONE else MappingConfidence.EXACT,
        createdAt = now,
        validatedAt = now,
        status = status,
    )

    private fun candidate(
        id: String,
        candidateSubject: AniWorldMappingSubject = season,
    ) = com.axiel7.anihyou.release.core.model.ExternalMappingCandidate(
        subject = candidateSubject,
        externalProvider = provider,
        externalId = id,
        mappingSource = MappingSource.MALSYNC,
        confidence = MappingConfidence.EXACT,
    )

    private fun availableCapability() = MALSyncCapabilityAssessment(
        status = MALSyncCapabilityStatus.AVAILABLE,
        inspectedAdapterRevision = "test",
        inspectedDocumentationRevision = "test",
        diagnostic = "available in test",
    )

    private fun fixedClock(): Clock = Clock.fixed(now, ZoneOffset.UTC)

    private class InMemoryMappings : ExternalMappingRepository {
        private val values = linkedMapOf<Pair<String, ExternalProvider>, ExternalMapping>()

        override suspend fun find(
            subject: AniWorldMappingSubject,
            externalProvider: ExternalProvider,
        ): ExternalMapping? = values[subject.stableKey to externalProvider]

        override suspend fun put(mapping: ExternalMapping): Boolean {
            values[mapping.subject.stableKey to mapping.externalProvider] = mapping
            return true
        }

        override suspend fun remove(
            subject: AniWorldMappingSubject,
            externalProvider: ExternalProvider,
        ): Boolean = values.remove(subject.stableKey to externalProvider) != null
    }

    private class RejectingWriteMappings(
        private val concurrent: ExternalMapping? = null,
    ) : ExternalMappingRepository {
        private var value: ExternalMapping? = null
        private var findCount = 0

        override suspend fun find(
            subject: AniWorldMappingSubject,
            externalProvider: ExternalProvider,
        ): ExternalMapping? {
            findCount++
            if (findCount > 1 && value == null && concurrent != null) value = concurrent
            return value
        }

        override suspend fun put(mapping: ExternalMapping): Boolean = false

        override suspend fun remove(
            subject: AniWorldMappingSubject,
            externalProvider: ExternalProvider,
        ): Boolean = false
    }

    private class InMemoryAttempts : MappingAttemptRepository {
        val rows = mutableListOf<ExternalMappingAttempt>()

        override suspend fun append(attempt: ExternalMappingAttempt): Boolean = rows.add(attempt)

        override suspend fun latest(
            subject: AniWorldMappingSubject,
            externalProvider: ExternalProvider,
        ): ExternalMappingAttempt? = rows.lastOrNull {
            it.subject == subject && it.externalProvider == externalProvider
        }
    }
}

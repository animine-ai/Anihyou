package com.axiel7.anihyou.release.data.malsync

import com.axiel7.anihyou.release.core.api.ExternalMappingRepository
import com.axiel7.anihyou.release.core.api.ExternalMappingResolver
import com.axiel7.anihyou.release.core.api.MappingAttemptRepository
import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.ExternalMapping
import com.axiel7.anihyou.release.core.model.ExternalMappingAttempt
import com.axiel7.anihyou.release.core.model.ExternalMappingPrecedence
import com.axiel7.anihyou.release.core.model.ExternalMappingResolution
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingAttemptResultKind
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource
import com.axiel7.anihyou.release.core.model.MappingStatus
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Cache-first mapping resolver. It never emits ReleaseEvidence or a release
 * decision and never removes an existing mapping after an adapter failure.
 */
class CachedExternalMappingResolver(
    private val mappings: ExternalMappingRepository,
    private val attempts: MappingAttemptRepository? = null,
    private val capability: MALSyncCapabilityAssessment = MALSyncCapabilities.ANIWORLD,
    private val client: MALSyncMappingClient = UnsupportedMALSyncMappingClient(capability),
    private val clock: Clock = Clock.systemUTC(),
    private val filmTitleEvidence: suspend (AniWorldMappingSubject) -> MALSyncFilmTitleEvidence? = { null },
) : ExternalMappingResolver {
    override suspend fun resolve(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
    ): ExternalMappingResolution {
        val now = clock.instant()
        val existing = mappings.find(subject, externalProvider)
        if (existing != null && existing.status == MappingStatus.ACTIVE &&
            !requiresRevalidation(existing, now)
        ) {
            return cacheHit(existing, "active mapping cache hit")
        }

        if (capability.status != MALSyncCapabilityStatus.AVAILABLE) {
            return retainOrUnmapped(
                subject = subject,
                externalProvider = externalProvider,
                existing = existing,
                resultKind = MappingAttemptResultKind.UNSUPPORTED,
                diagnostic = capabilityDiagnostic(existing, now),
            )
        }

        val titleEvidence = try {
            filmTitleEvidence(subject)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        val identifierResult = MALSyncAniWorldIdentifierFactory.create(
            subject = subject,
            filmTitleEvidence = titleEvidence,
        )
        if (identifierResult is MALSyncIdentifierResult.Unsupported) {
            return retainOrUnmapped(
                subject = subject,
                externalProvider = externalProvider,
                existing = existing,
                resultKind = MappingAttemptResultKind.UNSUPPORTED,
                diagnostic = identifierResult.diagnostic,
            )
        }
        val identifier = (identifierResult as MALSyncIdentifierResult.Supported).identifier
        val lookup = try {
            client.lookup(identifier)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            MALSyncMappingLookup.Failure("MALSync lookup failed closed")
        }
        return when (lookup) {
            is MALSyncMappingLookup.Exact -> {
                if (lookup.externalProvider != externalProvider) {
                    retainOrUnmapped(
                        subject = subject,
                        externalProvider = externalProvider,
                        existing = existing,
                        resultKind = MappingAttemptResultKind.MALFORMED_RESPONSE,
                        diagnostic = "MALSync returned an unexpected external provider",
                    )
                } else {
                    val incoming = ExternalMapping(
                        subject = subject,
                        externalProvider = externalProvider,
                        externalId = lookup.externalId,
                        mappingSource = MappingSource.MALSYNC,
                        confidence = MappingConfidence.EXACT,
                        createdAt = now,
                        validatedAt = now,
                        status = MappingStatus.ACTIVE,
                        provenance = lookup.provenance,
                        parserVersion = identifier.provenance.adapterRevision,
                    )
                    if (!ExternalMappingPrecedence.canReplace(existing, incoming)) {
                        return mappingWriteRejected(
                            subject = subject,
                            externalProvider = externalProvider,
                            existing = existing,
                            diagnostic = "MALSync mapping was rejected by mapping precedence",
                        )
                    }
                    val stored = mappings.put(incoming)
                    val persisted = mappings.find(subject, externalProvider)
                    val effective = persisted ?: if (stored) incoming else null
                    if (effective != null && effective.externalId != null &&
                        (effective.status == MappingStatus.ACTIVE ||
                            effective.status == MappingStatus.STALE)
                    ) {
                        if (!stored) {
                            return mappingWriteRejected(
                                subject = subject,
                                externalProvider = externalProvider,
                                existing = effective,
                                diagnostic = "MALSync mapping write lost a persistence race; retained effective mapping",
                            )
                        }
                        record(
                            subject = subject,
                            externalProvider = externalProvider,
                            resultKind = MappingAttemptResultKind.MAPPED,
                            diagnostic = "MALSync exact mapping received and persisted",
                        )
                        ExternalMappingResolution.Mapped(effective)
                    } else {
                        mappingWriteRejected(
                            subject = subject,
                            externalProvider = externalProvider,
                            existing = persisted ?: existing,
                            diagnostic = "MALSync exact mapping was not accepted by persistence",
                        )
                    }
                }
            }

            MALSyncMappingLookup.NotFound -> retainOrUnmapped(
                subject = subject,
                externalProvider = externalProvider,
                existing = existing,
                resultKind = MappingAttemptResultKind.UNMAPPED,
                diagnostic = "MALSync returned no exact mapping",
            )

            is MALSyncMappingLookup.Ambiguous -> {
                record(subject, externalProvider, MappingAttemptResultKind.AMBIGUOUS, lookup.diagnostic)
                val candidatesMatchSubject = lookup.candidates.all {
                    it.subject == subject && it.externalProvider == externalProvider
                }
                val candidatesAreDistinct = lookup.candidates
                    .map { it.externalId }
                    .distinct()
                    .size == lookup.candidates.size
                if (!candidatesMatchSubject || !candidatesAreDistinct) {
                    retainOrUnmapped(
                        subject = subject,
                        externalProvider = externalProvider,
                        existing = existing,
                        resultKind = MappingAttemptResultKind.MALFORMED_RESPONSE,
                        diagnostic = "MALSync ambiguity did not preserve the exact mapping subject",
                    )
                } else if (existing != null && existing.externalId != null &&
                    (existing.status == MappingStatus.ACTIVE || existing.status == MappingStatus.STALE)
                ) {
                    ExternalMappingResolution.Mapped(existing)
                } else {
                    ExternalMappingResolution.Ambiguous(subject, lookup.candidates, lookup.diagnostic)
                }
            }

            is MALSyncMappingLookup.Failure -> retainOrUnmapped(
                subject = subject,
                externalProvider = externalProvider,
                existing = existing,
                resultKind = MappingAttemptResultKind.NETWORK_FAILURE,
                diagnostic = lookup.diagnostic,
            )

            is MALSyncMappingLookup.Unsupported -> retainOrUnmapped(
                subject = subject,
                externalProvider = externalProvider,
                existing = existing,
                resultKind = MappingAttemptResultKind.UNSUPPORTED,
                diagnostic = lookup.diagnostic,
            )
        }
    }

    private suspend fun cacheHit(
        mapping: ExternalMapping,
        diagnostic: String,
    ): ExternalMappingResolution.Mapped {
        record(mapping.subject, mapping.externalProvider, MappingAttemptResultKind.CACHE_HIT, diagnostic)
        return ExternalMappingResolution.Mapped(mapping)
    }

    private fun requiresRevalidation(mapping: ExternalMapping, now: java.time.Instant): Boolean =
        mapping.status == MappingStatus.STALE ||
            (mapping.status == MappingStatus.ACTIVE &&
                mapping.staleAt?.let { !now.isBefore(it) } == true)

    private fun capabilityDiagnostic(mapping: ExternalMapping?, now: java.time.Instant): String =
        if (mapping != null && requiresRevalidation(mapping, now)) {
            "mapping revalidation unavailable: ${capability.diagnostic}"
        } else {
            capability.diagnostic
        }

    private suspend fun mappingWriteRejected(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
        existing: ExternalMapping?,
        diagnostic: String,
    ): ExternalMappingResolution {
        record(
            subject = subject,
            externalProvider = externalProvider,
            resultKind = MappingAttemptResultKind.PERSISTENCE_REJECTED,
            diagnostic = diagnostic,
        )
        if (existing != null && existing.externalId != null &&
            (existing.status == MappingStatus.ACTIVE || existing.status == MappingStatus.STALE)
        ) {
            return ExternalMappingResolution.Mapped(existing)
        }
        return ExternalMappingResolution.Unmapped(subject, diagnostic)
    }

    private suspend fun retainOrUnmapped(
        subject: AniWorldMappingSubject,
        externalProvider: ExternalProvider,
        existing: ExternalMapping?,
        resultKind: MappingAttemptResultKind,
        diagnostic: String,
    ): ExternalMappingResolution {
        record(
            subject = subject,
            externalProvider = externalProvider,
            resultKind = resultKind,
            diagnostic = diagnostic,
        )
        if (existing != null && existing.externalId != null &&
            (existing.status == MappingStatus.ACTIVE || existing.status == MappingStatus.STALE)
        ) {
            return ExternalMappingResolution.Mapped(existing)
        }
        return ExternalMappingResolution.Unmapped(
            subject = subject,
            diagnostic = diagnostic,
        )
    }

    private suspend fun record(
        subject: AniWorldMappingSubject?,
        externalProvider: ExternalProvider?,
        resultKind: MappingAttemptResultKind,
        diagnostic: String,
    ) {
        if (subject == null || externalProvider == null) return
        attempts?.append(
            ExternalMappingAttempt(
                subject = subject,
                externalProvider = externalProvider,
                source = MappingSource.MALSYNC,
                attemptedAt = clock.instant(),
                resultKind = resultKind,
                diagnostic = diagnostic.take(ExternalMappingAttempt.MAX_DIAGNOSTIC_LENGTH),
            ),
        )
    }
}

package com.axiel7.anihyou.release.data.db

import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseAuthority
import com.axiel7.anihyou.release.core.model.ReleaseDecision
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseForecastRevision
import com.axiel7.anihyou.release.core.model.ReleasePhase
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceHealth
import com.axiel7.anihyou.release.core.model.SourceHealthStatus
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import java.time.Instant
import java.util.Locale

private const val MAX_EVIDENCE_ID_LENGTH = 256
private const val MAX_IDENTITY_KEY_LENGTH = 2048
private const val MAX_URL_LENGTH = 2048
private const val MAX_HASH_LENGTH = 256
private const val MAX_PARSER_LENGTH = 128
private const val MAX_PAYLOAD_LENGTH = 65_536
private const val MAX_DIAGNOSTIC_LENGTH = 4_096

private fun String.requireBounded(limit: Int, field: String): String {
    require(length <= limit) { "$field exceeds bounded persistence length" }
    return this
}

private fun String?.isWithin(limit: Int): Boolean = this == null || length <= limit

private fun encodeSiteIdentifier(identifier: AniWorldSiteIdentifier): String =
    packFields(
        listOf(
            identifier.provider,
            identifier.canonicalId,
            identifier.slug,
            identifier.canonicalSeriesPath,
            identifier.canonicalUrl,
            identifier.normalizedTitle,
            identifier.sourceType.name,
            identifier.firstSeenAt?.toString(),
            identifier.lastValidatedAt?.toString(),
            identifier.sourceHash,
            identifier.parserVersion,
        ),
    )

private fun decodeSiteIdentifier(payload: String): AniWorldSiteIdentifier? {
    if (payload.length > MAX_PAYLOAD_LENGTH) return null
    val fields = unpackFields(payload)?.takeIf { it.size == 11 } ?: return null
    val sourceType = fields[6]?.let {
        runCatching { AniWorldIdentitySourceType.valueOf(it) }.getOrNull()
    } ?: return null

    fun parseOptionalInstant(index: Int): Instant? {
        val raw = fields[index] ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    if (fields[7] != null && parseOptionalInstant(7) == null) return null
    if (fields[8] != null && parseOptionalInstant(8) == null) return null

    return runCatching {
        AniWorldSiteIdentifier(
            slug = fields[2] ?: return null,
            canonicalSeriesPath = fields[3] ?: return null,
            provider = fields[0] ?: return null,
            canonicalId = fields[1] ?: return null,
            canonicalUrl = fields[4] ?: return null,
            normalizedTitle = fields[5],
            sourceType = sourceType,
            firstSeenAt = parseOptionalInstant(7),
            lastValidatedAt = parseOptionalInstant(8),
            sourceHash = fields[9],
            parserVersion = fields[10],
        )
    }.getOrNull()
}

private fun parseRequiredInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()

private fun parseOptionalInstant(value: String?): Instant? =
    value?.let(::parseRequiredInstant)

private fun encodeStringList(values: List<String>): String = packFields(values)

private fun decodeStringList(payload: String): List<String>? {
    if (payload.length > MAX_PAYLOAD_LENGTH) return null
    val values = unpackFields(payload) ?: return null
    if (values.size > 512 || values.any { it == null || it.length > MAX_EVIDENCE_ID_LENGTH }) {
        return null
    }
    return values.map { it!! }
}

fun ReleaseEvidence.toEntity(): ReleaseEvidenceEntity {
    val sitePayload = siteIdentifier?.let(::encodeSiteIdentifier)
    return ReleaseEvidenceEntity(
        id = id.requireBounded(MAX_EVIDENCE_ID_LENGTH, "evidence id"),
        canonicalFingerprint = ReleaseEvidenceFingerprintV2.compute(this),
        identityKey = identityKey.requireBounded(MAX_IDENTITY_KEY_LENGTH, "evidence identity"),
        sourceType = sourceType.name,
        sourceUrl = sourceUrl.requireBounded(MAX_URL_LENGTH, "evidence url"),
        sourceHash = sourceHash.requireBounded(MAX_HASH_LENGTH, "evidence hash"),
        parserVersion = parserVersion.requireBounded(MAX_PARSER_LENGTH, "evidence parser"),
        observedAt = observedAt.toString(),
        sourceReportedAt = sourceReportedAt?.toString(),
        approximateTime = approximateTime,
        siteIdentifierPayload = sitePayload?.requireBounded(MAX_PAYLOAD_LENGTH, "site identity"),
        sourceSeason = sourceSeason,
        navigationSeason = navigationSeason,
        installmentPayload = encodeInstallment(installment)
            .requireBounded(MAX_PAYLOAD_LENGTH, "installment"),
        languageTrack = languageTrack?.name,
        evidenceType = evidenceType.name,
        scheduleCondition = scheduleCondition.name,
        confidenceSource = confidence.source,
        confidenceIdentity = confidence.identity,
        confidenceInstallment = confidence.installment,
        confidenceLanguageTrack = confidence.languageTrack,
        confidenceTiming = confidence.timing,
    )
}

fun ReleaseEvidence.toEntityOrNull(): ReleaseEvidenceEntity? =
    runCatching { toEntity() }.getOrNull()

fun ReleaseEvidenceEntity.toDomainOrNull(
    validateCanonicalFingerprint: Boolean = true,
): ReleaseEvidence? {
    if (!id.isWithin(MAX_EVIDENCE_ID_LENGTH) ||
        !identityKey.isWithin(MAX_IDENTITY_KEY_LENGTH) ||
        !sourceUrl.isWithin(MAX_URL_LENGTH) ||
        !sourceHash.isWithin(MAX_HASH_LENGTH) ||
        !parserVersion.isWithin(MAX_PARSER_LENGTH) ||
        siteIdentifierPayload?.length?.let { it > MAX_PAYLOAD_LENGTH } == true ||
        installmentPayload.length > MAX_PAYLOAD_LENGTH ||
        (validateCanonicalFingerprint &&
            !ReleaseEvidenceFingerprintV2.isValidFingerprint(canonicalFingerprint))
    ) return null

    val sourceType = runCatching { ReleaseSourceType.valueOf(sourceType) }.getOrNull()
        ?: return null
    val observed = parseRequiredInstant(observedAt) ?: return null
    val sourceReported = parseOptionalInstant(sourceReportedAt)
    if (sourceReportedAt != null && sourceReported == null) return null
    val site = siteIdentifierPayload?.let(::decodeSiteIdentifier)
    if (sourceType.isAniWorld && site == null) return null
    val installment = decodeInstallment(installmentPayload) ?: return null
    val track = languageTrack?.let {
        runCatching { LanguageTrack.valueOf(it) }.getOrNull()
    }
    if (languageTrack != null && track == null) return null
    val evidenceType = runCatching { ReleaseEvidenceType.valueOf(evidenceType) }.getOrNull()
        ?: return null
    val condition = runCatching { ScheduleCondition.valueOf(scheduleCondition) }.getOrNull()
        ?: return null
    return runCatching {
        ReleaseEvidence(
            id = id,
            sourceType = sourceType,
            sourceUrl = sourceUrl,
            sourceHash = sourceHash,
            parserVersion = parserVersion,
            observedAt = observed,
            sourceReportedAt = sourceReported,
            approximateTime = approximateTime,
            siteIdentifier = site,
            sourceSeason = sourceSeason,
            navigationSeason = navigationSeason,
            installment = installment,
            languageTrack = track,
            evidenceType = evidenceType,
            scheduleCondition = condition,
            confidence = ConfidenceVector(
                source = confidenceSource,
                identity = confidenceIdentity,
                installment = confidenceInstallment,
                languageTrack = confidenceLanguageTrack,
                timing = confidenceTiming,
            ),
        ).takeIf { evidence ->
            val legacyPrefix = "${sourceType.name.lowercase(Locale.ROOT)}:"
            evidence.identityKey == identityKey &&
                (!validateCanonicalFingerprint ||
                    canonicalFingerprint == ReleaseEvidenceFingerprintV2.compute(evidence)) &&
                (!ReleaseEvidenceFingerprintV2.isV2Id(id) ||
                    id == ReleaseEvidenceFingerprintV2.evidenceId(evidence)) &&
                (!sourceType.isAniWorld || !id.startsWith(legacyPrefix) ||
                    legacyEvidenceId(evidence) == id)
        }
    }.getOrNull()
}

fun ReleaseDecision.toEntity(): ReleaseDecisionEntity = ReleaseDecisionEntity(
    identityKey = identityKey.requireBounded(MAX_IDENTITY_KEY_LENGTH, "decision identity"),
    siteIdentifierPayload = siteIdentifier?.let(::encodeSiteIdentifier)
        ?.requireBounded(MAX_PAYLOAD_LENGTH, "decision identity"),
    sourceSeason = sourceSeason,
    navigationSeason = navigationSeason,
    installmentPayload = encodeInstallment(installment)
        .requireBounded(MAX_PAYLOAD_LENGTH, "decision installment"),
    languageTrack = languageTrack?.name,
    phase = phase.name,
    scheduleCondition = scheduleCondition.name,
    authority = authority.name,
    contributingEvidenceIdsPayload = encodeStringList(contributingEvidenceIds)
        .requireBounded(MAX_PAYLOAD_LENGTH, "contributing evidence"),
    authoritativeEvidenceIdsPayload = encodeStringList(authoritativeEvidenceIds)
        .requireBounded(MAX_PAYLOAD_LENGTH, "authoritative evidence"),
    releaseAt = releaseAt?.toString(),
    lastObservedAt = lastObservedAt?.toString(),
    decidedAt = decidedAt.toString(),
    revision = revision,
    diagnosticsPayload = encodeStringList(diagnostics)
        .requireBounded(MAX_PAYLOAD_LENGTH, "decision diagnostics"),
)

fun ReleaseDecision.toEntityOrNull(): ReleaseDecisionEntity? =
    runCatching { toEntity() }.getOrNull()

fun ReleaseDecisionEntity.toDomainOrNull(): ReleaseDecision? {
    if (!identityKey.isWithin(MAX_IDENTITY_KEY_LENGTH) ||
        siteIdentifierPayload?.length?.let { it > MAX_PAYLOAD_LENGTH } == true ||
        installmentPayload.length > MAX_PAYLOAD_LENGTH ||
        contributingEvidenceIdsPayload.length > MAX_PAYLOAD_LENGTH ||
        authoritativeEvidenceIdsPayload.length > MAX_PAYLOAD_LENGTH ||
        diagnosticsPayload.length > MAX_PAYLOAD_LENGTH
    ) return null
    val site = siteIdentifierPayload?.let(::decodeSiteIdentifier)
    val installment = decodeInstallment(installmentPayload) ?: return null
    val track = languageTrack?.let {
        runCatching { LanguageTrack.valueOf(it) }.getOrNull()
    }
    if (languageTrack != null && track == null) return null
    val phase = runCatching { ReleasePhase.valueOf(phase) }.getOrNull() ?: return null
    val condition = runCatching { ScheduleCondition.valueOf(scheduleCondition) }.getOrNull()
        ?: return null
    val authority = runCatching { ReleaseAuthority.valueOf(authority) }.getOrNull()
        ?: return null
    val contributing = decodeStringList(contributingEvidenceIdsPayload) ?: return null
    val authoritative = decodeStringList(authoritativeEvidenceIdsPayload) ?: return null
    val release = releaseAt?.let(::parseRequiredInstant)
    val observed = lastObservedAt?.let(::parseRequiredInstant)
    val decided = parseRequiredInstant(decidedAt) ?: return null
    if (releaseAt != null && release == null) return null
    if (lastObservedAt != null && observed == null) return null
    return runCatching {
        ReleaseDecision(
            siteIdentifier = site,
            sourceSeason = sourceSeason,
            navigationSeason = navigationSeason,
            installment = installment,
            languageTrack = track,
            phase = phase,
            scheduleCondition = condition,
            authority = authority,
            contributingEvidenceIds = contributing,
            authoritativeEvidenceIds = authoritative,
            releaseAt = release,
            lastObservedAt = observed,
            decidedAt = decided,
            revision = revision,
            diagnostics = decodeStringList(diagnosticsPayload) ?: return null,
        ).takeIf { it.identityKey == identityKey }
    }.getOrNull()
}

fun SourceHealth.toEntity(): SourceHealthEntity = SourceHealthEntity(
    sourceType = sourceType.name,
    status = status.name,
    lastAttemptAt = lastAttemptAt?.toString(),
    lastSuccessAt = lastSuccessAt?.toString(),
    consecutiveFailures = consecutiveFailures,
    parserVersion = parserVersion?.requireBounded(MAX_PARSER_LENGTH, "health parser"),
    sourceHash = sourceHash?.requireBounded(MAX_HASH_LENGTH, "health hash"),
    diagnostic = diagnostic?.requireBounded(MAX_DIAGNOSTIC_LENGTH, "health diagnostic"),
)

fun SourceHealth.toEntityOrNull(): SourceHealthEntity? =
    runCatching { toEntity() }.getOrNull()

fun SourceHealthEntity.toDomainOrNull(): SourceHealth? {
    if (!parserVersion.isWithin(MAX_PARSER_LENGTH) ||
        !sourceHash.isWithin(MAX_HASH_LENGTH) ||
        !diagnostic.isWithin(MAX_DIAGNOSTIC_LENGTH)
    ) return null
    val type = runCatching { ReleaseSourceType.valueOf(sourceType) }.getOrNull() ?: return null
    val status = runCatching { SourceHealthStatus.valueOf(status) }.getOrNull() ?: return null
    val attempt = lastAttemptAt?.let(::parseRequiredInstant)
    val success = lastSuccessAt?.let(::parseRequiredInstant)
    if ((lastAttemptAt != null && attempt == null) || (lastSuccessAt != null && success == null)) {
        return null
    }
    if (consecutiveFailures < 0) return null
    return runCatching {
        SourceHealth(
            sourceType = type,
            status = status,
            lastAttemptAt = attempt,
            lastSuccessAt = success,
            consecutiveFailures = consecutiveFailures,
            parserVersion = parserVersion,
            sourceHash = sourceHash,
            diagnostic = diagnostic,
        )
    }.getOrNull()
}

fun ReleaseForecastRevision.toEntity(): ReleaseForecastRevisionEntity =
    ReleaseForecastRevisionEntity(
        identityKey = identityKey.requireBounded(MAX_IDENTITY_KEY_LENGTH, "forecast identity"),
        evidenceId = evidenceId.requireBounded(MAX_EVIDENCE_ID_LENGTH, "forecast evidence"),
        forecastAt = forecastAt.toString(),
        observedAt = observedAt.toString(),
        approximateTime = approximateTime,
        sourceHash = sourceHash.requireBounded(MAX_HASH_LENGTH, "forecast hash"),
        parserVersion = parserVersion.requireBounded(MAX_PARSER_LENGTH, "forecast parser"),
    )

fun ReleaseForecastRevision.toEntityOrNull(): ReleaseForecastRevisionEntity? =
    runCatching { toEntity() }.getOrNull()

fun ReleaseForecastRevisionEntity.toDomainOrNull(): ReleaseForecastRevision? {
    if (!identityKey.isWithin(MAX_IDENTITY_KEY_LENGTH) ||
        !evidenceId.isWithin(MAX_EVIDENCE_ID_LENGTH) ||
        !sourceHash.isWithin(MAX_HASH_LENGTH) ||
        !parserVersion.isWithin(MAX_PARSER_LENGTH)
    ) return null
    val forecast = parseRequiredInstant(forecastAt) ?: return null
    val observed = parseRequiredInstant(observedAt) ?: return null
    return runCatching {
        ReleaseForecastRevision(
            id = evidenceId,
            identityKey = identityKey,
            evidenceId = evidenceId,
            forecastAt = forecast,
            observedAt = observed,
            approximateTime = approximateTime,
            sourceHash = sourceHash,
            parserVersion = parserVersion,
        ).takeIf { it.id == evidenceId }
    }.getOrNull()
}

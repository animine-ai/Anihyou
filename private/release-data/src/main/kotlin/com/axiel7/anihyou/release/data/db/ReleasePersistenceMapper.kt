package com.axiel7.anihyou.release.data.db

import com.axiel7.anihyou.release.core.model.AuthorityStatus
import com.axiel7.anihyou.release.core.model.CalendarReleaseProjection
import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.MediaReleaseProjection
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import com.axiel7.anihyou.release.data.aniworld.AniWorldNormalizedObservation
import com.axiel7.anihyou.release.data.aniworld.AniWorldRawToken
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val NULL_MARKER = "\u0000"

internal fun packFields(values: List<String?>): String = buildString {
    values.forEach { value ->
        val encoded = value ?: NULL_MARKER
        append(encoded.length)
        append(':')
        append(encoded)
    }
}

internal fun unpackFields(payload: String): List<String?>? {
    if (payload.isEmpty()) return emptyList()
    val values = mutableListOf<String?>()
    var cursor = 0
    while (cursor < payload.length) {
        val separator = payload.indexOf(':', cursor)
        if (separator <= cursor) return null
        val length = payload.substring(cursor, separator).toIntOrNull() ?: return null
        if (length < 0 || separator + 1 + length > payload.length) return null
        val valueStart = separator + 1
        val value = payload.substring(valueStart, valueStart + length)
        values += if (value == NULL_MARKER) null else value
        cursor = valueStart + length
    }
    return values
}

private fun unpackExactly(payload: String, expectedSize: Int): List<String?>? =
    unpackFields(payload)?.takeIf { it.size == expectedSize }

private fun packList(values: List<String>): String = packFields(values)

private fun unpackList(payload: String): List<String>? {
    val values = unpackFields(payload) ?: return null
    if (values.any { it == null }) return null
    return values.map { it!! }
}

internal fun encodeInstallment(installment: Installment): String = when (installment) {
    is Installment.Episode -> packFields(
        listOf("episode", installment.number.toString(), installment.fraction?.toString()),
    )
    is Installment.Film -> packFields(listOf("film", installment.number?.toString()))
    is Installment.Special -> packFields(listOf("special", installment.number?.toString()))
}

internal fun decodeInstallment(payload: String): Installment? {
    val fields = unpackFields(payload) ?: return null
    val kind = fields.firstOrNull() ?: return null
    return when (kind) {
        "episode" -> {
            if (fields.size != 3) return null
            val number = fields[1]?.toIntOrNull() ?: return null
            val fraction = fields[2]?.toIntOrNull()
            if (fields[2] != null && fraction == null) return null
            runCatching { Installment.Episode(number, fraction) }.getOrNull()
        }
        "film" -> {
            if (fields.size != 2) return null
            val number = fields[1]?.toIntOrNull()
            if (fields[1] != null && number == null) return null
            runCatching { Installment.Film(number) }.getOrNull()
        }
        "special" -> {
            if (fields.size != 2) return null
            val number = fields[1]?.toIntOrNull()
            if (fields[1] != null && number == null) return null
            runCatching { Installment.Special(number) }.getOrNull()
        }
        else -> null
    }
}

internal fun encodeStream(stream: ReleaseStreamKey): String = packFields(
    listOf(
        stream.providerId.value,
        stream.stableSeriesKey.value,
        stream.releaseKind.name,
        stream.sourceSeason?.toString(),
        stream.languageTrack.name,
    ),
)

internal fun decodeStream(payload: String): ReleaseStreamKey? {
    val fields = unpackExactly(payload, 5) ?: return null
    val provider = fields[0] ?: return null
    val series = fields[1] ?: return null
    val kind = fields[2]?.let { runCatching { ReleaseKind.valueOf(it) }.getOrNull() } ?: return null
    val season = fields[3]?.toIntOrNull()
    if (fields[3] != null && season == null) return null
    val track = fields[4]?.let { runCatching { LanguageTrack.valueOf(it) }.getOrNull() } ?: return null
    return runCatching {
        ReleaseStreamKey(
            providerId = ProviderId(provider),
            stableSeriesKey = SourceSeriesKey(series),
            releaseKind = kind,
            sourceSeason = season,
            languageTrack = track,
        )
    }.getOrNull()
}

private fun encodeConfirmation(confirmation: Confirmation): String = packFields(
    listOf(
        encodeInstallment(confirmation.identity.installment),
        confirmation.evidenceKind.name,
        confirmation.confirmedObservedAt.toString(),
    ),
)

private fun decodeConfirmation(
    stream: ReleaseStreamKey,
    payload: String,
): Confirmation? {
    val fields = unpackExactly(payload, 3) ?: return null
    val installment = fields[0]?.let(::decodeInstallment) ?: return null
    val evidence = fields[1]?.let {
        runCatching { ConfirmationEvidenceKind.valueOf(it) }.getOrNull()
    } ?: return null
    val observedAt = fields[2]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return Confirmation(
        identity = SourceIdentity(stream, installment),
        evidenceKind = evidence,
        confirmedObservedAt = observedAt,
    )
}

private fun encodeForecast(forecast: Forecast): String = packFields(
    listOf(
        encodeInstallment(forecast.identity.installment),
        forecast.forecastAt.toString(),
        forecast.sourceDate?.toString(),
        forecast.sourceTime,
        forecast.sourceZone?.id,
        forecast.approximate.toString(),
        forecast.observedAt.toString(),
    ),
)

private fun decodeForecast(
    stream: ReleaseStreamKey,
    payload: String,
): Forecast? {
    val fields = unpackExactly(payload, 7) ?: return null
    val installment = fields[0]?.let(::decodeInstallment) ?: return null
    val forecastAt = fields[1]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val sourceDate = fields[2]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (fields[2] != null && sourceDate == null) return null
    val sourceZone = fields[4]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    if (fields[4] != null && sourceZone == null) return null
    val approximate = fields[5]?.toBooleanStrictOrNull() ?: return null
    val observedAt = fields[6]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return Forecast(
        identity = SourceIdentity(stream, installment),
        forecastAt = forecastAt,
        sourceDate = sourceDate,
        sourceTime = fields[3],
        sourceZone = sourceZone,
        approximate = approximate,
        observedAt = observedAt,
    )
}

private fun encodeFreshness(freshness: Freshness): String = packFields(
    listOf(
        freshness.status.name,
        freshness.lastAttemptAt?.toString(),
        freshness.lastSuccessAt?.toString(),
        freshness.observedAt?.toString(),
        freshness.parserVersion,
        freshness.sourceHash,
        freshness.diagnostic,
    ),
)

private fun decodeFreshness(payload: String): Freshness? {
    val fields = unpackExactly(payload, 7) ?: return null
    val status = fields[0]?.let { runCatching { FreshnessStatus.valueOf(it) }.getOrNull() } ?: return null
    fun parseInstant(index: Int): Instant? {
        val raw = fields[index] ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }
    val lastAttempt = parseInstant(1)
    val lastSuccess = parseInstant(2)
    val observed = parseInstant(3)
    if ((fields[1] != null && lastAttempt == null) ||
        (fields[2] != null && lastSuccess == null) ||
        (fields[3] != null && observed == null)
    ) return null
    return Freshness(
        status = status,
        lastAttemptAt = lastAttempt,
        lastSuccessAt = lastSuccess,
        observedAt = observed,
        parserVersion = fields[4],
        sourceHash = fields[5],
        diagnostic = fields[6],
    )
}

private fun encodeMapping(mapping: ReleaseMapping): String = packFields(
    listOf(
        mapping.mediaId.toString(),
        mapping.confidence.name,
        mapping.score?.toString(),
        mapping.runnerUpMargin?.toString(),
        mapping.evidence,
        mapping.matcherVersion,
        mapping.origin.name,
    ),
)

private fun decodeMapping(payload: String): ReleaseMapping? {
    val fields = unpackExactly(payload, 7) ?: return null
    val mediaId = fields[0]?.toIntOrNull() ?: return null
    val confidence = fields[1]?.let { runCatching { MappingConfidence.valueOf(it) }.getOrNull() }
        ?: return null
    val score = fields[2]?.toDoubleOrNull()
    if (fields[2] != null && score == null) return null
    val runnerUpMargin = fields[3]?.toDoubleOrNull()
    if (fields[3] != null && runnerUpMargin == null) return null
    val evidence = fields[4] ?: return null
    val matcherVersion = fields[5] ?: return null
    val origin = fields[6]?.let { runCatching { MappingOrigin.valueOf(it) }.getOrNull() }
        ?: return null
    return runCatching {
        ReleaseMapping(
            mediaId = mediaId,
            confidence = confidence,
            score = score,
            runnerUpMargin = runnerUpMargin,
            evidence = evidence,
            matcherVersion = matcherVersion,
            origin = origin,
        )
    }.getOrNull()
}

private fun encodeConfirmations(values: List<Confirmation>): String =
    packList(values.map(::encodeConfirmation))

private fun decodeConfirmations(
    stream: ReleaseStreamKey,
    payload: String,
): List<Confirmation>? {
    val records = unpackList(payload) ?: return null
    val decoded = mutableListOf<Confirmation>()
    records.forEach { record ->
        decoded += decodeConfirmation(stream, record) ?: return null
    }
    return decoded
}

private fun encodeForecasts(values: List<Forecast>): String =
    packList(values.map(::encodeForecast))

private fun decodeForecasts(
    stream: ReleaseStreamKey,
    payload: String,
): List<Forecast>? {
    val records = unpackList(payload) ?: return null
    val decoded = mutableListOf<Forecast>()
    records.forEach { record ->
        decoded += decodeForecast(stream, record) ?: return null
    }
    return decoded
}

private fun encodeRawTokens(values: List<AniWorldRawToken>): String =
    packList(values.map { packFields(listOf(it.key, it.value)) })

private fun decodeRawTokens(payload: String): List<AniWorldRawToken>? {
    val records = unpackList(payload) ?: return null
    val decoded = mutableListOf<AniWorldRawToken>()
    records.forEach { record ->
        val fields = unpackExactly(record, 2) ?: return null
        decoded += AniWorldRawToken(fields[0] ?: return null, fields[1] ?: return null)
    }
    return decoded
}

private fun encodeDiagnostics(values: List<String>): String = packList(values)

private fun decodeDiagnostics(payload: String): List<String>? = unpackList(payload)

fun ReleaseSnapshot.toEntity(): ProviderSnapshotEntity = ProviderSnapshotEntity(
    streamKey = stream.stableKey,
    providerId = stream.providerId.value,
    stableSeriesKey = stream.stableSeriesKey.value,
    releaseKind = stream.releaseKind.name,
    sourceSeason = stream.sourceSeason,
    languageTrack = stream.languageTrack.name,
    confirmationsPayload = encodeConfirmations(confirmations),
    forecastsPayload = encodeForecasts(forecasts),
    freshnessStatus = freshness.status.name,
    lastAttemptAt = freshness.lastAttemptAt?.toString(),
    lastSuccessAt = freshness.lastSuccessAt?.toString(),
    freshnessObservedAt = freshness.observedAt?.toString(),
    parserVersion = freshness.parserVersion,
    sourceHash = freshness.sourceHash,
    freshnessDiagnostic = freshness.diagnostic,
    mappingPayload = mapping?.let(::encodeMapping),
    sourcePresent = sourcePresent,
    sourceRoot = sourceRoot,
    snapshotObservedAt = observedAt.toString(),
)

fun ProviderSnapshotEntity.toDomainOrNull(): ReleaseSnapshot? {
    val stream = decodeStream(
        packFields(listOf(providerId, stableSeriesKey, releaseKind, sourceSeason?.toString(), languageTrack)),
    ) ?: return null
    if (stream.stableKey != streamKey) return null
    val confirmations = decodeConfirmations(stream, confirmationsPayload) ?: return null
    val forecasts = decodeForecasts(stream, forecastsPayload) ?: return null
    val freshness = decodeFreshness(
        packFields(
            listOf(
                freshnessStatus,
                lastAttemptAt,
                lastSuccessAt,
                freshnessObservedAt,
                parserVersion,
                sourceHash,
                freshnessDiagnostic,
            ),
        ),
    ) ?: return null
    val mapping = mappingPayload?.let { decodeMapping(it) ?: return null }
    val observedAt = runCatching { Instant.parse(snapshotObservedAt) }.getOrNull() ?: return null
    return runCatching {
        ReleaseSnapshot(
            stream = stream,
            confirmations = confirmations,
            forecasts = forecasts,
            freshness = freshness,
            mapping = mapping,
            sourcePresent = sourcePresent,
            sourceRoot = sourceRoot,
            observedAt = observedAt,
        )
    }.getOrNull()
}

fun AniWorldNormalizedObservation.toEntity(observedAt: Instant): SourceReleaseObservationEntity {
    val identity = SourceIdentity(stream, installment)
    return SourceReleaseObservationEntity(
        identityKey = identity.stableKey,
        observedAt = observedAt.toString(),
        streamKey = stream.stableKey,
        streamPayload = encodeStream(stream),
        installmentPayload = encodeInstallment(installment),
        track = track.name,
        confirmed = confirmed,
        forecastAt = forecastAt?.toString(),
        sourceDate = sourceDate?.toString(),
        sourceTime = sourceTime,
        sourceZone = sourceZone?.id,
        approximate = approximate,
        sourceRoot = sourceRoot,
        rawTokensPayload = encodeRawTokens(rawTokens),
    )
}

fun SourceReleaseObservationEntity.toDomainOrNull(): AniWorldNormalizedObservation? {
    val stream = decodeStream(streamPayload ?: return null) ?: return null
    if (stream.stableKey != streamKey) return null
    val installment = decodeInstallment(installmentPayload) ?: return null
    val trackValue = runCatching { LanguageTrack.valueOf(track) }.getOrNull() ?: return null
    val observationTime = runCatching { Instant.parse(observedAt) }.getOrNull() ?: return null
    val forecast = forecastAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (forecastAt != null && forecast == null) return null
    val date = sourceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (sourceDate != null && date == null) return null
    val zone = sourceZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    if (sourceZone != null && zone == null) return null
    if (sourceRoot.isBlank()) return null
    if (observationTime == Instant.MIN) return null
    return AniWorldNormalizedObservation(
        stream = stream,
        installment = installment,
        track = trackValue,
        confirmed = confirmed,
        forecastAt = forecast,
        sourceDate = date,
        sourceTime = sourceTime,
        sourceZone = zone,
        approximate = approximate,
        sourceRoot = sourceRoot,
        rawTokens = decodeRawTokens(rawTokensPayload) ?: return null,
    )
}

fun ReleaseMapping.toEntity(streamKey: String, updatedAt: Instant): ReleaseMappingEntity =
    ReleaseMappingEntity(
        streamKey = streamKey,
        mediaId = mediaId,
        confidence = confidence.name,
        score = score,
        runnerUpMargin = runnerUpMargin,
        evidence = evidence,
        matcherVersion = matcherVersion,
        origin = origin.name,
        updatedAt = updatedAt.toString(),
    )

fun ReleaseMappingEntity.toDomainOrNull(): ReleaseMapping? {
    val mediaId = mediaId ?: return null
    val confidence = confidence?.let { runCatching { MappingConfidence.valueOf(it) }.getOrNull() }
        ?: return null
    val evidence = evidence ?: return null
    val matcherVersion = matcherVersion ?: return null
    val origin = origin?.let { runCatching { MappingOrigin.valueOf(it) }.getOrNull() } ?: return null
    return runCatching {
        ReleaseMapping(
            mediaId = mediaId,
            confidence = confidence,
            score = score,
            runnerUpMargin = runnerUpMargin,
            evidence = evidence,
            matcherVersion = matcherVersion,
            origin = origin,
        )
    }.getOrNull()
}

private fun projectionKey(accountId: Long?, stream: ReleaseStreamKey): String =
    "${accountId?.toString() ?: "shared"}::${stream.stableKey}"

fun MediaReleaseProjection.toEntity(accountId: Long?): MediaReleaseProjectionEntity =
    MediaReleaseProjectionEntity(
        projectionKey = projectionKey(accountId, stream),
        accountId = accountId,
        mediaId = mediaId,
        streamPayload = encodeStream(stream),
        authority = authority.name,
        confirmedThroughEpisode = confirmedThroughEpisode,
        confirmedInstallmentsPayload = packList(confirmedInstallments.map(::encodeInstallment)),
        nextForecastPayload = nextForecast?.let(::encodeForecast),
        nextForecastAt = nextForecast?.forecastAt?.toString(),
        pendingCount = pendingCount,
        freshnessPayload = encodeFreshness(freshness),
        mappingPayload = mapping?.let(::encodeMapping),
        sourceRoot = sourceRoot,
        revision = revision,
        diagnosticsPayload = encodeDiagnostics(diagnostics),
    )

fun MediaReleaseProjectionEntity.toDomainOrNull(): MediaReleaseProjection? {
    val stream = decodeStream(streamPayload) ?: return null
    val authority = runCatching { AuthorityStatus.valueOf(authority) }.getOrNull() ?: return null
    val installments = unpackList(confirmedInstallmentsPayload)?.map { decodeInstallment(it) }
        ?: return null
    if (installments.any { it == null }) return null
    val next = nextForecastPayload?.let { decodeForecast(stream, it) ?: return null }
    if ((nextForecastAt == null) != (next == null)) return null
    if (nextForecastAt != null && nextForecastAt != next?.forecastAt?.toString()) return null
    val freshness = decodeFreshness(freshnessPayload) ?: return null
    val mapping = mappingPayload?.let { decodeMapping(it) ?: return null }
    val diagnostics = decodeDiagnostics(diagnosticsPayload) ?: return null
    if (mediaId != null && mediaId <= 0) return null
    if (pendingCount < 0 || revision < 0L) return null
    return MediaReleaseProjection(
        mediaId = mediaId,
        stream = stream,
        authority = authority,
        confirmedThroughEpisode = confirmedThroughEpisode,
        confirmedInstallments = installments.filterNotNull(),
        nextForecast = next,
        pendingCount = pendingCount,
        freshness = freshness,
        mapping = mapping,
        sourceRoot = sourceRoot,
        revision = revision,
        diagnostics = diagnostics,
    )
}

fun CalendarReleaseProjection.toEntity(accountId: Long?): CalendarReleaseProjectionEntity =
    CalendarReleaseProjectionEntity(
        projectionKey = projectionKey(accountId, stream) + "::" + installment.stableKey,
        accountId = accountId,
        mediaId = mediaId,
        streamPayload = encodeStream(stream),
        installmentPayload = encodeInstallment(installment),
        forecastAt = forecastAt?.toString(),
        confirmed = confirmed,
        authority = authority.name,
        revision = revision,
        sourceDate = this.sourceDate?.toString()
            ?: forecastAt?.atZone(ZoneId.of("Europe/Berlin"))?.toLocalDate()?.toString(),
    )

fun CalendarReleaseProjectionEntity.toDomainOrNull(): CalendarReleaseProjection? {
    val stream = decodeStream(streamPayload) ?: return null
    val installment = decodeInstallment(installmentPayload) ?: return null
    val forecast = forecastAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (forecastAt != null && forecast == null) return null
    val parsedSourceDate = sourceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (sourceDate != null && parsedSourceDate == null) return null
    val authority = runCatching { AuthorityStatus.valueOf(authority) }.getOrNull() ?: return null
    if (mediaId != null && mediaId <= 0) return null
    if (revision < 0L) return null
    return CalendarReleaseProjection(
        mediaId = mediaId,
        stream = stream,
        installment = installment,
        forecastAt = forecast,
        confirmed = confirmed,
        authority = authority,
        revision = revision,
        sourceDate = parsedSourceDate,
    )
}

package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseSnapshot
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AniWorldParser(
    private val limits: AniWorldLimits = AniWorldLimits(),
    private val parserVersion: String = DEFAULT_PARSER_VERSION,
) {
    init {
        require(parserVersion.isNotBlank()) { "parser version must not be blank" }
    }

    fun inspect(
        html: String,
        expectedRole: AniWorldPageRole,
        sourceUrl: String = DEFAULT_RECENT_URL,
    ): AniWorldInspectionResult {
        if (html.toByteArray(Charsets.UTF_8).size > limits.maxBodyBytes) {
            return AniWorldInspectionResult.Failure(
                AniWorldFailureKind.OVERSIZED_BODY,
                "inspection body exceeds the bounded body limit",
            )
        }
        val document = Jsoup.parse(html, sourceUrl)
        if (isBlocked(document)) {
            return AniWorldInspectionResult.Failure(
                AniWorldFailureKind.BLOCKED_PAGE,
                "block-page marker detected",
            )
        }
        val anchor = pageAnchor(document, expectedRole)
            ?: return AniWorldInspectionResult.Failure(
                AniWorldFailureKind.MISSING_PAGE_ANCHOR,
                "required page-role heading is missing or changed",
            )
        val releaseFlags = releaseFlags(document, sourceUrl)
        val tracks = releaseFlags.mapNotNull {
            (it.classification as? FlagClassification.German)?.track
        }
        val unknownFlags = releaseFlags.count {
            it.classification is FlagClassification.Unknown
        }
        if (expectedRole != AniWorldPageRole.SUPPORT_EXPLANATION && unknownFlags > 0) {
            return AniWorldInspectionResult.Failure(
                AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
                "an expected release marker has changed or is not recognized",
            )
        }
        return AniWorldInspectionResult.Success(
            AniWorldPageInspection(
                role = expectedRole,
                pageAnchor = anchor,
                flagTracksInDocumentOrder = tracks,
                unknownFlagCount = unknownFlags,
                sourceLinks = releaseFlags.mapNotNull {
                    resolveSourceKey(it.card, sourceUrl)
                }.distinct(),
            ),
        )
    }

    fun parse(
        response: AniWorldHttpResponse,
        role: AniWorldPageRole,
        observedAt: Instant,
    ): AniWorldParseResult = parse(
        html = response.body,
        sourceUrl = response.finalUrl,
        role = role,
        observedAt = observedAt,
    )

    fun parse(
        html: String,
        sourceUrl: String,
        role: AniWorldPageRole,
        observedAt: Instant,
    ): AniWorldParseResult {
        if (role == AniWorldPageRole.SUPPORT_EXPLANATION) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.UNSUPPORTED_ROLE,
                "support explanation is inspect-only and does not produce release snapshots",
            )
        }
        val bodyBytes = html.toByteArray(Charsets.UTF_8).size
        if (bodyBytes > limits.maxBodyBytes) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.OVERSIZED_BODY,
                "parse body exceeds the bounded body limit",
            )
        }
        val document = Jsoup.parse(html, sourceUrl)
        if (isBlocked(document)) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.BLOCKED_PAGE,
                "block-page marker detected",
            )
        }
        val pageAnchor = pageAnchor(document, role)
            ?: return AniWorldParseResult.Failure(
                AniWorldFailureKind.MISSING_PAGE_ANCHOR,
                "required page-role heading is missing or changed",
            )
        val releaseFlags = releaseFlags(document, sourceUrl)
        if (releaseFlags.size > limits.maxCards) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.TOO_MANY_CARDS,
                "release marker/card count exceeds the bounded card limit",
            )
        }

        val sourceHash = sha256(html)
        val pageSourceRoot = sourceRoot(sourceUrl)
        val unknownFlags = releaseFlags.count {
            it.classification is FlagClassification.Unknown
        }
        val germanFlags = releaseFlags.filter {
            it.classification is FlagClassification.German
        }
        if (unknownFlags > 0) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
                "an expected release marker has changed or is not recognized",
                diagnostics = diagnostics(
                    role = role,
                    sourceHash = sourceHash,
                    bodyBytes = bodyBytes,
                    cardCount = releaseFlags.size,
                    validCardCount = 0,
                    invalidCardCount = 0,
                    recognizedTrackCount = germanFlags.size,
                    unknownFlagCount = unknownFlags,
                    sourceRoot = pageSourceRoot,
                ),
            )
        }

        val dateMarkers = document.select("time").mapNotNull { time ->
            time.attr("datetime").substringBefore('T').ifBlank { time.attr("data-date") }
        }.distinct()
        if (role == AniWorldPageRole.FUTURE_CALENDAR && dateMarkers.size > limits.maxCalendarDays) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.TOO_MANY_DAYS,
                "calendar day count exceeds the bounded day limit",
            )
        }

        val observations = mutableListOf<AniWorldNormalizedObservation>()
        var invalidCardCount = 0
        for (releaseFlag in germanFlags) {
            val flag = releaseFlag.flag
            val card = releaseFlag.card
            val track = (releaseFlag.classification as FlagClassification.German).track
            val route = (resolveRoute(card, sourceUrl) as? AniWorldCardRouteResult.Success)?.route
            if (route == null) {
                invalidCardCount += 1
                continue
            }
            val canonicalRoute = route
            val sourceKey = SourceSeriesKey(canonicalRoute.slug)
            val installment = parseInstallment(flag, card, canonicalRoute)
            val season = parseSeason(flag, card) ?: canonicalRoute.season
            val parsedTime = if (role == AniWorldPageRole.FUTURE_CALENDAR) {
                parseForecastTime(flag, card, flagText(flag, card))
            } else {
                DateTimeParseOutcome.NotRequired
            }
            if (installment == null) {
                invalidCardCount += 1
                continue
            }
            if (parsedTime is DateTimeParseOutcome.Failure) {
                return AniWorldParseResult.Failure(
                    parsedTime.kind,
                    parsedTime.diagnostic,
                    diagnostics = diagnostics(
                        role = role,
                        sourceHash = sourceHash,
                        bodyBytes = bodyBytes,
                        cardCount = releaseFlags.size,
                        validCardCount = observations.size,
                        invalidCardCount = invalidCardCount + 1,
                        recognizedTrackCount = germanFlags.size,
                        unknownFlagCount = 0,
                        sourceRoot = pageSourceRoot,
                    ),
                )
            }
            val time = parsedTime as? DateTimeParseOutcome.Parsed
            val kind = parseReleaseKind(flag, card, canonicalRoute)
            val stream = ReleaseStreamKey(
                providerId = ProviderId(PROVIDER_ID),
                stableSeriesKey = sourceKey,
                releaseKind = kind,
                sourceSeason = season,
                languageTrack = track,
            )
            val rawTokens = unknownDataTokens(card)
            observations += AniWorldNormalizedObservation(
                stream = stream,
                installment = installment,
                track = track,
                confirmed = role == AniWorldPageRole.RECENT_CURRENT,
                forecastAt = time?.instant,
                sourceDate = time?.date,
                sourceTime = time?.time,
                sourceZone = time?.zone,
                approximate = time?.approximate ?: false,
                sourceRoot = canonicalRoute.canonicalSeriesUrl,
                rawTokens = rawTokens,
            )
        }

        val denominator = releaseFlags.size.coerceAtLeast(1)
        if (invalidCardCount.toDouble() / denominator > limits.maxInvalidCardRatio) {
            return AniWorldParseResult.Failure(
                AniWorldFailureKind.EXCESSIVE_INVALID_CARDS,
                "invalid card ratio exceeds the bounded threshold",
                diagnostics = diagnostics(
                    role = role,
                    sourceHash = sourceHash,
                    bodyBytes = bodyBytes,
                    cardCount = releaseFlags.size,
                    validCardCount = observations.size,
                    invalidCardCount = invalidCardCount,
                    recognizedTrackCount = germanFlags.size,
                    unknownFlagCount = 0,
                    sourceRoot = pageSourceRoot,
                ),
            )
        }

        val deduplicated = linkedMapOf<String, AniWorldNormalizedObservation>()
        for (observation in observations) {
            val identity = SourceIdentity(observation.stream, observation.installment)
            val previous = deduplicated[identity.stableKey]
            if (previous == null) {
                deduplicated[identity.stableKey] = observation
            } else if (
                previous.confirmed != observation.confirmed ||
                previous.forecastAt != observation.forecastAt ||
                previous.sourceDate != observation.sourceDate ||
                previous.sourceTime != observation.sourceTime
            ) {
                return AniWorldParseResult.Failure(
                    AniWorldFailureKind.DUPLICATE_IDENTITY_CONFLICT,
                    "duplicate source identity contains conflicting evidence",
                    diagnostics = diagnostics(
                        role = role,
                        sourceHash = sourceHash,
                        bodyBytes = bodyBytes,
                        cardCount = releaseFlags.size,
                        validCardCount = deduplicated.size,
                        invalidCardCount = invalidCardCount,
                        recognizedTrackCount = germanFlags.size,
                        unknownFlagCount = 0,
                        sourceRoot = pageSourceRoot,
                    ),
                )
            }
        }

        val uniqueObservations = deduplicated.values.toList()
        val snapshots = uniqueObservations
            .groupBy { it.stream }
            .map { (stream, rows) ->
                val freshness = Freshness(
                    status = FreshnessStatus.FRESH,
                    lastAttemptAt = observedAt,
                    lastSuccessAt = observedAt,
                    observedAt = observedAt,
                    parserVersion = parserVersion,
                    sourceHash = sourceHash,
                    diagnostic = pageAnchor,
                )
                ReleaseSnapshot(
                    stream = stream,
                    confirmations = rows.filter { it.confirmed }.map { row ->
                        Confirmation(
                            identity = SourceIdentity(row.stream, row.installment),
                            evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
                            confirmedObservedAt = observedAt,
                        )
                    },
                    forecasts = rows.filter { !it.confirmed }.mapNotNull { row ->
                        val forecastAt = row.forecastAt ?: return@mapNotNull null
                        Forecast(
                            identity = SourceIdentity(row.stream, row.installment),
                            forecastAt = forecastAt,
                            sourceDate = row.sourceDate,
                            sourceTime = row.sourceTime,
                            sourceZone = row.sourceZone,
                            approximate = row.approximate,
                            observedAt = observedAt,
                        )
                    },
                    freshness = freshness,
                    mapping = null,
                    sourcePresent = true,
                    sourceRoot = rows.first().sourceRoot,
                    observedAt = observedAt,
                )
            }
            .sortedBy { it.stream.stableKey }

        return AniWorldParseResult.Success(
            AniWorldParsedPage(
                role = role,
                observations = uniqueObservations,
                snapshots = snapshots,
                diagnostics = diagnostics(
                    role = role,
                    sourceHash = sourceHash,
                    bodyBytes = bodyBytes,
                    cardCount = releaseFlags.size,
                    validCardCount = uniqueObservations.size,
                    invalidCardCount = invalidCardCount,
                    recognizedTrackCount = germanFlags.size,
                    unknownFlagCount = 0,
                    sourceRoot = pageSourceRoot,
                    boundedUnknownTokenCount = uniqueObservations
                        .sumOf { it.rawTokens.size }
                        .coerceAtMost(limits.maxUnknownTokens),
                ),
            ),
        )
    }

    private fun diagnostics(
        role: AniWorldPageRole,
        sourceHash: String,
        bodyBytes: Int,
        cardCount: Int,
        validCardCount: Int,
        invalidCardCount: Int,
        recognizedTrackCount: Int,
        unknownFlagCount: Int,
        sourceRoot: String,
        boundedUnknownTokenCount: Int = 0,
    ): AniWorldPageDiagnostics = AniWorldPageDiagnostics(
        role = role,
        parserVersion = parserVersion,
        sourceHash = sourceHash,
        bodyBytes = bodyBytes,
        cardCount = cardCount,
        validCardCount = validCardCount,
        invalidCardCount = invalidCardCount,
        recognizedTrackCount = recognizedTrackCount,
        unknownFlagCount = unknownFlagCount,
        boundedUnknownTokenCount = boundedUnknownTokenCount,
        sourceRoot = sourceRoot,
    )

    private fun pageAnchor(document: Document, role: AniWorldPageRole): String? {
        if (document.select("main").isEmpty()) return null
        val headings = document.select("main h1, main h2")
        return headings.firstOrNull { heading ->
            val text = normalize(heading.text())
            when (role) {
                AniWorldPageRole.RECENT_CURRENT -> text.contains("neue episoden")
                AniWorldPageRole.FUTURE_CALENDAR -> text.contains("animekalender")
                AniWorldPageRole.POSTPONEMENT ->
                    text.contains("verschob") || text.contains("verschieb")
                AniWorldPageRole.DIRECT_EPISODE ->
                    text.contains("folge") || text.contains("episode") || text.contains("stream")
                AniWorldPageRole.SUPPORT_EXPLANATION -> text.contains("nach deutscher synchro sortieren")
            }
        }?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun flagElements(document: Document): List<Element> =
        document.select("main img").filter { image ->
            image.classNames().any { normalize(it) == "flag" } ||
                image.hasAttr("data-language") ||
                image.hasAttr("data-track")
        }

    private fun releaseFlags(document: Document, sourceUrl: String): List<ReleaseFlag> =
        flagElements(document).mapNotNull { flag ->
            val card = findCard(flag)
            if (!isReleaseCandidate(flag, card, sourceUrl)) {
                null
            } else {
                ReleaseFlag(
                    flag = flag,
                    card = card,
                    classification = classifyFlag(flag, card),
                )
            }
        }

    private fun isReleaseCandidate(
        flag: Element,
        card: Element,
        sourceUrl: String,
    ): Boolean =
        resolveSourceKey(card, sourceUrl) != null ||
            parseInstallment(
                flag,
                card,
                (resolveRoute(card, sourceUrl) as? AniWorldCardRouteResult.Success)?.route,
            ) != null

    private fun classifyFlag(flag: Element, card: Element): FlagClassification {
        val text = flagText(flag, card)
        detectTrack(text)?.let { return FlagClassification.German(it) }
        return if (isKnownNonGermanMarker(text)) {
            FlagClassification.KnownNonGerman
        } else {
            FlagClassification.Unknown
        }
    }

    private fun isKnownNonGermanMarker(text: String): Boolean {
        val normalized = normalize(text).replace('_', ' ')
        return KNOWN_NON_GERMAN_MARKERS.any { marker ->
            Regex("\\b" + Regex.escape(marker) + "\\b").containsMatchIn(normalized)
        }
    }

    private fun flagText(flag: Element, card: Element): String = listOf(
        flag.attr("title"),
        flag.attr("alt"),
        flag.attr("data-language"),
        flag.attr("data-track"),
        card.attr("title"),
        card.attr("data-language"),
        card.attr("data-track"),
        card.text(),
    ).filter { it.isNotBlank() }.joinToString(" ")

    private fun detectTrack(text: String): LanguageTrack? {
        val normalized = normalize(text)
        if (normalized.contains("untertitel") ||
            normalized.contains("subtitle") ||
            normalized.contains("de_sub") ||
            Regex("\\bsub\\b").containsMatchIn(normalized)
        ) {
            return LanguageTrack.DE_SUB
        }
        if (normalized.contains("deutsche flagge") ||
            normalized.contains("german flag") ||
            normalized.contains("auf deutsch") ||
            normalized.contains("de_dub") ||
            Regex("\\bdeutsch\\b").containsMatchIn(normalized)
        ) {
            return LanguageTrack.DE_DUB
        }
        return null
    }

    private fun parseInstallment(
        flag: Element,
        card: Element,
        route: AniWorldCanonicalRoute?,
    ): Installment? {
        val title = flagText(flag, card)
        val explicit = firstNonBlankAttr(flag, card, "data-installment", "data-episode")
        if (explicit != null) {
            parseEpisodeValue(explicit)?.let {
                if (route?.installment != null && route.installment != it) return null
                return it
            }
        }
        val explicitFilm = firstNonBlankAttr(flag, card, "data-film", "data-movie")
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?.let { Installment.Film(it) }
        if (explicitFilm != null) {
            if (route?.installment != null && route.installment != explicitFilm) return null
            return explicitFilm
        }
        route?.installment?.let { return it }
        val kind = parseReleaseKind(flag, card, route)
        return when (kind) {
            ReleaseKind.MOVIE -> {
                if (route?.kind == AniWorldCanonicalRouteKind.FILMS_OVERVIEW) {
                    null
                } else {
                    val number = Regex("(?i)\\b(?:film|movie)\\s*([0-9]{1,4})\\b")
                        .find(title)?.groupValues?.get(1)?.toIntOrNull()
                    Installment.Film(number)
                }
            }
            ReleaseKind.SPECIAL, ReleaseKind.OVA, ReleaseKind.ONA -> {
                val number = firstNonBlankAttr(flag, card, "data-special")
                    ?.toIntOrNull()
                    ?: Regex("(?i)\\b(?:special|ova|ona)\\s*([0-9]{1,4})\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
                Installment.Special(number)
            }
            ReleaseKind.EPISODE -> {
                val match = Regex("(?i)\\bS[0-9]{1,3}E([0-9]{1,4})\\b").find(title)
                    ?: Regex("(?i)\\b(?:folge|episode|ep\\.?)\\s*([0-9]{1,4})(?:[.,]([0-9]{1,2}))?\\b").find(title)
                if (match == null) {
                    null
                } else {
                    val number = match.groupValues[1].toIntOrNull() ?: return null
                    val fraction = match.groupValues.getOrNull(2)?.toIntOrNull()
                    Installment.Episode(number, fraction)
                }
            }
        }
    }

    private fun parseEpisodeValue(value: String): Installment.Episode? {
        val match = Regex("^\\s*([0-9]{1,4})(?:[.,]([0-9]{1,2}))?\\s*$").find(value)
            ?: return null
        return Installment.Episode(
            number = match.groupValues[1].toInt(),
            fraction = match.groupValues.getOrNull(2)?.toIntOrNull(),
        )
    }

    private fun parseSeason(flag: Element, card: Element): Int? {
        firstNonBlankAttr(flag, card, "data-season", "data-source-season")?.toIntOrNull()?.let { return it }
        val text = flagText(flag, card)
        return Regex("(?i)\\b(?:staffel|season)\\s*([0-9]{1,3})\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)\\bS([0-9]{1,3})E[0-9]{1,4}\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parseReleaseKind(
        flag: Element,
        card: Element,
        route: AniWorldCanonicalRoute? = null,
    ): ReleaseKind {
        if (route?.kind == AniWorldCanonicalRouteKind.FILM ||
            route?.kind == AniWorldCanonicalRouteKind.FILMS_OVERVIEW
        ) {
            return ReleaseKind.MOVIE
        }
        val text = flagText(flag, card)
        return when {
            Regex("(?i)\\b(?:film|movie)\\b").containsMatchIn(text) -> ReleaseKind.MOVIE
            Regex("(?i)\\bova\\b").containsMatchIn(text) -> ReleaseKind.OVA
            Regex("(?i)\\bona\\b").containsMatchIn(text) -> ReleaseKind.ONA
            Regex("(?i)\\bspecial\\b").containsMatchIn(text) -> ReleaseKind.SPECIAL
            else -> ReleaseKind.EPISODE
        }
    }

    private fun parseForecastTime(
        flag: Element,
        card: Element,
        text: String,
    ): DateTimeParseOutcome {
        val timeElement = card.selectFirst("time[datetime], time[data-date], time[data-time]")
        val datetime = firstNonBlankAttr(timeElement, card, "datetime")
        if (datetime != null) {
            parseDateTimeAttribute(datetime, timeElement, card, text)?.let { return it }
        }
        val date = firstNonBlankAttr(timeElement, card, "data-date")
            ?: DATE_REGEX.find(text)?.value
        val time = firstNonBlankAttr(timeElement, card, "data-time")
            ?: TIME_REGEX.find(text)?.value
        val zone = firstNonBlankAttr(timeElement, card, "data-zone", "data-timezone")
        if (date == null || time == null || zone == null) {
            return DateTimeParseOutcome.Failure(
                AniWorldFailureKind.INVALID_DATE_TIME,
                "calendar row is missing a complete date/time/zone",
            )
        }
        return parseLocalDateTime(date, time, zone, text)
    }

    private fun parseDateTimeAttribute(
        datetime: String,
        timeElement: Element?,
        card: Element,
        text: String,
    ): DateTimeParseOutcome? {
        runCatching { OffsetDateTime.parse(datetime, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
            .getOrNull()?.let { value ->
                return DateTimeParseOutcome.Parsed(
                    instant = value.toInstant(),
                    date = value.toLocalDate(),
                    time = value.toLocalTime().format(TIME_OUTPUT),
                    zone = value.offset,
                    approximate = isApproximate(text),
                )
            }
        runCatching { Instant.parse(datetime) }
            .getOrNull()?.let { value ->
                val offsetDateTime = value.atOffset(ZoneOffset.UTC)
                return DateTimeParseOutcome.Parsed(
                    instant = value,
                    date = offsetDateTime.toLocalDate(),
                    time = offsetDateTime.toLocalTime().format(TIME_OUTPUT),
                    zone = ZoneOffset.UTC,
                    approximate = isApproximate(text),
                )
            }
        val date = firstNonBlankAttr(timeElement, card, "data-date")
        val time = firstNonBlankAttr(timeElement, card, "data-time")
        val zone = firstNonBlankAttr(timeElement, card, "data-zone", "data-timezone")
        if (date != null && time != null && zone != null) {
            return parseLocalDateTime(date, time, zone, text)
        }
        return DateTimeParseOutcome.Failure(
            AniWorldFailureKind.INVALID_DATE_TIME,
            "calendar datetime attribute is invalid",
        )
    }

    private fun parseLocalDateTime(
        dateText: String,
        timeText: String,
        zoneText: String,
        context: String,
    ): DateTimeParseOutcome {
        val date = runCatching {
            LocalDate.parse(dateText.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull() ?: runCatching {
            LocalDate.parse(dateText.trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        }.getOrNull() ?: return DateTimeParseOutcome.Failure(
            AniWorldFailureKind.INVALID_DATE_TIME,
            "calendar date is invalid",
        )
        val time = runCatching {
            java.time.LocalTime.parse(timeText.trim(), TIME_INPUT)
        }.getOrNull() ?: return DateTimeParseOutcome.Failure(
            AniWorldFailureKind.INVALID_DATE_TIME,
            "calendar time is invalid",
        )
        val zone = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()
            ?: return DateTimeParseOutcome.Failure(
                AniWorldFailureKind.INVALID_DATE_TIME,
                "calendar source zone is invalid",
            )
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size > 1) {
            return DateTimeParseOutcome.Failure(
                AniWorldFailureKind.AMBIGUOUS_LOCAL_TIME,
                "calendar local time overlaps in the source zone",
            )
        }
        if (offsets.isEmpty()) {
            return DateTimeParseOutcome.Failure(
                AniWorldFailureKind.INVALID_DATE_TIME,
                "calendar local time falls inside a source-zone gap",
            )
        }
        return DateTimeParseOutcome.Parsed(
            instant = local.toInstant(offsets.single()),
            date = date,
            time = time.format(TIME_OUTPUT),
            zone = zone,
            approximate = isApproximate(context),
        )
    }

    private fun findCard(flag: Element): Element {
        val parents = flag.parents()
        return parents.firstOrNull { parent ->
            parent.hasAttr("data-source-key") ||
                parent.hasAttr("data-series-key") ||
                parent.hasAttr("data-episode") ||
                parent.tagName() in CARD_TAGS
        } ?: parents.firstOrNull { it.tagName() == "a" } ?: flag
    }

    private fun resolveRoute(element: Element, sourceUrl: String): AniWorldCardRouteResult =
        AniWorldCardRouteResolver.resolve(
            sourceUrl = sourceUrl,
            sourceKey = firstNonBlankAttr(element, "data-source-key", "data-series-key", "data-series"),
            href = if (element.tagName() == "a") element.attr("href") else element.selectFirst("a[href]")?.attr("href"),
        )

    private fun resolveSourceKey(element: Element, sourceUrl: String): SourceSeriesKey? =
        (resolveRoute(element, sourceUrl) as? AniWorldCardRouteResult.Success)
            ?.route
            ?.slug
            ?.let(::SourceSeriesKey)

    private fun unknownDataTokens(element: Element): List<AniWorldRawToken> =
        element.attributes().asList()
            .filter { it.key.startsWith("data-") && it.key !in KNOWN_DATA_ATTRIBUTES }
            .take(limits.maxUnknownTokens)
            .map {
                AniWorldRawToken(
                    key = it.key,
                    value = it.value.take(limits.maxUnknownTokenValueLength),
                )
            }

    private fun firstNonBlankAttrNullable(element: Element?, vararg names: String): String? =
        element?.let { firstNonBlankAttr(it, *names) }

    private fun firstNonBlankAttr(first: Element?, second: Element?, vararg names: String): String? =
        firstNonBlankAttrNullable(first, *names) ?: firstNonBlankAttrNullable(second, *names)

    private fun firstNonBlankAttr(element: Element, vararg names: String): String? =
        names.asSequence().map { element.attr(it).trim() }.firstOrNull { it.isNotBlank() }

    private fun sourceRoot(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme, uri.authority, null, null, null).toString().trimEnd('/')
    }.getOrDefault(url.substringBefore("//").trimEnd('/'))

    private fun isBlocked(document: Document): Boolean {
        val text = normalize(document.title() + " " + document.body().text())
        return BLOCK_MARKERS.any { text.contains(it) }
    }

    private fun isApproximate(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("~") || normalized.contains("ca.")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun normalize(value: String): String =
        value.lowercase().replace(WHITESPACE_REGEX, " ").trim()

    private sealed interface FlagClassification {
        data class German(val track: LanguageTrack) : FlagClassification
        data object KnownNonGerman : FlagClassification
        data object Unknown : FlagClassification
    }

    private data class ReleaseFlag(
        val flag: Element,
        val card: Element,
        val classification: FlagClassification,
    )

    private sealed interface DateTimeParseOutcome {
        data object NotRequired : DateTimeParseOutcome

        data class Parsed(
            val instant: Instant,
            val date: LocalDate,
            val time: String,
            val zone: ZoneId,
            val approximate: Boolean,
        ) : DateTimeParseOutcome

        data class Failure(
            val kind: AniWorldFailureKind,
            val diagnostic: String,
        ) : DateTimeParseOutcome
    }

    companion object {
        const val DEFAULT_PARSER_VERSION = "aniworld-r2-wp02-v1"
        const val PROVIDER_ID = "aniworld"
        const val DEFAULT_RECENT_URL = "https://aniworld.to/neue-episoden"
        val CARD_TAGS = setOf("article", "li", "tr", "section")
        val KNOWN_NON_GERMAN_MARKERS = setOf(
            "en", "eng", "english", "englisch",
            "fr", "fra", "fre", "french", "französisch", "franzoesisch",
            "es", "spa", "spanish", "spanisch",
            "it", "ita", "italian", "italienisch",
            "pt", "por", "portuguese", "portugiesisch",
            "pl", "pol", "polish", "polnisch",
            "ru", "rus", "russian", "russisch",
            "ja", "jpn", "japanese", "japanisch",
            "ko", "kor", "korean", "koreanisch",
            "zh", "chi", "chinese", "chinesisch",
            "nl", "nld", "dutch", "niederländisch", "niederlaendisch",
            "da", "dan", "danish", "dänisch", "daenisch",
            "sv", "swe", "swedish", "schwedisch",
            "no", "nor", "norwegian", "norwegisch",
            "fi", "fin", "finnish", "finnisch",
            "cs", "ces", "czech", "tschechisch",
            "tr", "tur", "turkish", "türkisch", "tuerkisch",
            "ro", "ron", "romanian", "rumänisch", "rumaenisch",
            "hu", "hun", "hungarian", "ungarisch",
            "el", "ell", "greek", "griechisch",
            "original", "originalton", "om", "ov",
        )
        val KNOWN_DATA_ATTRIBUTES = setOf(
            "data-source-key",
            "data-series-key",
            "data-series",
            "data-episode",
            "data-installment",
            "data-film",
            "data-movie",
            "data-special",
            "data-season",
            "data-source-season",
            "data-language",
            "data-track",
            "data-date",
            "data-time",
            "data-zone",
            "data-timezone",
        )
        val BLOCK_MARKERS = setOf(
            "captcha",
            "cloudflare",
            "access denied",
            "verify you are human",
            "temporarily blocked",
        )
        val DATE_REGEX = Regex("\\b(?:[0-3][0-9]\\.[0-1][0-9]\\.[0-9]{4}|[0-9]{4}-[0-1][0-9]-[0-3][0-9])\\b")
        val TIME_REGEX = Regex("\\b(?:[01][0-9]|2[0-3]):[0-5][0-9]\\b")
        val TIME_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
        val TIME_OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

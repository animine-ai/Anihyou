package com.axiel7.anihyou.release.data.aniworld

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ProviderId
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * V3 parser boundary. It emits the existing normalized observation type and
 * never emits a release decision. Each source adapter selects the evidence
 * meaning after this parser has validated the page.
 */
class AniWorldEvidenceParser(
    private val limits: AniWorldLimits = AniWorldLimits(),
    val parserVersion: String = DEFAULT_PARSER_VERSION,
    private val sourceZone: ZoneId = DEFAULT_SOURCE_ZONE,
) {
    init {
        require(parserVersion.isNotBlank()) { "parser version must not be blank" }
    }

    fun parse(
        html: String,
        sourceUrl: String,
        role: AniWorldPageRole,
        observedAt: Instant,
    ): AniWorldEvidenceParseResult = try {
        parseInternal(html, sourceUrl, role, observedAt)
    } catch (exception: Exception) {
        AniWorldEvidenceParseResult.Failure(
            kind = AniWorldFailureKind.INVALID_CARD,
            diagnostic = "evidence parser failed closed: " +
                (exception::class.simpleName ?: "parser-error"),
        )
    }

    private fun parseInternal(
        html: String,
        sourceUrl: String,
        role: AniWorldPageRole,
        observedAt: Instant,
    ): AniWorldEvidenceParseResult {
        val bodyBytes = html.toByteArray(Charsets.UTF_8).size
        if (bodyBytes > limits.maxBodyBytes) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.OVERSIZED_BODY,
                "evidence body exceeds the bounded body limit",
            )
        }
        if (html.isBlank()) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.EMPTY_STRUCTURE,
                "evidence body is empty",
            )
        }
        val sourceUri = parseSourceUri(sourceUrl)
            ?: return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.REDIRECT_HOST,
                "evidence source URL is not an allowed HTTPS AniWorld URL",
            )
        val sourceHash = sha256(html)
        val document = Jsoup.parse(html, sourceUrl)
        val normalizedDocumentText = normalize(document.title() + " " + document.text())
        if (BLOCK_MARKERS.any(normalizedDocumentText::contains)) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.BLOCKED_PAGE,
                "block-page marker detected",
                sourceHash,
            )
        }
        if (document.selectFirst("main") == null) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.MISSING_PAGE_ANCHOR,
                "required main page anchor is missing",
                sourceHash,
            )
        }
        if (!hasPageAnchor(document, role)) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.MISSING_PAGE_ANCHOR,
                "required page-role anchor is missing or changed",
                sourceHash,
            )
        }

        val cards = releaseCards(document, role)
        if (cards.size > limits.maxCards) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.TOO_MANY_CARDS,
                "release card count exceeds the bounded card limit",
                sourceHash,
            )
        }
        if (cards.isEmpty()) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.EMPTY_STRUCTURE,
                "page contains no bounded release cards",
                sourceHash,
            )
        }
        if (role == AniWorldPageRole.FUTURE_CALENDAR &&
            calendarDates(document, cards).size > limits.maxCalendarDays
        ) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.TOO_MANY_DAYS,
                "calendar day count exceeds the bounded day limit",
                sourceHash,
            )
        }

        val observations = mutableListOf<AniWorldNormalizedObservation>()
        val warnings = mutableListOf<String>()
        var invalidCards = 0
        var unknownTrackCards = 0
        var knownForeignCards = 0

        cards.forEach { card ->
            val tracks = tracksFor(card)
            if (tracks.isEmpty()) {
                if (knownForeign(card.toString())) {
                    knownForeignCards++
                } else {
                    unknownTrackCards++
                    warnings += "ignored card with unknown language marker"
                }
                return@forEach
            }
            val route = when (val resolved = resolveRoute(card, sourceUri)) {
                is AniWorldCardRouteResult.Success -> resolved.route
                is AniWorldCardRouteResult.Failure -> {
                    invalidCards++
                    warnings += resolved.diagnostic
                    return@forEach
                }
            }
            val installment = parseInstallment(card, sourceUri, route)
            if (installment == null) {
                invalidCards++
                warnings += "ignored card with invalid AniWorld identity or installment"
                return@forEach
            }
            val sourceKey = SourceSeriesKey(route.slug)
            val sourceSeason = parseSeason(card) ?: route.season
            val navigationSeason = parseNavigationSeason(card)
            val scheduleCondition = if (role == AniWorldPageRole.POSTPONEMENT) {
                parseScheduleCondition(card.text())
            } else {
                ScheduleCondition.UNKNOWN
            }
            if (role == AniWorldPageRole.POSTPONEMENT &&
                scheduleCondition == ScheduleCondition.UNKNOWN
            ) {
                invalidCards++
                warnings += "ignored postponement card without a schedule condition"
                return@forEach
            }

            val temporal = when (role) {
                AniWorldPageRole.FUTURE_CALENDAR -> parseCalendarTime(
                    card = card,
                    documentText = document.text(),
                )
                AniWorldPageRole.RECENT_CURRENT,
                AniWorldPageRole.DIRECT_EPISODE,
                -> parseSourceReportedTime(card)
                AniWorldPageRole.POSTPONEMENT,
                AniWorldPageRole.SUPPORT_EXPLANATION,
                -> TemporalOutcome.NotPresent
            }
            if (temporal is TemporalOutcome.Failure) {
                invalidCards++
                warnings += temporal.diagnostic
                return@forEach
            }
            val parsedTime = temporal as? TemporalOutcome.Parsed
            val stream = ReleaseStreamKey(
                providerId = ProviderId(AniWorldEvidenceParser.PROVIDER_ID),
                stableSeriesKey = sourceKey,
                releaseKind = parseReleaseKind(card, route),
                sourceSeason = sourceSeason,
                languageTrack = tracks.first(),
            )
            tracks.forEach { track ->
                val trackStream = stream.copy(languageTrack = track)
                observations += AniWorldNormalizedObservation(
                    stream = trackStream,
                    installment = installment,
                    track = track,
                    confirmed = role == AniWorldPageRole.RECENT_CURRENT ||
                        role == AniWorldPageRole.DIRECT_EPISODE,
                    forecastAt = if (role == AniWorldPageRole.FUTURE_CALENDAR) {
                        parsedTime?.instant
                    } else {
                        null
                    },
                    sourceDate = parsedTime?.date,
                    sourceTime = parsedTime?.time,
                    sourceZone = parsedTime?.zone,
                    approximate = parsedTime?.approximate ?: isApproximate(card.text()),
                    sourceRoot = route.canonicalSeriesUrl,
                    rawTokens = emptyList(),
                    navigationSeason = navigationSeason,
                    sourceReportedAt = if (
                        role == AniWorldPageRole.RECENT_CURRENT ||
                        role == AniWorldPageRole.DIRECT_EPISODE
                    ) {
                        parsedTime?.instant
                    } else {
                        null
                    },
                    scheduleCondition = scheduleCondition,
                )
            }
        }

        val denominator = cards.size.coerceAtLeast(1)
        if (invalidCards.toDouble() / denominator > limits.maxInvalidCardRatio) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.EXCESSIVE_INVALID_CARDS,
                "invalid card ratio exceeds the bounded threshold",
                sourceHash,
            )
        }
        if (observations.isEmpty() && unknownTrackCards > 0 && knownForeignCards == 0) {
            return AniWorldEvidenceParseResult.Failure(
                AniWorldFailureKind.UNKNOWN_LANGUAGE_MARKER,
                "no positively identified German language track was found",
                sourceHash,
            )
        }
        if (observations.isEmpty()) {
            return AniWorldEvidenceParseResult.Success(
                observations = emptyList(),
                sourceHash = sourceHash,
                warnings = (warnings + "page yielded no German release evidence").bounded(),
            )
        }

        val unique = linkedMapOf<String, AniWorldNormalizedObservation>()
        observations.forEach { observation ->
            val key = observation.stream.stableKey + "/" + observation.installment.stableKey
            val previous = unique[key]
            if (previous == null) {
                unique[key] = observation
            } else if (
                previous.forecastAt != observation.forecastAt ||
                previous.sourceReportedAt != observation.sourceReportedAt ||
                previous.scheduleCondition != observation.scheduleCondition
            ) {
                return AniWorldEvidenceParseResult.Failure(
                    AniWorldFailureKind.DUPLICATE_IDENTITY_CONFLICT,
                    "duplicate source identity contains conflicting evidence",
                    sourceHash,
                )
            }
        }
        return AniWorldEvidenceParseResult.Success(
            observations = unique.values.toList(),
            sourceHash = sourceHash,
            warnings = warnings.bounded(),
        )
    }

    private fun parseSourceUri(sourceUrl: String): URI? = runCatching {
        URI(sourceUrl).takeIf { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in ALLOWED_HOSTS
        }
    }.getOrNull()

    private fun hasPageAnchor(
        document: org.jsoup.nodes.Document,
        role: AniWorldPageRole,
    ): Boolean {
        val text = normalize(document.selectFirst("main")?.text().orEmpty())
        val heading = document.select("main h1, main h2").joinToString(" ") { normalize(it.text()) }
        return when (role) {
            AniWorldPageRole.RECENT_CURRENT ->
                heading.contains("neue episoden") || text.contains("neue episoden")
            AniWorldPageRole.FUTURE_CALENDAR ->
                heading.contains("animekalender") || text.contains("animekalender")
            AniWorldPageRole.POSTPONEMENT ->
                heading.contains("verschob") || heading.contains("verschieb") ||
                    text.contains("verschob") || text.contains("verschieb")
            AniWorldPageRole.DIRECT_EPISODE ->
                releaseCards(document, role).isNotEmpty() &&
                    (text.contains("folge") || text.contains("episode") ||
                        text.contains("veröffentlicht") || text.contains("stream"))
            AniWorldPageRole.SUPPORT_EXPLANATION -> false
        }
    }

    private fun releaseCards(
        document: org.jsoup.nodes.Document,
        role: AniWorldPageRole,
    ): List<Element> {
        val markers = document.select(
            "main img.flag, main [data-track], main [data-language], " +
                "main [data-language-track], main [data-release-track]",
        )
        val cards = linkedSetOf<Element>()
        markers.forEach { marker -> cards += findCard(marker) }
        if (cards.isEmpty()) {
            document.select(
                "main article, main li, main tr, main section, " +
                    "main [data-source-key], main [data-series-key], main [data-episode], " +
                    "main [data-installment], main [data-track]",
            ).forEach { cards += it }
        }
        if (cards.isEmpty() && role == AniWorldPageRole.DIRECT_EPISODE) {
            val main = document.selectFirst("main")
            if (main != null) cards.add(main)
        }
        return cards.toList()
    }

    private fun findCard(marker: Element): Element {
        val parents = marker.parents()
        return parents.firstOrNull { parent ->
            parent.hasAttr("data-source-key") ||
                parent.hasAttr("data-series-key") ||
                parent.hasAttr("data-series") ||
                parent.hasAttr("data-episode") ||
                parent.hasAttr("data-installment") ||
                parent.tagName() in CARD_TAGS
        } ?: marker
    }

    private fun tracksFor(card: Element): List<LanguageTrack> {
        val markers = card.select(
            "img.flag, [data-track], [data-language], [data-language-track], [data-release-track]",
        ).ifEmpty { listOf(card) }
        val tracks = linkedSetOf<LanguageTrack>()
        markers.forEach { marker ->
            val markerText = listOf(
                marker.attr("data-track"),
                marker.attr("data-language"),
                marker.attr("data-language-track"),
                marker.attr("data-release-track"),
                marker.attr("title"),
                marker.attr("alt"),
                marker.text(),
            ).filter(String::isNotBlank).joinToString(" ")
            val normalized = normalize(markerText)
            if (normalized.contains("de_sub") || normalized.contains("de-sub") ||
                normalized.contains("untertitel") || normalized.contains("subtitle") ||
                Regex("\\bsub\\b").containsMatchIn(normalized)
            ) {
                tracks += LanguageTrack.DE_SUB
            }
            if (normalized.contains("de_dub") || normalized.contains("de-dub") ||
                normalized.contains("deutsche flagge") || normalized.contains("german flag") ||
                normalized.contains("auf deutsch") || normalized.contains("synchron") ||
                Regex("\\bdub\\b").containsMatchIn(normalized)
            ) {
                tracks += LanguageTrack.DE_DUB
            }
        }
        return tracks.toList()
    }

    private fun knownForeign(text: String): Boolean {
        val normalized = normalize(text).replace('_', ' ')
        return KNOWN_FOREIGN_MARKERS.any { marker ->
            Regex("\\b" + Regex.escape(marker) + "\\b").containsMatchIn(normalized)
        }
    }

    private fun resolveRoute(card: Element, sourceUri: URI): AniWorldCardRouteResult =
        AniWorldCardRouteResolver.resolve(
            sourceUrl = sourceUri.toString(),
            sourceKey = firstNonBlankAttr(
                card,
                "data-source-key",
                "data-series-key",
                "data-series",
                "data-slug",
            ),
            href = if (card.tagName() == "a") card.attr("href") else card.selectFirst("a[href]")?.attr("href"),
        )

    private fun resolveSourceKey(card: Element, sourceUri: URI): SourceSeriesKey? =
        (resolveRoute(card, sourceUri) as? AniWorldCardRouteResult.Success)
            ?.route
            ?.slug
            ?.let(::SourceSeriesKey)

    private fun parseInstallment(
        card: Element,
        sourceUri: URI,
        route: AniWorldCanonicalRoute,
    ): Installment? {
        val text = normalize(card.text())
        val explicitEpisode = firstNonBlankAttr(card, "data-installment", "data-episode", "data-episode-number")
            ?.let { value -> parseEpisodeValue(value) }
        if (explicitEpisode != null) {
            if (route.installment != null && route.installment != explicitEpisode) return null
            return explicitEpisode
        }
        val explicitFilm = firstNonBlankAttr(card, "data-film", "data-movie")
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
            ?.let { Installment.Film(it) }
        if (explicitFilm != null) {
            if (route.installment != null && route.installment != explicitFilm) return null
            return explicitFilm
        }
        route.installment?.let { return it }
        val kind = parseReleaseKind(card, route)
        return when (kind) {
            ReleaseKind.MOVIE -> if (route.kind == AniWorldCanonicalRouteKind.FILMS_OVERVIEW) null else Installment.Film()
            ReleaseKind.SPECIAL,
            ReleaseKind.OVA,
            ReleaseKind.ONA,
            -> Installment.Special(firstNonBlankAttr(card, "data-special")?.toIntOrNull())
            ReleaseKind.EPISODE -> {
                val match = Regex("(?i)\\bS[0-9]{1,3}E([0-9]{1,4})\\b").find(text)
                    ?: Regex("(?i)\\b(?:folge|episode|ep\\.?|e)\\s*([0-9]{1,4})(?:[.,]([0-9]{1,2}))?\\b")
                        .find(text)
                    ?: Regex("(?i)/episode[-/]([0-9]{1,4})").find(sourceUri.path.orEmpty())
                if (match == null) {
                    null
                } else {
                    Installment.Episode(
                        number = match.groupValues[1].toIntOrNull() ?: return null,
                        fraction = match.groupValues.getOrNull(2)?.toIntOrNull(),
                    )
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

    private fun parseSeason(card: Element): Int? =
        firstNonBlankAttr(card, "data-source-season", "data-season")?.toIntOrNull()
            ?: Regex("(?i)\\b(?:staffel|season|s)\\s*([0-9]{1,3})").find(card.text())
                ?.groupValues?.get(1)?.toIntOrNull()

    private fun parseNavigationSeason(card: Element): Int? =
        firstNonBlankAttr(card, "data-navigation-season", "data-nav-season")?.toIntOrNull()

    private fun parseReleaseKind(
        card: Element,
        route: AniWorldCanonicalRoute? = null,
    ): ReleaseKind {
        if (route?.kind == AniWorldCanonicalRouteKind.FILM ||
            route?.kind == AniWorldCanonicalRouteKind.FILMS_OVERVIEW
        ) {
            return ReleaseKind.MOVIE
        }
        val text = normalize(card.text())
        return when {
            Regex("\\b(?:film|movie)\\b").containsMatchIn(text) -> ReleaseKind.MOVIE
            Regex("\\bova\\b").containsMatchIn(text) -> ReleaseKind.OVA
            Regex("\\bona\\b").containsMatchIn(text) -> ReleaseKind.ONA
            Regex("\\bspecial\\b").containsMatchIn(text) -> ReleaseKind.SPECIAL
            else -> ReleaseKind.EPISODE
        }
    }

    private fun parseScheduleCondition(text: String): ScheduleCondition {
        val normalized = normalize(text)
        return when {
            HIATUS_MARKERS.any(normalized::contains) -> ScheduleCondition.HIATUS
            DELAY_MARKERS.any(normalized::contains) -> ScheduleCondition.DELAYED
            else -> ScheduleCondition.UNKNOWN
        }
    }

    private fun parseCalendarTime(
        card: Element,
        documentText: String,
    ): TemporalOutcome {
        val timeElement = card.selectFirst("time[datetime], time[data-date], time[data-time]")
        return parseTemporal(
            card = card,
            timeElement = timeElement,
            fallbackText = documentText,
            required = true,
        )
    }

    private fun parseSourceReportedTime(card: Element): TemporalOutcome {
        val timeElement = card.selectFirst(
            "time[datetime], time[data-date], time[data-time], " +
                "[data-source-reported-at], [data-published-at], [data-release-at]",
        )
        val hasTextMarker = SOURCE_TIME_MARKERS.any(normalize(card.text())::contains)
        return if (timeElement != null || hasTextMarker) {
            parseTemporal(card, timeElement, card.text(), required = true)
        } else {
            TemporalOutcome.NotPresent
        }
    }

    private fun parseTemporal(
        card: Element,
        timeElement: Element?,
        fallbackText: String,
        required: Boolean,
    ): TemporalOutcome {
        val datetime = firstNonBlankAttr(
            timeElement,
            card,
            "datetime",
            "data-source-reported-at",
            "data-published-at",
            "data-release-at",
        )
        if (datetime != null) {
            parseDateTimeAttribute(datetime, timeElement, card, fallbackText)?.let { return it }
        }
        val date = firstNonBlankAttr(
            timeElement,
            card,
            "data-date",
            "data-published-date",
            "data-release-date",
        ) ?: DATE_REGEX.find(fallbackText)?.value
        val time = firstNonBlankAttr(
            timeElement,
            card,
            "data-time",
            "data-published-time",
            "data-release-time",
        ) ?: TIME_REGEX.find(fallbackText)?.value
        val zone = firstNonBlankAttr(
            timeElement,
            card,
            "data-zone",
            "data-timezone",
            "data-source-zone",
        ) ?: sourceZone.id
        if (date != null && time != null) {
            return parseLocalDateTime(date, time, zone, fallbackText)
        }
        return if (required) {
            TemporalOutcome.Failure(
                "source time is present but not a valid date/time",
            )
        } else {
            TemporalOutcome.NotPresent
        }
    }

    private fun parseDateTimeAttribute(
        datetime: String,
        timeElement: Element?,
        card: Element,
        context: String,
    ): TemporalOutcome? {
        runCatching { OffsetDateTime.parse(datetime, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
            .getOrNull()?.let { value -> return parsed(value.toInstant(), value.toLocalDate(), value.toLocalTime(), value.offset, context) }
        runCatching { Instant.parse(datetime) }
            .getOrNull()?.let { value ->
                val local = value.atOffset(ZoneOffset.UTC)
                return parsed(value, local.toLocalDate(), local.toLocalTime(), ZoneOffset.UTC, context)
            }
        val date = firstNonBlankAttr(timeElement, card, "data-date", "data-published-date", "data-release-date")
        val time = firstNonBlankAttr(timeElement, card, "data-time", "data-published-time", "data-release-time")
        if (date != null && time != null) {
            return parseLocalDateTime(
                date,
                time,
                firstNonBlankAttr(timeElement, card, "data-zone", "data-timezone", "data-source-zone")
                    ?: sourceZone.id,
                context,
            )
        }
        return null
    }

    private fun parsed(
        instant: Instant,
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId,
        context: String,
    ): TemporalOutcome = TemporalOutcome.Parsed(
        instant = instant,
        date = date,
        time = time.format(TIME_OUTPUT),
        zone = zone,
        approximate = isApproximate(context),
    )

    private fun parseLocalDateTime(
        dateText: String,
        timeText: String,
        zoneText: String,
        context: String,
    ): TemporalOutcome {
        val date = runCatching { LocalDate.parse(dateText.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }
            .getOrNull()
            ?: runCatching { LocalDate.parse(dateText.trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy")) }
                .getOrNull()
            ?: return TemporalOutcome.Failure("source date is invalid")
        val time = runCatching { LocalTime.parse(timeText.trim(), TIME_INPUT) }.getOrNull()
            ?: return TemporalOutcome.Failure("source time is invalid")
        val zone = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()
            ?: return TemporalOutcome.Failure("source timezone is invalid")
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size > 1) return TemporalOutcome.Failure("source local time is ambiguous")
        if (offsets.isEmpty()) return TemporalOutcome.Failure("source local time falls in a DST gap")
        return parsed(local.toInstant(offsets.single()), date, time, zone, context)
    }

    private fun calendarDates(
        document: org.jsoup.nodes.Document,
        cards: List<Element>,
    ): Set<String> = (document.select("time") + cards).mapNotNull { element ->
        firstNonBlankAttr(element, "datetime", "data-date")?.substringBefore('T')
            ?: DATE_REGEX.find(element.text())?.value
    }.toSet()

    private fun firstNonBlankAttr(
        first: Element?,
        second: Element,
        vararg names: String,
    ): String? = firstNonBlankAttr(first, *names) ?: firstNonBlankAttr(second, *names)

    private fun firstNonBlankAttr(element: Element?, vararg names: String): String? =
        element?.let { value ->
            names.asSequence().map { value.attr(it).trim() }.firstOrNull(String::isNotBlank)
        }

    private fun isApproximate(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("~") || normalized.contains("ca.") || normalized.contains("circa")
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(WHITESPACE_REGEX, " ").trim()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun List<String>.bounded(): List<String> =
        distinct().map { it.take(MAX_DIAGNOSTIC_LENGTH) }.take(MAX_WARNING_COUNT)

    private sealed interface TemporalOutcome {
        data object NotPresent : TemporalOutcome

        data class Parsed(
            val instant: Instant,
            val date: LocalDate,
            val time: String,
            val zone: ZoneId,
            val approximate: Boolean,
        ) : TemporalOutcome

        data class Failure(val diagnostic: String) : TemporalOutcome
    }

    companion object {
        const val DEFAULT_PARSER_VERSION = "aniworld-v3-wp02-evidence-v1"
        const val PROVIDER_ID = "aniworld"
        val DEFAULT_SOURCE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val ALLOWED_HOSTS: Set<String> = setOf("aniworld.to", "www.aniworld.to")
        val CARD_TAGS: Set<String> = setOf("article", "li", "tr", "section")
        val BLOCK_MARKERS: Set<String> = setOf(
            "captcha",
            "cloudflare",
            "access denied",
            "verify you are human",
            "temporarily blocked",
        )
        val DELAY_MARKERS: Set<String> = setOf(
            "verschoben",
            "verschiebung",
            "verzögert",
            "delayed",
        )
        val HIATUS_MARKERS: Set<String> = setOf(
            "hiatus",
            "pausiert",
            "unbekannter rückkehrtermin",
            "unbekannte rückkehr",
            "keiner rückkehrtermin",
            "kein rückkehrtermin",
            "auf unbestimmte zeit",
        )
        val SOURCE_TIME_MARKERS: Set<String> = setOf(
            "veröffentlicht bei uns",
            "released at",
            "published at",
        )
        val KNOWN_FOREIGN_MARKERS: Set<String> = setOf(
            "english", "englisch", "french", "französisch", "spanish", "spanisch",
            "italian", "italienisch", "japanese", "japanisch", "originalton", "original",
        )
        val DATE_REGEX = Regex("\\b(?:[0-3][0-9]\\.[0-1][0-9]\\.[0-9]{4}|[0-9]{4}-[0-1][0-9]-[0-3][0-9])\\b")
        val TIME_REGEX = Regex("\\b(?:[01][0-9]|2[0-3]):[0-5][0-9]\\b")
        val TIME_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
        val TIME_OUTPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val WHITESPACE_REGEX = Regex("\\s+")
        const val MAX_WARNING_COUNT = 32
        const val MAX_DIAGNOSTIC_LENGTH = 256
    }
}

sealed interface AniWorldEvidenceParseResult {
    data class Success(
        val observations: List<AniWorldNormalizedObservation>,
        val sourceHash: String,
        val warnings: List<String> = emptyList(),
    ) : AniWorldEvidenceParseResult

    data class Failure(
        val kind: AniWorldFailureKind,
        val diagnostic: String,
        val sourceHash: String? = null,
    ) : AniWorldEvidenceParseResult
}

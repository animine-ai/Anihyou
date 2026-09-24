package com.axiel7.anihyou.release.data.malsync

import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.data.aniworld.AniWorldCanonicalRouteKind
import com.axiel7.anihyou.release.data.aniworld.AniWorldCanonicalRouteParser
import com.axiel7.anihyou.release.data.aniworld.AniWorldCanonicalRouteResult
import java.net.URI
import java.util.Locale

/** Native MALSync identifier semantics for AniWorld mapping subjects. */
data class MALSyncIdentifier(
    val subject: AniWorldMappingSubject,
    val value: String,
    val provenance: MALSyncIdentifierProvenance,
) {
    init {
        require(value.isNotBlank()) { "MALSync identifier must not be blank" }
        require(value.length <= MAX_IDENTIFIER_LENGTH) {
            "MALSync identifier exceeds the bounded length"
        }
    }

    companion object {
        const val MAX_IDENTIFIER_LENGTH = 512
    }
}

data class MALSyncIdentifierProvenance(
    val adapterRevision: String,
    val semanticReference: String,
) {
    init {
        require(adapterRevision.isNotBlank()) { "MALSync adapter revision must not be blank" }
        require(semanticReference.isNotBlank()) { "MALSync semantic reference must not be blank" }
    }
}

/** Positive title evidence from the AniWorld film page itself. */
data class MALSyncFilmTitleEvidence(
    val subject: AniWorldMappingSubject.Film,
    val title: String,
    val sourceUrl: String,
    val sourceHash: String,
    val parserVersion: String,
) {
    init {
        require(title.isNotBlank()) { "film title evidence must not be blank" }
        require(sourceHash.isNotBlank()) { "film title evidence hash must not be blank" }
        require(parserVersion.isNotBlank()) { "film title parser version must not be blank" }
        val uri = runCatching { URI(sourceUrl) }.getOrNull()
        require(uri?.scheme.equals("https", ignoreCase = true)) {
            "film title evidence must use HTTPS"
        }
        require(uri?.host.equals("aniworld.to", ignoreCase = true) ||
            uri?.host.equals("www.aniworld.to", ignoreCase = true)) {
            "film title evidence must come from AniWorld"
        }
        require(uri?.rawPath?.contains("/anime/stream/") == true &&
            uri.rawPath.contains("/filme/")) {
            "film title evidence must come from a direct AniWorld film page"
        }
    }
}

sealed interface MALSyncIdentifierResult {
    data class Supported(val identifier: MALSyncIdentifier) : MALSyncIdentifierResult

    data class Unsupported(
        val subject: AniWorldMappingSubject,
        val diagnostic: String,
    ) : MALSyncIdentifierResult {
        init { require(diagnostic.isNotBlank()) { "unsupported diagnostic must not be blank" } }
    }
}

object MALSyncAniWorldIdentifierFactory {
    private const val ADAPTER_REVISION = "aniworld-native-identifier-v1"
    private const val SEMANTIC_REFERENCE =
        "MALSync src/pages/Aniworld/main.ts identifier semantics"

    fun create(
        subject: AniWorldMappingSubject,
        filmTitleEvidence: MALSyncFilmTitleEvidence? = null,
    ): MALSyncIdentifierResult = when (subject) {
        is AniWorldMappingSubject.Season -> MALSyncIdentifierResult.Supported(
            MALSyncIdentifier(
                subject = subject,
                value = "${subject.siteIdentifier.slug}?s=staffel-${subject.navigationSeason}",
                provenance = provenance(),
            ),
        )

        is AniWorldMappingSubject.Film -> {
            val evidence = filmTitleEvidence
                ?: return MALSyncIdentifierResult.Unsupported(
                    subject,
                    "film identifier requires positive direct-page title evidence",
                )
            if (evidence.subject != subject) {
                return MALSyncIdentifierResult.Unsupported(
                    subject,
                    "film title evidence belongs to a different AniWorld film subject",
                )
            }
            when (val route = AniWorldCanonicalRouteParser.parse(evidence.sourceUrl)) {
                is AniWorldCanonicalRouteResult.Failure -> {
                    return MALSyncIdentifierResult.Unsupported(
                        subject,
                        "film title evidence route is not a canonical AniWorld film page",
                    )
                }
                is AniWorldCanonicalRouteResult.Success -> {
                    val film = route.route.installment as? Installment.Film
                    if (route.route.kind != AniWorldCanonicalRouteKind.FILM ||
                        route.route.slug != subject.siteIdentifier.slug ||
                        film?.number != subject.filmNumber
                    ) {
                        return MALSyncIdentifierResult.Unsupported(
                            subject,
                            "film title evidence route does not match the requested AniWorld film",
                        )
                    }
                }
            }
            val title = normalizeFilmTitle(evidence.title)
                ?: return MALSyncIdentifierResult.Unsupported(
                    subject,
                    "film title evidence is empty after conservative normalization",
                )
            MALSyncIdentifierResult.Supported(
                MALSyncIdentifier(
                    subject = subject,
                    value = "${subject.siteIdentifier.slug}?m=$title",
                    provenance = provenance(),
                ),
            )
        }
    }

    private fun provenance(): MALSyncIdentifierProvenance = MALSyncIdentifierProvenance(
        adapterRevision = ADAPTER_REVISION,
        semanticReference = SEMANTIC_REFERENCE,
    )

    /**
     * Conservative equivalent of the native MALSync film-title tokenization.
     * It does not search a catalogue or infer a title from a slug.
     */
    private fun normalizeFilmTitle(raw: String): String? {
        val normalized = raw
            .replace(Regex("\\[[A-Za-z0-9]+\\]"), "")
            .trim()
            .split(' ', limit = Int.MAX_VALUE)
            .joinToString("-")
            .lowercase(Locale.ROOT)
        return normalized.takeIf { it.isNotBlank() && it.length <= MAX_FILM_TITLE_LENGTH }
    }

    private const val MAX_FILM_TITLE_LENGTH = 256
}

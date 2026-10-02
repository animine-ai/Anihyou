package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionExecutionReceipt
import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ProviderId
import com.axiel7.anihyou.release.core.extension.ObservationClaimKind
import com.axiel7.anihyou.release.core.extension.ObservationInstallmentKind
import com.axiel7.anihyou.release.core.extension.ObservationTrack
import com.axiel7.anihyou.release.core.extension.ProviderObservationV1
import com.axiel7.anihyou.release.core.extension.SourceRole
import com.axiel7.anihyou.release.core.model.AniWorldIdentitySourceType
import com.axiel7.anihyou.release.core.model.AniWorldSiteIdentifier
import com.axiel7.anihyou.release.core.model.ConfidenceVector
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import com.axiel7.anihyou.release.core.sync.ReleaseSourceTimePolicy
import com.axiel7.anihyou.release.data.ReleaseEvidenceFingerprintV2
import java.math.BigDecimal
import java.time.Instant

/**
 * Host policy, supplied from authenticated release configuration. An installed package or its
 * navigation declaration can never add entries to this list.
 */
data class ApprovedExtensionAuthorityTuple(
    val publisherId: String,
    val signingKeyId: String,
    val extensionId: String,
    val providerId: String,
    val roles: Set<SourceRole>,
) {
    init {
        require(publisherId.isNotBlank() && signingKeyId.isNotBlank() && roles.isNotEmpty())
        ExtensionId.parse(extensionId)
        ProviderId.parse(providerId)
    }
}

class ExtensionEvidenceAuthorityAdapter(
    private val approved: Set<ApprovedExtensionAuthorityTuple>,
) {
    fun observationPolicy(): ExtensionObservationPolicy = ExtensionObservationPolicy { extension, observation ->
        observation.extensionId == extension.extensionId &&
            observation.providerId == extension.providerId && approved.any { tuple ->
                tuple.publisherId == extension.publisherId && tuple.signingKeyId == extension.signingKeyId &&
                    tuple.extensionId == extension.extensionId.value &&
                    tuple.providerId == extension.providerId.value && observation.sourceRole in tuple.roles
            }
    }

    /** A policy with no provisioned tuple is deliberately inert. */
    fun permits(receipt: ExtensionExecutionReceipt, role: SourceRole): Boolean =
        approved.any { tuple ->
            tuple.publisherId == receipt.publisherId &&
                tuple.signingKeyId == receipt.signingKeyId &&
                tuple.extensionId == receipt.extensionId.value &&
                tuple.providerId == receipt.providerId.value &&
                role in tuple.roles
        }

    /**
     * The coordinator has already checked the wire envelope. We bind every claim once more to
     * host-produced response provenance before projecting it into the historical Evidence codec.
     * Unbound/unsupported claims never produce authority or negative coverage.
     */
    fun project(completed: ExtensionHostResult.Completed): List<ReleaseEvidence> {
        val receipt = completed.receipt
        val provenance = completed.responseProvenance.associateBy { it.requestId }
        return completed.observations.mapNotNull { observation ->
            if (!permits(receipt, observation.sourceRole) ||
                observation.extensionId != receipt.extensionId ||
                observation.providerId != receipt.providerId) return@mapNotNull null
            val source = provenance[observation.requestId] ?: return@mapNotNull null
            if (source.finalUrl != observation.sourceUrl ||
                source.sourceHash != observation.sourceHash ||
                source.httpStatus !in 200..299) return@mapNotNull null
            convert(observation, Instant.parse(receipt.completedAt))
        }.distinctBy { it.id }
    }

    private fun convert(observation: ProviderObservationV1, observedAt: Instant): ReleaseEvidence? {
        val (sourceType, evidenceType) = when (observation.sourceRole) {
            SourceRole.CALENDAR -> if (observation.claimKind == ObservationClaimKind.FORECAST)
                ReleaseSourceType.ANIWORLD_CALENDAR to ReleaseEvidenceType.FORECAST else return null
            SourceRole.RECENT -> if (observation.claimKind == ObservationClaimKind.RELEASE_LISTING)
                ReleaseSourceType.ANIWORLD_RECENT to ReleaseEvidenceType.CONFIRMATION else return null
            SourceRole.DIRECT -> if (observation.claimKind == ObservationClaimKind.DIRECT_AVAILABILITY)
                ReleaseSourceType.ANIWORLD_DIRECT_PAGE to ReleaseEvidenceType.VERIFICATION else return null
            // A textual postponement row needs separate host-side identity binding.
            SourceRole.POSTPONEMENT -> return null
        }
        val slug = observation.providerSeriesKey ?: return null
        // One explicit compatibility representation; titles and arbitrary URLs cannot select identity.
        if (!slug.matches(Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}"))) return null
        val site = runCatching {
            AniWorldSiteIdentifier(slug = slug, sourceType = when (sourceType) {
                ReleaseSourceType.ANIWORLD_CALENDAR -> AniWorldIdentitySourceType.CALENDAR
                ReleaseSourceType.ANIWORLD_RECENT -> AniWorldIdentitySourceType.RECENT
                else -> AniWorldIdentitySourceType.DIRECT_PAGE
            }, firstSeenAt = observedAt, lastValidatedAt = observedAt,
                sourceHash = observation.sourceHash, parserVersion = PARSER_VERSION)
        }.getOrNull() ?: return null
        val track = when (observation.track) {
            ObservationTrack.DE_SUB -> LanguageTrack.DE_SUB
            ObservationTrack.DE_DUB -> LanguageTrack.DE_DUB
            ObservationTrack.UNKNOWN -> return null
        }
        val installment = installment(observation) ?: return null
        if (installment is Installment.Episode && observation.sourceSeason == null) return null
        if (installment is Installment.Film && observation.sourceSeason != null) return null
        val reportedAt = observation.parsedTimestamp?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: if (sourceType == ReleaseSourceType.ANIWORLD_CALENDAR) ExtensionCalendarTimePolicy.normalize(
                observation.sourceDateText, observation.sourceTimeText, ReleaseSourceTimePolicy.ANI_WORLD_ZONE,
            ) else null
        // Host normalization of the guest's calendar fields remains an estimate. It cannot
        // become precise publication evidence or establish precise negative-coverage expectations.
        val approximate = observation.approximate ||
            (sourceType == ReleaseSourceType.ANIWORLD_CALENDAR && observation.parsedTimestamp == null)
        val identityKey = listOf(site.stableKey,
            observation.sourceSeason?.toString() ?: "source-season:unknown",
            observation.navigationSeason?.toString() ?: "navigation-season:unknown",
            installment.stableKey, track.name).joinToString("/")
        val id = ReleaseEvidenceFingerprintV2.evidenceId(sourceType, observation.sourceHash,
            identityKey, evidenceType, reportedAt, approximate, ScheduleCondition.UNKNOWN)
        return ReleaseEvidence(id, sourceType, observation.sourceUrl, observation.sourceHash,
            PARSER_VERSION, observedAt, reportedAt, approximate, site,
            observation.sourceSeason, observation.navigationSeason, installment, track,
            evidenceType, ScheduleCondition.UNKNOWN,
            ConfidenceVector(1.0, 1.0, 1.0, 1.0, if (reportedAt != null) 1.0 else 0.75))
    }

    private fun installment(observation: ProviderObservationV1): Installment? {
        val raw = observation.installment.number ?: return null
        val number = runCatching { BigDecimal(raw).stripTrailingZeros() }.getOrNull() ?: return null
        if (number.signum() < 0 || number.precision() > 9) return null
        return when (observation.installment.kind) {
            ObservationInstallmentKind.EPISODE -> {
                if (number.scale() > 2) return null
                val whole = runCatching { number.toBigInteger().intValueExact() }.getOrNull() ?: return null
                val fractionalText = number.toPlainString().substringAfter('.', "")
                // The legacy codec writes fraction=5 as ".5", not ".05".
                if (fractionalText.length > 1 && fractionalText.startsWith('0')) return null
                val fraction = fractionalText.toIntOrNull()
                runCatching { Installment.Episode(whole, fraction) }.getOrNull()
            }
            ObservationInstallmentKind.FILM -> runCatching {
                Installment.Film(number.intValueExact().also { require(it > 0) })
            }.getOrNull()
            else -> null
        }
    }

    private companion object { const val PARSER_VERSION = "aniworld-v3-extension-v1" }
}

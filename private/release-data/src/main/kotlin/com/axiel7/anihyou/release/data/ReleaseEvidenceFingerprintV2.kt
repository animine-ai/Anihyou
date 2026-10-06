package com.axiel7.anihyou.release.data

import com.axiel7.anihyou.release.core.model.ReleaseEvidence
import com.axiel7.anihyou.release.core.model.ReleaseEvidenceType
import com.axiel7.anihyou.release.core.model.ReleaseSourceType
import com.axiel7.anihyou.release.core.model.ScheduleCondition
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** The single v2 evidence fingerprint codec shared by IDs, persistence and migrations. */
internal object ReleaseEvidenceFingerprintV2 {
    private const val VERSION = "evidence-id-v2"
    private const val ID_PREFIX = "aniworld-v3"
    private const val HEX = "0123456789abcdef"

    fun compute(evidence: ReleaseEvidence): String = compute(
        sourceType = evidence.sourceType,
        sourceHash = evidence.sourceHash,
        identityKey = evidence.identityKey,
        evidenceType = evidence.evidenceType,
        sourceReportedAt = evidence.sourceReportedAt,
        approximateTime = evidence.approximateTime,
        scheduleCondition = evidence.scheduleCondition,
    )

    fun compute(
        sourceType: ReleaseSourceType,
        sourceHash: String,
        identityKey: String,
        evidenceType: ReleaseEvidenceType,
        sourceReportedAt: Instant?,
        approximateTime: Boolean,
        scheduleCondition: ScheduleCondition,
    ): String {
        val payload = ByteArrayOutputStream()
        listOf(
            VERSION,
            sourceType.name,
            sourceHash,
            identityKey,
            evidenceType.name,
            sourceReportedAt?.toString(),
            approximateTime.toString(),
            scheduleCondition.name,
        ).forEach { field ->
            val encoded = encodeField(field)
            payload.write(encoded, 0, encoded.size)
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    fun evidenceId(evidence: ReleaseEvidence): String =
        "$ID_PREFIX:${evidence.sourceType.name}:${compute(evidence)}"

    fun evidenceId(
        sourceType: ReleaseSourceType,
        sourceHash: String,
        identityKey: String,
        evidenceType: ReleaseEvidenceType,
        sourceReportedAt: Instant?,
        approximateTime: Boolean,
        scheduleCondition: ScheduleCondition,
    ): String = "$ID_PREFIX:$sourceType:${compute(
        sourceType = sourceType,
        sourceHash = sourceHash,
        identityKey = identityKey,
        evidenceType = evidenceType,
        sourceReportedAt = sourceReportedAt,
        approximateTime = approximateTime,
        scheduleCondition = scheduleCondition,
    )}"

    fun isValidEvidenceId(evidence: ReleaseEvidence): Boolean =
        evidence.id == evidenceId(evidence)

    fun isSemanticallyCompatible(left: ReleaseEvidence, right: ReleaseEvidence): Boolean =
        left.sourceType == right.sourceType &&
            left.sourceHash == right.sourceHash &&
            left.identityKey == right.identityKey &&
            left.siteIdentifier?.stableKey == right.siteIdentifier?.stableKey &&
            left.sourceSeason == right.sourceSeason &&
            left.navigationSeason == right.navigationSeason &&
            left.installment == right.installment &&
            left.languageTrack == right.languageTrack &&
            left.evidenceType == right.evidenceType &&
            left.sourceReportedAt == right.sourceReportedAt &&
            left.approximateTime == right.approximateTime &&
            left.scheduleCondition == right.scheduleCondition &&
            left.sourceUrl == right.sourceUrl &&
            left.confidence == right.confidence

    fun isV2Id(id: String): Boolean = id.startsWith("$ID_PREFIX:")

    fun isValidFingerprint(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    internal fun encodeField(value: String?): ByteArray {
        if (value == null) return "-1:".toByteArray(StandardCharsets.US_ASCII)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val prefix = "${bytes.size}:".toByteArray(StandardCharsets.US_ASCII)
        return prefix + bytes
    }
}

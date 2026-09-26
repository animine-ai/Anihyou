package com.axiel7.anihyou.release.core.model

/** A versioned release key, independent of the immutable v11 observation key. */
data class CanonicalReleaseIdentity private constructor(
    val seriesPath: String,
    val installment: Installment,
    val sourceSeason: Int?,
    val track: LanguageTrack,
) {
    val bucketKey: String = encode("canonical-bucket-v1", "aniworld", seriesPath,
        installment.stableKey)
    val key: String = encode(
        "canonical-release-v1", "aniworld", seriesPath,
        when (installment) {
            is Installment.Episode -> "EPISODE"
            is Installment.Film -> "FILM"
            is Installment.Special -> error("Special is not a supported release identity")
        },
        sourceSeason?.toString(),
        when (installment) {
            is Installment.Episode -> installment.number.toString()
            is Installment.Film -> installment.number.toString()
            else -> error("unsupported installment")
        },
        (installment as? Installment.Episode)?.fraction?.toString(), track.name,
    )

    companion object {
        fun bucketOf(evidence: ReleaseEvidence): String? {
            val path = evidence.siteIdentifier?.canonicalSeriesPath ?: return null
            if (evidence.identityCompleteness() == ReleaseIdentityCompleteness.NON_BINDABLE) return null
            return encode("canonical-bucket-v1", "aniworld", path, evidence.installment.stableKey)
        }

        fun decode(key: String): CanonicalReleaseIdentity? {
            val parts = ArrayList<String?>()
            var offset = 0
            while (offset < key.length && parts.size < 8) {
                val colon = key.indexOf(':', offset)
                if (colon < 0 || colon - offset > 8) return null
                val size = key.substring(offset, colon).toIntOrNull() ?: return null
                if (size < -1 || size > 2048) return null
                offset = colon + 1
                if (size == -1) { parts += null; continue }
                var end = offset
                var bytes = 0
                while (end < key.length && bytes < size) {
                    val c = key[end]
                    if (Character.isHighSurrogate(c)) {
                        if (end + 1 >= key.length || !Character.isLowSurrogate(key[end + 1])) return null
                        bytes += key.substring(end, end + 2).toByteArray(Charsets.UTF_8).size
                        end += 2
                    } else {
                        if (Character.isLowSurrogate(c)) return null
                        bytes += c.toString().toByteArray(Charsets.UTF_8).size
                        end++
                    }
                }
                if (bytes != size) return null
                parts += key.substring(offset, end)
                offset = end
            }
            if (offset != key.length || parts.size != 8 || parts[0] != "canonical-release-v1" ||
                parts[1] != "aniworld") return null
            val path = parts[2] ?: return null
            val number = parts[5]?.toIntOrNull() ?: return null
            if (parts[6] != null && parts[6]?.toIntOrNull() == null) return null
            val installment = when (parts[3]) {
                "EPISODE" -> runCatching { Installment.Episode(number, parts[6]?.toIntOrNull()) }
                    .getOrNull() ?: return null
                "FILM" -> {
                    if (parts[4] != null || parts[6] != null || number <= 0) return null
                    Installment.Film(number)
                }
                else -> return null
            }
            val season = parts[4]?.toIntOrNull()
            if (installment is Installment.Episode && (season == null || season < 0)) return null
            val track = runCatching { LanguageTrack.valueOf(parts[7] ?: return null) }.getOrNull()
                ?: return null
            if (!path.startsWith("/anime/stream/")) return null
            val site = runCatching { AniWorldSiteIdentifier(path.removePrefix("/anime/stream/")) }
                .getOrNull() ?: return null
            val result = CanonicalReleaseIdentity(site.canonicalSeriesPath, installment, season, track)
            return result.takeIf { it.key == key }
        }

        fun from(evidence: ReleaseEvidence): CanonicalReleaseIdentity? {
            val site = evidence.siteIdentifier ?: return null
            val installment = evidence.installment
            when (installment) {
                is Installment.Episode -> if (evidence.sourceSeason == null) return null
                is Installment.Film -> if (installment.number == null || installment.number <= 0 ||
                    evidence.sourceSeason != null) return null
                is Installment.Special -> return null
            }
            return CanonicalReleaseIdentity(
                site.canonicalSeriesPath, installment, evidence.sourceSeason,
                evidence.languageTrack ?: return null,
            )
        }

        private fun encode(vararg fields: String?): String = buildString {
            fields.forEach { field ->
                if (field == null) append("-1:") else {
                    append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field)
                }
            }
        }
    }
}

enum class ReleaseIdentityCompleteness { EXACT, PARTIAL, NON_BINDABLE }

fun ReleaseEvidence.identityCompleteness(): ReleaseIdentityCompleteness {
    if (siteIdentifier == null) return ReleaseIdentityCompleteness.NON_BINDABLE
    when (val part = installment) {
        is Installment.Special -> return ReleaseIdentityCompleteness.NON_BINDABLE
        is Installment.Film -> if (part.number == null || part.number <= 0 || sourceSeason != null) {
            return ReleaseIdentityCompleteness.NON_BINDABLE
        }
        is Installment.Episode -> Unit
    }
    return if (CanonicalReleaseIdentity.from(this) == null) {
        ReleaseIdentityCompleteness.PARTIAL
    } else ReleaseIdentityCompleteness.EXACT
}

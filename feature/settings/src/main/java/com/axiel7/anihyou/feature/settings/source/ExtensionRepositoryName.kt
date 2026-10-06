package com.axiel7.anihyou.feature.settings.source

import java.net.URI

/** A display label only. The accepted root and source URL still determine trust. */
internal fun repositoryDisplayName(url: String): String? = try {
    val uri = URI(url)
    val host = uri.host?.takeIf { it.isNotBlank() }
    val path = uri.path.orEmpty().trim('/').split('/')
    val knownCatalog = uri.scheme == "https" && host == "raw.githubusercontent.com" &&
        uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null &&
        (path == listOf("animine-ai", "release-extentions", "catalog") ||
            path == listOf("animine-ai", "release-extentions", "private-preview-catalog", "catalog"))
    if (knownCatalog) "AniHyou Extensions" else host
} catch (_: Exception) {
    null
}

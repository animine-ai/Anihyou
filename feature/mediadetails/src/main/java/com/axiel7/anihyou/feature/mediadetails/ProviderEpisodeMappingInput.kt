package com.axiel7.anihyou.feature.mediadetails

private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val HOSTNAME = Regex("(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::[0-9]{1,5})?$")

/** A provider series key is an opaque identifier, never a website URL or path. */
internal fun isValidProviderSeriesKey(value: String): Boolean =
    value.length in 1..128 &&
        value == value.trim() &&
        value.none {
            it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' || it == '?' || it == '#'
        } &&
        !value.startsWith("//") &&
        !URI_SCHEME.containsMatchIn(value) &&
        !HOSTNAME.matches(value)

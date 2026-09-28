package com.axiel7.anihyou.release.core.extension

/** Provider coordinates are mapped by the host before entering the extension. */
enum class NavigationTargetKind { OVERVIEW, EPISODE }

data class NavigationContextV1(
    val schemaVersion: Int,
    val extensionId: ExtensionId,
    val providerId: ProviderId,
    val observedAt: String,
    val targetKind: NavigationTargetKind,
    val targetToken: String,
    val providerSeriesKey: String,
    val providerRouteHint: String?,
    val sourceSeason: Int?,
    val providerEpisode: String?,
    val track: ObservationTrack?,
)

/** Navigation uses the same capability-neutral host HTTP boundary as release requests. */
data class NavigationRequestSpecV1(val requestId: String, val url: String)
data class NavigationPlanOutputV1(val schemaVersion: Int, val requests: List<NavigationRequestSpecV1>)

data class NavigationResponseEnvelopeV1(
    val requestId: String,
    val status: ExtensionResponseStatus,
    val httpStatus: Int?,
    val finalUrl: String?,
    val bodyUtf8: String?,
    val sourceHash: String?,
)

data class ProviderNavigationTargetV1(
    val schemaVersion: Int,
    val extensionId: ExtensionId,
    val providerId: ProviderId,
    val targetKind: NavigationTargetKind,
    val providerSeriesKey: String,
    val sourceSeason: Int?,
    val providerEpisode: String?,
    val track: ObservationTrack?,
    val url: String,
    val requestId: String?,
    val sourceHash: String?,
    val diagnostics: List<ObservationDiagnosticV1>,
)

data class NavigationParseOutputV1(val schemaVersion: Int, val targets: List<ProviderNavigationTargetV1>)
